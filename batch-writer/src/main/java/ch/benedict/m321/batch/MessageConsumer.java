package ch.benedict.m321.batch;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Holt Nachrichten paketweise aus der Queue "chat.persist" und schreibt jedes Paket auf
 * einmal in die Datenbank. Scheitert ein Paket, wird es Zeile fuer Zeile nachgeschrieben,
 * damit eine einzelne kaputte Nachricht nicht alle anderen aufhaelt.
 */
@Component   // Spring baut diese Klasse beim Start und meldet den @RabbitListener unten an.
public class MessageConsumer {

    // Ein Logger fuer die ganze Klasse, darum static final.
    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);

    // Schreibt die Nachrichten in die Tabelle message.
    private final MessageRepository messageRepository;

    // Werkzeug von Spring zum SENDEN an RabbitMQ. Wir brauchen es nur fuer chat.dlq.
    private final RabbitTemplate rabbitTemplate;

    /**
     * Spring reicht Repository und RabbitTemplate ueber den Konstruktor herein.
     */
    public MessageConsumer(MessageRepository messageRepository, RabbitTemplate rabbitTemplate) {
        this.messageRepository = messageRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Wird von Spring fuer jedes Paket aus "chat.persist" aufgerufen. Normalfall: ein
     * batchUpdate fuer das ganze Paket. Das ACK fuer alle Nachrichten im Paket sendet Spring
     * erst, wenn diese Methode ohne Fehler zurueckkehrt.
     */
    // @RabbitListener startet im Hintergrund einen eigenen Thread, der dauerhaft mit RabbitMQ
    // verbunden bleibt. Weil die Fabrik auf Buendeln eingestellt ist (setBatchListener), kommt
    // hier eine Liste an - jedes Element bereits vom JSON in ein Message-Objekt umgewandelt.
    @RabbitListener(queues = RabbitConfiguration.PERSIST_QUEUE_NAME)
    public void receive(List<Message> messages) {
        try {
            messageRepository.insertBatch(messages);

            int packetSize = messages.size();
            log.info("Paket mit {} Nachrichten in einer Transaktion geschrieben", packetSize);
        } catch (DataAccessResourceFailureException | CannotCreateTransactionException databaseUnreachable) {
            // Die Datenbank ist nicht erreichbar. Der senkrechte Strich "|" heisst: dieser
            // catch-Block gilt fuer BEIDE Fehlerarten.
            // - DataAccessResourceFailureException: die Verbindung bricht waehrend des
            //   Schreibens ab (oder es gibt gar keine).
            // - CannotCreateTransactionException: schon das Oeffnen der Transaktion
            //   (@Transactional an insertBatch) scheitert, weil keine Verbindung zustande kommt.
            //   Das ist der haeufigste Fall, wenn Postgres gestoppt ist (im Test beobachtet).
            // Einzeln nachschreiben waere sinnlos - es wuerde genauso scheitern. Wir werfen den
            // Fehler weiter: dann gibt es kein ACK, das ganze Paket bleibt in der Queue und
            // wird spaeter erneut versucht (S7).
            log.warn("Datenbank nicht erreichbar - Paket bleibt in der Queue");
            throw databaseUnreachable;
        } catch (DataAccessException batchFailed) {
            // Irgendetwas im Paket stimmt nicht. Dank @Transactional wurde das ganze Paket
            // zurueckgerollt - es steht also noch KEINE Zeile davon in der Tabelle.
            log.warn("Paket mit {} Nachrichten gescheitert ({}) - schreibe einzeln nach",
                    messages.size(), batchFailed.getClass().getSimpleName());
            insertOneByOne(messages);
        }
    }

    /**
     * Einzelweg-Fallback: schreibt die Nachrichten eines gescheiterten Pakets eine nach der
     * anderen. So werden alle gesunden gespeichert und nur die kaputte wird aussortiert.
     */
    private void insertOneByOne(List<Message> messages) {
        for (Message message : messages) {
            try {
                messageRepository.insertOne(message);
            } catch (DataIntegrityViolationException invalidData) {
                // Die DATEN dieser Nachricht passen nicht zur Tabelle, z.B. ein Raum, den es
                // nicht gibt, oder ein fehlender Text. Ein weiterer Versuch wuerde immer
                // wieder scheitern - darum aussortieren und mit der naechsten weitermachen.
                sendToDeadLetterQueue(message, invalidData);
            }
            // Alle ANDEREN Fehler fangen wir hier absichtlich nicht ab, allen voran
            // "Datenbank nicht erreichbar". Sie fliegen bis zu Spring durch: kein ACK, das
            // ganze Paket bleibt in der Queue. Bereits geschriebene Nachrichten schaden beim
            // naechsten Versuch nicht - ON CONFLICT (id) DO NOTHING ueberspringt sie.
            // Lieber einmal zu viel wiederholen als eine gueltige Nachricht in chat.dlq.
        }
    }

    /**
     * Legt eine Nachricht, die sich nie speichern laesst, in die Queue chat.dlq. Danach gilt
     * sie fuer chat.persist als erledigt und wird mit dem Paket bestaetigt.
     */
    private void sendToDeadLetterQueue(Message message, DataIntegrityViolationException reason) {
        log.error("Nachricht {} laesst sich nicht speichern und geht nach {}: {}",
                message.id(), RabbitConfiguration.DEAD_LETTER_QUEUE_NAME,
                reason.getMostSpecificCause().getMessage());

        // Exchange "" ist der Standard-Exchange von RabbitMQ: er liefert direkt an die Queue,
        // deren Name als Routing-Key angegeben ist - hier also an chat.dlq. Die Nachricht wird
        // mit unserem JSON-Umwandler wieder zu JSON und bleibt so in der Management-
        // Oberflaeche lesbar.
        rabbitTemplate.convertAndSend("", RabbitConfiguration.DEAD_LETTER_QUEUE_NAME, message);
    }
}
