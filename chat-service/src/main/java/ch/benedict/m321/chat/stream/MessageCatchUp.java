package ch.benedict.m321.chat.stream;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ch.benedict.m321.chat.message.Message;
import ch.benedict.m321.chat.message.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Holt bei einer Neuverbindung die verpassten Nachrichten aus der Datenbank nach und schaltet die
 * Verbindung danach auf live um. Die Schwierigkeit dabei: Waehrend wir die Datenbank lesen, koennen
 * neue Nachrichten ankommen. Sie duerfen weder verloren gehen noch die alten ueberholen.
 *
 * Darum ist die Reihenfolge wichtig: Zuerst tragen wir die Verbindung ein, dann lesen wir. Waere es
 * umgekehrt, gingen Nachrichten verloren, die zwischen dem Lesen und dem Eintragen ankommen.
 * Solange das Nachholen laeuft, sammelt die Registry die Live-Nachrichten fuer diese Verbindung.
 */
@Component
public class MessageCatchUp {

    private static final Logger log = LoggerFactory.getLogger(MessageCatchUp.class);

    private final SseConnectionRegistry connectionRegistry;
    private final MessageEventSender eventSender;
    private final MessageService messageService;

    // Alle drei Werkzeuge kommen per Konstruktor-Injektion herein.
    public MessageCatchUp(SseConnectionRegistry connectionRegistry, MessageEventSender eventSender,
                          MessageService messageService) {
        this.connectionRegistry = connectionRegistry;
        this.eventSender = eventSender;
        this.messageService = messageService;
    }

    /**
     * Traegt die Verbindung ein, sendet alle Nachrichten nach lastEventId und danach die in der
     * Zwischenzeit gesammelten. Wenn diese Methode endet, laeuft die Verbindung normal live.
     */
    public void run(UUID roomId, UUID lastEventId, SseEmitter connection) {
        connectionRegistry.startCatchUp(roomId, connection);

        List<Message> missedMessages = messageService.loadMessagesAfter(roomId, lastEventId);
        log.info("Neuverbindung: Raum {}, {} Nachrichten werden nachgeholt", roomId, missedMessages.size());

        // Wir merken uns, was schon gesendet ist. Eine neue Nachricht kann naemlich beides sein:
        // bereits in der Datenbank und zusaetzlich gerade in der Registry gesammelt.
        Set<UUID> sentMessageIds = new HashSet<>();
        for (Message message : missedMessages) {
            eventSender.send(connection, message);
            sentMessageIds.add(message.id());
        }

        sendHeldMessages(connection, sentMessageIds);
    }

    /**
     * Sendet die waehrend des Nachholens gesammelten Live-Nachrichten, ohne Doppelte.
     * Die Registry beendet das Sammeln erst, wenn sie uns eine leere Liste geben musste.
     */
    private void sendHeldMessages(SseEmitter connection, Set<UUID> sentMessageIds) {
        List<Message> heldMessages = connectionRegistry.takeHeldMessages(connection);

        // Eine Schleife statt eines einzelnen Durchgangs: Der Consumer-Thread kann weitere
        // Nachrichten sammeln, waehrend dieser Thread die vorherigen sendet. Erst wenn nichts mehr
        // wartet, sendet der Consumer selbst. So kann er keine gesammelte Nachricht ueberholen.
        while (!heldMessages.isEmpty()) {
            for (Message message : heldMessages) {
                boolean firstTime = sentMessageIds.add(message.id());
                if (firstTime) {
                    eventSender.send(connection, message);
                }
            }
            heldMessages = connectionRegistry.takeHeldMessages(connection);
        }
    }
}
