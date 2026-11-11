package ch.benedict.m321.chat.stream;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.message.Message;
import ch.benedict.m321.chat.message.MessageService;
import ch.benedict.m321.chat.rabbit.LiveMessageConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testet das Nachholen ohne Spring, Datenbank und Netzwerk. Der MessageService ist eine Attrappe,
 * damit wir genau steuern koennen, was waehrend des Lesens aus der Datenbank passiert.
 */
class MessageCatchUpTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LAST_EVENT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000");

    private final Message firstMissed = newMessage("aaaaaaaa-0000-0000-0000-000000000001");
    private final Message secondMissed = newMessage("aaaaaaaa-0000-0000-0000-000000000002");
    private final Message liveMessage = newMessage("aaaaaaaa-0000-0000-0000-000000000003");

    private final SseConnectionRegistry registry = new SseConnectionRegistry();
    private final MessageEventSender eventSender = new MessageEventSender(registry);
    private final MessageService messageService = mock(MessageService.class);
    private final MessageCatchUp catchUp = new MessageCatchUp(registry, eventSender, messageService);
    private final LiveMessageConsumer consumer = new LiveMessageConsumer(registry, eventSender);

    /**
     * Baut eine Beispiel-Nachricht mit der angegebenen id.
     */
    private static Message newMessage(String id) {
        return new Message(UUID.fromString(id), ROOM_ID, "lernende1", "Hallo", Instant.parse("2026-09-04T08:05:00Z"));
    }

    /**
     * Die Datenbank liefert die verpassten Nachrichten, und genau diese kommen in dieser
     * Reihenfolge beim Client an.
     */
    @Test
    void sendsMissedMessagesInOrder() {
        when(messageService.loadMessagesAfter(ROOM_ID, LAST_EVENT_ID))
                .thenReturn(List.of(firstMissed, secondMissed));
        RecordingConnection connection = new RecordingConnection();

        catchUp.run(ROOM_ID, LAST_EVENT_ID, connection);

        assertEquals(List.of(firstMissed, secondMissed), connection.receivedMessages);
    }

    /**
     * Der schwierigste Fall: Waehrend wir die Datenbank lesen, kommt eine Live-Nachricht an.
     * Sie darf nicht verloren gehen und die nachgeholten Nachrichten nicht ueberholen.
     */
    @Test
    void liveMessageArrivingDuringReadIsSentAfterMissedMessages() {
        when(messageService.loadMessagesAfter(ROOM_ID, LAST_EVENT_ID)).thenAnswer(call -> {
            consumer.receive(liveMessage);
            return List.of(firstMissed, secondMissed);
        });
        RecordingConnection connection = new RecordingConnection();

        catchUp.run(ROOM_ID, LAST_EVENT_ID, connection);

        assertEquals(List.of(firstMissed, secondMissed, liveMessage), connection.receivedMessages);
    }

    /**
     * Eine Nachricht kann gleichzeitig in der Datenbank stehen und live ankommen.
     * Der Client darf sie trotzdem nur einmal bekommen.
     */
    @Test
    void messageFoundInDatabaseAndArrivingLiveIsSentOnlyOnce() {
        when(messageService.loadMessagesAfter(ROOM_ID, LAST_EVENT_ID)).thenAnswer(call -> {
            consumer.receive(secondMissed);
            return List.of(firstMissed, secondMissed);
        });
        RecordingConnection connection = new RecordingConnection();

        catchUp.run(ROOM_ID, LAST_EVENT_ID, connection);

        assertEquals(List.of(firstMissed, secondMissed), connection.receivedMessages);
    }

    /**
     * Nach dem Nachholen sendet der Consumer wieder direkt, statt die Nachricht zu sammeln.
     */
    @Test
    void connectionIsLiveAfterCatchUp() {
        when(messageService.loadMessagesAfter(ROOM_ID, LAST_EVENT_ID)).thenReturn(List.of());
        RecordingConnection connection = new RecordingConnection();

        catchUp.run(ROOM_ID, LAST_EVENT_ID, connection);
        consumer.receive(liveMessage);

        assertEquals(List.of(liveMessage), connection.receivedMessages);
    }

    /**
     * Eine Verbindung, die sich die Nachrichten nur merkt, statt sie ins Netzwerk zu schreiben.
     */
    private static class RecordingConnection extends SseEmitter {
        final List<Object> receivedMessages = new ArrayList<>();

        @Override
        public void send(SseEventBuilder event) {
            // Ein Ereignis besteht aus zwei Teilen: dem Text "id:...data:" und der Nachricht.
            List<DataWithMediaType> parts = new ArrayList<>(event.build());
            receivedMessages.add(parts.get(1).getData());
        }
    }
}
