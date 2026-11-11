package ch.benedict.m321.chat.stream;

import java.io.IOException;

import ch.benedict.m321.chat.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Schickt eine Nachricht als SSE-Ereignis an eine einzelne Verbindung.
 * Es gibt diese Klasse, weil Live-Nachrichten und nachgeholte Nachrichten beim Client genau gleich
 * aussehen muessen. Beide Wege benutzen darum denselben Code.
 */
@Component
public class MessageEventSender {

    private static final Logger log = LoggerFactory.getLogger(MessageEventSender.class);

    private final SseConnectionRegistry connectionRegistry;

    // Die Registry brauchen wir, um eine tote Verbindung austragen zu koennen.
    public MessageEventSender(SseConnectionRegistry connectionRegistry) {
        this.connectionRegistry = connectionRegistry;
    }

    /**
     * Schickt die Nachricht an die Verbindung und traegt diese aus, wenn es scheitert.
     * Der Fehler wird hier abgefangen, damit eine tote Verbindung die anderen im Raum nicht aufhaelt.
     */
    public void send(SseEmitter connection, Message message) {
        SseEmitter.SseEventBuilder event = buildEvent(message);

        try {
            connection.send(event);
        } catch (IOException | IllegalStateException sendFailed) {
            // Der senkrechte Strich "|" heisst: dieser catch-Block gilt fuer BEIDE Fehlerarten.
            // - IOException: Das Schreiben ins Netzwerk ist gescheitert, der Client ist weg.
            // - IllegalStateException: Die Verbindung war schon beendet, als wir senden wollten.
            //   Das kann passieren, weil onCompletion auf einem anderen Thread laeuft.
            log.debug("Senden fehlgeschlagen, Verbindung wird ausgetragen: Raum {}, Grund: {}",
                    message.roomId(), sendFailed.getMessage());
            connectionRegistry.remove(message.roomId(), connection);
        }
    }

    /**
     * Baut das SSE-Ereignis fuer eine Nachricht. Das steht in einer eigenen Methode, damit jede
     * Verbindung ihr eigenes Ereignis-Objekt bekommt.
     */
    private SseEmitter.SseEventBuilder buildEvent(Message message) {
        String eventId = message.id().toString();

        SseEmitter.SseEventBuilder event = SseEmitter.event();

        // Der Browser merkt sich die letzte id und schickt sie beim Wiederverbinden automatisch
        // im Header Last-Event-ID mit. Genau damit holt der StreamController dann nach.
        event.id(eventId);

        // Spring wandelt die Nachricht erst beim Senden in JSON um, mit demselben ObjectMapper
        // wie bei den REST-Antworten. So sieht sie gleich aus wie in chat.persist.
        event.data(message, MediaType.APPLICATION_JSON);

        return event;
    }
}
