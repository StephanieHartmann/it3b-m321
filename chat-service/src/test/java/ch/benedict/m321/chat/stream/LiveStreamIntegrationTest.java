package ch.benedict.m321.chat.stream;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import ch.benedict.m321.chat.TestcontainersConfiguration;
import ch.benedict.m321.chat.message.Message;
import ch.benedict.m321.chat.rabbit.RabbitConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prueft den ganzen Live-Weg: Fanout-Exchange -> Instanz-Queue -> Consumer -> SSE-Client.
 * Alles daran ist echt: RabbitMQ und PostgreSQL laufen in Docker, die Anwendung hoert auf einem
 * echten Port, und die Clients lesen ueber echte HTTP-Verbindungen.
 *
 * Die Tests kommen ohne sleep aus. Dass etwas NICHT ankommt, pruefen wir mit einer Marker-Nachricht:
 * Der Consumer arbeitet die Queue der Reihe nach ab. Ist die naechste Nachricht beim Client der
 * Marker, dann war die Nachricht davor schon verarbeitet und hat ihn nicht erreicht.
 */
// RANDOM_PORT startet den eingebauten Webserver auf einem freien Port. Das Standard-Testumfeld
// haette gar keinen Server, und ohne ihn gaebe es keine echte SSE-Verbindung.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
// Obergrenze pro Test: Eine haengende Netzwerkverbindung soll den Test scheitern lassen und nicht
// den ganzen Lauf von "mvn test" blockieren. Der eigene Thread ist noetig, damit JUnit einen
// blockierten Test wirklich abbrechen kann.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LiveStreamIntegrationTest {

    @LocalServerPort
    private int port;

    // Publiziert mit demselben JSON-Umwandler wie der MessageService im echten Betrieb.
    @Autowired
    private RabbitTemplate rabbitTemplate;

    // Die Tests schreiben selbst in die Datenbank, weil hier kein batch-writer mitlaeuft.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Wandelt das Feld data der SSE-Ereignisse zurueck in ein Message-Objekt.
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Verteilung (S3): Eine Nachricht erreicht alle Clients ihres Raums und keinen anderen Raum.
     */
    @Test
    void messageReachesClientsOfItsRoomOnly() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        Message messageForA = newMessage("00000000-0000-0000-0001-000000000001", roomA, 1);
        Message markerForB = newMessage("00000000-0000-0000-0001-000000000002", roomB, 2);
        Message markerForA = newMessage("00000000-0000-0000-0001-000000000003", roomA, 3);

        try (SseTestClient firstClientInA = SseTestClient.connect(port, roomA);
             SseTestClient secondClientInA = SseTestClient.connect(port, roomA);
             SseTestClient clientInB = SseTestClient.connect(port, roomB)) {

            publish(messageForA);

            // Beide Clients in Raum A bekommen die Nachricht, nicht nur einer.
            assertNextEventIs(messageForA, firstClientInA);
            assertNextEventIs(messageForA, secondClientInA);

            publish(markerForB);

            // Das erste Ereignis in Raum B ist der Marker, also kam die Nachricht fuer A dort nie an.
            assertNextEventIs(markerForB, clientInB);

            publish(markerForA);

            // Dasselbe in die andere Richtung: Der Marker fuer B kam in Raum A nicht an.
            assertNextEventIs(markerForA, firstClientInA);
            assertNextEventIs(markerForA, secondClientInA);
        }
    }

    /**
     * Neuverbindung (S7): Nach einem Abbruch holt der Client mit Last-Event-ID genau die fehlenden
     * Nachrichten in der richtigen Reihenfolge nach, keine doppelt, und laeuft danach live weiter.
     */
    @Test
    void reconnectingClientCatchesUpExactlyTheMissedMessages() throws Exception {
        UUID roomId = UUID.randomUUID();
        createRoom(roomId);

        Message firstSeen = newMessage("00000000-0000-0000-0002-000000000001", roomId, 1);
        Message lastSeen = newMessage("00000000-0000-0000-0002-000000000002", roomId, 2);

        // Die ersten beiden fehlenden Nachrichten haben dieselbe Zeit. Hier entscheidet die id.
        Message firstMissed = newMessage("00000000-0000-0000-0002-000000000003", roomId, 3);
        Message secondMissed = newMessage("00000000-0000-0000-0002-000000000004", roomId, 3);
        Message thirdMissed = newMessage("00000000-0000-0000-0002-000000000005", roomId, 4);
        Message liveAfterReconnect = newMessage("00000000-0000-0000-0002-000000000006", roomId, 5);

        // Der Beobachter bleibt die ganze Zeit verbunden. Er zeigt uns unten, wann der Consumer
        // die verpassten Nachrichten fertig verarbeitet hat.
        try (SseTestClient observer = SseTestClient.connect(port, roomId)) {
            SseTestClient client = SseTestClient.connect(port, roomId);

            publishAndSave(firstSeen);
            publishAndSave(lastSeen);
            assertNextEventIs(firstSeen, client);
            assertNextEventIs(lastSeen, client);
            assertNextEventIs(firstSeen, observer);
            assertNextEventIs(lastSeen, observer);

            client.close();

            // Wir speichern absichtlich in umgekehrter Reihenfolge. Sonst koennte die Reihenfolge
            // auch ohne ORDER BY stimmen, und der Test wuerde einen Fehler nicht bemerken.
            save(thirdMissed);
            save(secondMissed);
            save(firstMissed);
            publish(firstMissed);
            publish(secondMissed);
            publish(thirdMissed);

            // Erst wenn der Beobachter alle hat, sind sie am Consumer vorbei. Der neu verbundene
            // Client kann sie jetzt nur noch aus der Datenbank bekommen, nicht zusaetzlich live.
            assertNextEventIs(firstMissed, observer);
            assertNextEventIs(secondMissed, observer);
            assertNextEventIs(thirdMissed, observer);

            UUID lastEventId = lastSeen.id();
            try (SseTestClient reconnected = SseTestClient.reconnect(port, roomId, lastEventId)) {
                assertNextEventIs(firstMissed, reconnected);
                assertNextEventIs(secondMissed, reconnected);
                assertNextEventIs(thirdMissed, reconnected);

                publishAndSave(liveAfterReconnect);

                // Direkt danach kommt die neue Nachricht. Haette der Client etwas doppelt
                // bekommen, stuende das hier vor ihr, und dieser Vergleich wuerde scheitern.
                assertNextEventIs(liveAfterReconnect, reconnected);
            }
        }
    }

    /**
     * Prueft, dass das naechste Ereignis des Clients genau diese Nachricht ist: id ist die
     * Nachrichten-ID, data ist die ganze Nachricht als JSON.
     */
    private void assertNextEventIs(Message expected, SseTestClient client) throws Exception {
        SseTestClient.SseEvent event = client.nextEvent();
        assertEquals(expected.id().toString(), event.id());

        Message received = objectMapper.readValue(event.data(), Message.class);
        assertEquals(expected, received);
    }

    /**
     * Baut eine Nachricht. Die Zeit ergibt sich aus secondsAfterStart, damit die Tests die
     * Reihenfolge und auch einen Gleichstand genau festlegen koennen.
     */
    private Message newMessage(String id, UUID roomId, int secondsAfterStart) {
        Instant start = Instant.parse("2026-09-04T08:00:00Z");
        Instant sentAt = start.plusSeconds(secondsAfterStart);
        return new Message(UUID.fromString(id), roomId, "lernende1", "Text " + id, sentAt);
    }

    /**
     * Publiziert die Nachricht auf den Fanout-Exchange, genau wie der MessageService.
     */
    private void publish(Message message) {
        rabbitTemplate.convertAndSend(RabbitConfiguration.EXCHANGE_NAME, "", message);
    }

    /**
     * Speichert die Nachricht in der Datenbank und publiziert sie danach.
     */
    private void publishAndSave(Message message) {
        save(message);
        publish(message);
    }

    /**
     * Schreibt die Nachricht in die Tabelle message und uebernimmt so die Arbeit des batch-writer.
     */
    private void save(Message message) {
        String sql = "INSERT INTO message (id, room_id, sender, text, sent_at) VALUES (?, ?, ?, ?, ?)";
        Timestamp sentAt = Timestamp.from(message.sentAt());
        jdbcTemplate.update(sql, message.id(), message.roomId(), message.sender(), message.text(), sentAt);
    }

    /**
     * Legt einen Raum an, weil die Tabelle message per Fremdschluessel auf room zeigt.
     */
    private void createRoom(UUID roomId) {
        String sql = "INSERT INTO room (id, name, created_by, created_at) VALUES (?, ?, ?, ?)";
        Timestamp createdAt = Timestamp.from(Instant.parse("2026-09-04T07:00:00Z"));
        jdbcTemplate.update(sql, roomId, "Testraum", "lehrperson", createdAt);
    }
}
