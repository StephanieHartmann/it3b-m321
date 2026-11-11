package ch.benedict.m321.chat.rabbit;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.message.Message;
import ch.benedict.m321.chat.stream.MessageEventSender;
import ch.benedict.m321.chat.stream.SseConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testet das Verteilen an die Verbindungen als gewoehnliche Klasse, ohne Spring, Broker und
 * Netzwerk. Statt echter Verbindungen benutzen wir die beiden kleinen Hilfsklassen ganz unten.
 */
class LiveMessageConsumerTest {

    private static final UUID ROOM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MESSAGE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    private final SseConnectionRegistry registry = new SseConnectionRegistry();
    private final MessageEventSender eventSender = new MessageEventSender(registry);
    private final LiveMessageConsumer consumer = new LiveMessageConsumer(registry, eventSender);

    /**
     * Baut eine Beispiel-Nachricht fuer einen Raum, damit nicht jeder Test alle Felder aufzaehlt.
     */
    private Message messageForRoom(UUID roomId) {
        return new Message(MESSAGE_ID, roomId, "lernende1", "Hallo", Instant.parse("2026-09-04T08:05:00Z"));
    }

    /**
     * Das ist der Kern des gezielten Sendens: Nur der Raum der Nachricht bekommt sie.
     */
    @Test
    void sendsOnlyToConnectionsOfTheMessageRoom() {
        RecordingConnection connectionInRoomA = new RecordingConnection();
        RecordingConnection connectionInRoomB = new RecordingConnection();
        registry.add(ROOM_A, connectionInRoomA);
        registry.add(ROOM_B, connectionInRoomB);

        consumer.receive(messageForRoom(ROOM_A));

        assertEquals(1, connectionInRoomA.receivedEvents.size());
        assertTrue(connectionInRoomB.receivedEvents.isEmpty());
    }

    /**
     * Prueft den Inhalt des Ereignisses: Zeile "id:" mit der Nachrichten-ID und die Nachricht
     * selbst als Daten. Dass daraus das richtige JSON wird, erledigt Spring beim echten Senden.
     */
    @Test
    void eventContainsMessageIdAndMessage() {
        RecordingConnection connection = new RecordingConnection();
        registry.add(ROOM_A, connection);
        Message message = messageForRoom(ROOM_A);

        consumer.receive(message);

        List<Object> eventParts = new ArrayList<>();
        SseEmitter.SseEventBuilder event = connection.receivedEvents.get(0);
        for (ResponseBodyEmitter.DataWithMediaType part : event.build()) {
            eventParts.add(part.getData());
        }
        assertEquals("id:" + MESSAGE_ID + "\ndata:", eventParts.get(0));
        assertEquals(message, eventParts.get(1));
    }

    /**
     * Eine tote Verbindung (IOException) wird ausgetragen, die naechste bekommt die Nachricht.
     */
    @Test
    void removesFailedConnectionAndContinuesWithTheOthers() {
        BrokenConnection brokenConnection = new BrokenConnection();
        RecordingConnection healthyConnection = new RecordingConnection();
        registry.add(ROOM_A, brokenConnection);
        registry.add(ROOM_A, healthyConnection);

        consumer.receive(messageForRoom(ROOM_A));

        assertEquals(1, healthyConnection.receivedEvents.size());
        assertEquals(List.of(healthyConnection), registry.findConnections(ROOM_A));
    }

    /**
     * Eine bereits beendete Verbindung wirft beim Senden eine IllegalStateException.
     * Auch sie muss ausgetragen werden und darf den Consumer nicht abbrechen.
     */
    @Test
    void removesCompletedConnection() {
        SseEmitter completedConnection = new SseEmitter();
        completedConnection.complete();
        registry.add(ROOM_A, completedConnection);

        consumer.receive(messageForRoom(ROOM_A));

        assertTrue(registry.findConnections(ROOM_A).isEmpty());
    }

    /**
     * Eine Nachricht ohne id darf keine Exception ausloesen, sonst kaeme sie endlos zurueck.
     */
    @Test
    void dropsMessageWithoutIdWithoutSending() {
        RecordingConnection connection = new RecordingConnection();
        registry.add(ROOM_A, connection);
        Message messageWithoutId = new Message(null, ROOM_A, "hand", "kaputt", null);

        consumer.receive(messageWithoutId);

        assertTrue(connection.receivedEvents.isEmpty());
    }

    /**
     * Eine Verbindung, die sich die Ereignisse nur merkt, statt sie ins Netzwerk zu schreiben.
     */
    private static class RecordingConnection extends SseEmitter {
        final List<SseEventBuilder> receivedEvents = new ArrayList<>();

        @Override
        public void send(SseEventBuilder event) {
            receivedEvents.add(event);
        }
    }

    /**
     * Eine Verbindung, deren Client weg ist: Jeder Sendeversuch scheitert.
     */
    private static class BrokenConnection extends SseEmitter {
        @Override
        public void send(SseEventBuilder event) throws IOException {
            throw new IOException("Client ist nicht mehr verbunden");
        }
    }
}
