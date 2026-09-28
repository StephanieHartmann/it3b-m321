package ch.benedict.m321.chat.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.rabbit.RabbitConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Enthaelt die Fachlogik rund um Nachrichten.
 *
 * Der Controller (die naechste Klasse) kennt NUR diesen Service - er kennt weder das
 * Repository noch spaeter RabbitMQ direkt. Diese Trennung in drei Schichten
 * (Controller -> Service -> Repository) ist bewusst so gewaehlt: die Weboberflaeche (Controller)
 * bleibt getrennt von der Technik dahinter (Datenbank, Broker). Aendert sich spaeter zum
 * Beispiel, WIE wir Nachrichten lesen oder pruefen, muss der Controller davon nichts wissen.
 *
 * Der Service kann zwei Dinge: den Verlauf aus der Datenbank LESEN und neue Nachrichten an
 * RabbitMQ SENDEN. In die Datenbank schreibt er nie - das macht spaeter der batch-service.
 */
@Service   // Markiert diese Klasse als "Fachlogik-Baustein" - genauso wie @Repository, nur fuer
           // eine andere Schicht. Spring baut auch sie automatisch beim Start.
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    // RabbitTemplate ist fuer RabbitMQ, was JdbcTemplate fuer die Datenbank ist: ein fertiges
    // Werkzeug von Spring, das Verbindung, Kanal und Umwandlung in JSON (ueber unseren
    // Jackson2JsonMessageConverter aus RabbitConfiguration) fuer uns erledigt. Spring baut es
    // automatisch, weil in application.yml die Adresse des Brokers steht.
    private final RabbitTemplate rabbitTemplate;

    // Auch hier gilt: das Repository wird nicht selbst mit "new" gebaut, sondern von Spring
    // ueber den Konstruktor hereingereicht (Konstruktor-Injektion, siehe MessageRepository).
    private final MessageRepository messageRepository;

    // Beide Werkzeuge kommen per Konstruktor-Injektion herein. Im Controller-Test ersetzt
    // @MockitoBean den ganzen Service, deshalb braucht jener Test keinen echten Broker.
    public MessageService(RabbitTemplate rabbitTemplate, MessageRepository messageRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageRepository = messageRepository;
    }

    /**
     * Liefert den Verlauf eines Raums - reines Lesen, hier wird nichts veraendert.
     * Die Methode ist bewusst duenn: sie loggt und reicht den Aufruf einfach an das Repository
     * weiter. Trotzdem lohnt sich eine eigene Service-Methode, weil hier spaeter zum Beispiel
     * eine Mitgliedschaftspruefung dazukommen koennte (Raumverwaltung, Phase 6 im Gesamtplan),
     * ohne dass sich am Controller etwas aendern muesste.
     */
    public List<Message> loadHistory(UUID roomId, int limit) {
        log.debug("Verlauf angefordert: Raum {}, hoechstens {} Nachrichten", roomId, limit);
        return messageRepository.findLatest(roomId, limit);
    }

    /**
     * Nimmt eine neue Nachricht an und gibt sie an RabbitMQ weiter.
     *
     * Wichtig: hier wird NICHT in die Datenbank geschrieben. Der chat-service
     * publiziert nur; gespeichert wird spaeter gebuendelt vom batch-service
     * (PLANUNG.md, Abschnitt 2.3).
     */
    public Message sendMessage(NewMessage incoming) {
        // Die ID vergeben WIR, nicht die Datenbank. Nur so kann der batch-service
        // ein Paket gefahrlos wiederholen, ohne Dubletten zu erzeugen.
        UUID id = UUID.randomUUID();

        // Auch die Zeit setzen wir hier: das ist der Moment des SENDENS.
        // Die Datenbank wuerde spaeter den Moment des SCHREIBENS festhalten.
        Instant sentAt = Instant.now();

        // Aus dem, was der Client geschickt hat (roomId, sender, text), und dem, was wir selbst
        // vergeben haben (id, sentAt), bauen wir die vollstaendige Nachricht.
        Message message = new Message(id, incoming.roomId(), incoming.sender(),
                incoming.text(), sentAt);

        log.info("Nachricht {} von {} fuer Raum {} wird publiziert",
                id, incoming.sender(), incoming.roomId());

        // Zweites Argument ist der Routing-Key. Ein Fanout-Exchange ignoriert ihn,
        // deshalb steht dort der leere String.
        // convertAndSend heisst woertlich "umwandeln und senden": zuerst macht der
        // JSON-Converter aus dem Message-Objekt JSON-Text, dann geht dieser Text ueber das
        // Netzwerk an den Broker. Die Methode wartet NICHT darauf, dass jemand die Nachricht
        // liest - sie gibt sie nur beim Exchange ab. Von dort verteilt RabbitMQ sie an alle
        // gebundenen Queues, im Moment also an chat.persist.
        rabbitTemplate.convertAndSend(RabbitConfiguration.EXCHANGE_NAME, "", message);

        log.info("Nachricht {} an Exchange {} uebergeben", id, RabbitConfiguration.EXCHANGE_NAME);
        return message;
    }
}
