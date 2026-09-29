package ch.benedict.m321.batch;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Holt Nachrichten paketweise aus der Queue "chat.persist" und schreibt jedes Paket auf
 * einmal in die Datenbank. Wie gross ein Paket wird, steht in RabbitConfiguration.
 */
@Component   // Spring baut diese Klasse beim Start und meldet den @RabbitListener unten an.
public class MessageConsumer {

    // Ein Logger fuer die ganze Klasse, darum static final.
    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);

    // Schreibt die Nachrichten in die Tabelle message.
    private final MessageRepository messageRepository;

    /**
     * Spring reicht das Repository ueber den Konstruktor herein (Konstruktor-Injektion).
     */
    public MessageConsumer(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Wird von Spring fuer jedes Paket aus "chat.persist" aufgerufen und speichert es als
     * Ganzes. Das ACK fuer alle Nachrichten im Paket sendet Spring erst, wenn diese Methode
     * ohne Fehler zurueckkehrt.
     */
    // @RabbitListener startet im Hintergrund einen eigenen Thread, der dauerhaft mit RabbitMQ
    // verbunden bleibt. Weil die Fabrik auf Buendeln eingestellt ist (setBatchListener), kommt
    // hier eine Liste an - jedes Element bereits vom JSON in ein Message-Objekt umgewandelt.
    @RabbitListener(queues = RabbitConfiguration.PERSIST_QUEUE_NAME)
    public void receive(List<Message> messages) {
        // Scheitert das Schreiben (z.B. Datenbank weg), wirft insertBatch eine Exception.
        // Wir fangen sie absichtlich NICHT ab: nur so bleibt das ACK fuer das ganze Paket aus
        // und alle Nachrichten bleiben in der Queue.
        messageRepository.insertBatch(messages);

        int packetSize = messages.size();
        log.info("Paket mit {} Nachrichten in einer Transaktion geschrieben", packetSize);
    }
}
