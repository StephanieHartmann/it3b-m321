package ch.benedict.m321.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Holt Nachrichten aus der Queue "chat.persist" und speichert jede einzeln in der Datenbank.
 * Das ist der einfachste Weg Ende-zu-Ende; in Schritt 3 wird auf Buendeln umgestellt.
 */
@Component   // Spring baut diese Klasse beim Start und meldet den @RabbitListener unten an.
public class MessageConsumer {

    // Ein Logger fuer die ganze Klasse, darum static final.
    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);

    // Schreibt die Nachricht in die Tabelle message.
    private final MessageRepository messageRepository;

    /**
     * Spring reicht das Repository ueber den Konstruktor herein (Konstruktor-Injektion).
     */
    public MessageConsumer(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Wird von Spring fuer jede Nachricht in "chat.persist" aufgerufen und speichert sie.
     * Das ACK an RabbitMQ sendet Spring erst, wenn diese Methode ohne Fehler zurueckkehrt.
     */
    // @RabbitListener startet im Hintergrund einen eigenen Thread, der dauerhaft mit RabbitMQ
    // verbunden bleibt und auf neue Nachrichten wartet. Das JSON ist beim Aufruf bereits in ein
    // Message-Objekt umgewandelt (siehe jsonMessageConverter in RabbitConfiguration).
    @RabbitListener(queues = RabbitConfiguration.PERSIST_QUEUE_NAME)
    public void receive(Message message) {
        // Scheitert der INSERT (z.B. Datenbank weg, Szenario S7), wirft insertOne eine
        // Exception. Wir fangen sie absichtlich NICHT ab: nur wenn sie bis zu Spring
        // durchkommt, bleibt das ACK aus und die Nachricht bleibt in der Queue.
        int insertedRows = messageRepository.insertOne(message);

        // 0 eingefuegte Zeilen heisst: diese id stand schon in der Tabelle (doppelte Zustellung).
        if (insertedRows == 0) {
            log.info("Nachricht {} war schon gespeichert - uebersprungen", message.id());
        } else {
            log.info("Nachricht {} von {} in Raum {} gespeichert",
                    message.id(), message.sender(), message.roomId());
        }
    }
}
