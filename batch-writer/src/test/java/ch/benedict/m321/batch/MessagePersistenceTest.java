package ch.benedict.m321.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Prueft den ganzen Weg Queue -> batch-writer -> Datenbank mit echtem Broker und echter
 * Datenbank (Testcontainers): doppelte Zustellung (S5) und kurzen Datenbankausfall (S7).
 */
// connection-timeout=1000: wie lange (in Millisekunden) der Verbindungspool auf eine
// Datenbank-Verbindung wartet, bevor er aufgibt. Standard waeren 30 Sekunden. Im Ausfall-Test
// soll der batch-writer schnell merken, dass die Datenbank nicht da ist - sonst wuerde er
// einfach warten, bis sie zurueck ist, und der Fehlerweg wuerde nie durchlaufen.
@SpringBootTest(properties = "spring.datasource.hikari.connection-timeout=1000")
@Import(TestcontainersConfiguration.class)
// Faengt alles ab, was waehrend eines Tests auf die Konsole geschrieben wird - auch aus dem
// Listener-Thread. So kann der Ausfall-Test im Log nachsehen, ob der Fehlerweg lief.
@ExtendWith(OutputCaptureExtension.class)
class MessagePersistenceTest {

    // Der Raum aus db/02-demo-data.sql. Eine Nachricht muss in einem existierenden Raum
    // stehen, sonst verletzt sie den Fremdschluessel und landet (zu Recht) in chat.dlq.
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // Die Warnung, die MessageConsumer schreibt, wenn die Datenbank nicht erreichbar ist.
    private static final String DATABASE_UNREACHABLE_LOG = "Datenbank nicht erreichbar";

    // Zum Senden in chat.persist - mit demselben JSON-Umwandler wie im echten Betrieb. Er
    // setzt nur content_type application/json und keinen Java-Typ-Header, genau wie in S5.
    @Autowired
    private RabbitTemplate rabbitTemplate;

    // Zum Leeren und Zaehlen der Queues.
    @Autowired
    private AmqpAdmin amqpAdmin;

    // Der Test-Datenbank-Container, fuer Adresse, Benutzer und Passwort.
    @Autowired
    private PostgreSQLContainer<?> postgresContainer;

    // Eine EIGENE Verbindung des Tests zur Datenbank, unabhaengig vom batch-writer. Ueber sie
    // zaehlen wir Zeilen und sperren im Ausfall-Test die Anmeldung. Sie bleibt dabei offen,
    // weil eine Sperre nur neue Anmeldungen verhindert.
    private Connection adminConnection;

    /**
     * Oeffnet vor jedem Test die eigene Datenbank-Verbindung und leert chat.dlq, damit keine
     * Reste aus einem anderen Test das Ergebnis verfaelschen.
     */
    @BeforeEach
    void setUp() throws SQLException {
        String url = postgresContainer.getJdbcUrl();
        String username = postgresContainer.getUsername();
        String password = postgresContainer.getPassword();
        adminConnection = DriverManager.getConnection(url, username, password);

        amqpAdmin.purgeQueue(RabbitConfiguration.DEAD_LETTER_QUEUE_NAME, false);
    }

    /**
     * Schliesst nach jedem Test die eigene Datenbank-Verbindung wieder.
     */
    @AfterEach
    void tearDown() throws SQLException {
        adminConnection.close();
    }

    /**
     * S5: Dieselbe Nachricht kommt zweimal in chat.persist an. Es darf genau eine Zeile
     * entstehen, und die zweite Zustellung ist kein Fehler - also nichts in chat.dlq.
     */
    @Test
    void duplicateMessageIsStoredExactlyOnce() throws Exception {
        Message message = newMessage("zweimal gesendet");
        sendToPersistQueue(message);
        sendToPersistQueue(message);

        // Wann ist die ZWEITE Zustellung verarbeitet? Sie aendert in der Tabelle ja nichts.
        // Darum schicken wir danach eine dritte Nachricht als "Schlusslicht". Es gibt nur
        // einen Listener-Thread, der die Queue der Reihe nach abarbeitet: steht das
        // Schlusslicht in der Tabelle, sind die beiden davor sicher auch verarbeitet.
        Message lastMessage = newMessage("Schlusslicht");
        sendToPersistQueue(lastMessage);
        boolean lastMessageStored = waitUntilRowCount(lastMessage.id(), 1, 20);
        assertTrue(lastMessageStored, "Das Schlusslicht wurde nicht innert 20 s gespeichert");

        assertEquals(1, countRows(message.id()), "Die doppelte Nachricht steht nicht genau einmal da");
        assertEquals(0, countDeadLetters(), "Ein Duplikat darf nicht in chat.dlq landen");
    }

    /**
     * S7: Waehrend die Datenbank keine Verbindungen annimmt, kommt eine Nachricht an. Sie darf
     * weder verloren gehen noch in chat.dlq landen, und muss gespeichert werden, sobald die
     * Datenbank wieder erreichbar ist - ohne Neustart des batch-writer.
     */
    @Test
    void messageSurvivesShortDatabaseOutage(CapturedOutput output) throws Exception {
        Message message = newMessage("waehrend Ausfall gesendet");

        blockDatabaseLogin();
        try {
            sendToPersistQueue(message);

            // Erst weitermachen, wenn der batch-writer den Ausfall wirklich bemerkt hat. Ohne
            // diese Pruefung koennte der Test auch bestehen, wenn der Fehlerweg nie lief.
            boolean outageNoticed = waitForOutput(output, DATABASE_UNREACHABLE_LOG, 20);
            assertTrue(outageNoticed, "Der batch-writer hat den Datenbankausfall nicht gemeldet");
            assertEquals(0, countRows(message.id()), "Waehrend des Ausfalls darf nichts gespeichert sein");
        } finally {
            // Im finally, damit die Datenbank auch dann wieder offen ist, wenn eine Pruefung
            // oben fehlschlaegt - sonst wuerden alle folgenden Tests mit scheitern.
            allowDatabaseLogin();
        }

        // Die Nachricht lag die ganze Zeit unbestaetigt in chat.persist und wird jetzt erneut
        // zugestellt. Kein Neustart noetig.
        boolean stored = waitUntilRowCount(message.id(), 1, 30);
        assertTrue(stored, "Die Nachricht wurde nach dem Ausfall nicht innert 30 s gespeichert");
        assertEquals(0, countDeadLetters(), "Ein Datenbankausfall darf nichts in chat.dlq schicken");
    }

    // ---------------------------------------------------------------------------------
    // Hilfsmethoden
    // ---------------------------------------------------------------------------------

    /**
     * Baut eine gueltige Testnachricht mit neuer id im Demo-Raum.
     */
    private Message newMessage(String text) {
        UUID id = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new Message(id, ROOM_ID, "testlauf", text, sentAt);
    }

    /**
     * Legt eine Nachricht direkt in chat.persist - ueber den Standard-Exchange "", der an die
     * Queue mit dem Namen des Routing-Keys liefert.
     */
    private void sendToPersistQueue(Message message) {
        rabbitTemplate.convertAndSend("", RabbitConfiguration.PERSIST_QUEUE_NAME, message);
    }

    /**
     * Zaehlt, wie viele Zeilen mit dieser id in der Tabelle message stehen.
     */
    private int countRows(UUID id) throws SQLException {
        String sql = "SELECT count(*) FROM message WHERE id = ?";
        try (PreparedStatement statement = adminConnection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /**
     * Zaehlt die Nachrichten, die gerade in chat.dlq warten.
     */
    private int countDeadLetters() {
        QueueInformation info = amqpAdmin.getQueueInfo(RabbitConfiguration.DEAD_LETTER_QUEUE_NAME);
        return info.getMessageCount();
    }

    /**
     * Fragt alle 200 ms nach, bis die Zeilenzahl stimmt oder die Zeit abgelaufen ist.
     * Noetig, weil der batch-writer in einem anderen Thread arbeitet: wir wissen nicht genau,
     * wann er fertig ist, nur dass er es irgendwann sein muss.
     */
    private boolean waitUntilRowCount(UUID id, int expectedCount, int maxSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            int currentCount = countRows(id);
            if (currentCount == expectedCount) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    /**
     * Fragt alle 200 ms nach, bis der gesuchte Text in der Konsolenausgabe steht oder die Zeit
     * abgelaufen ist.
     */
    private boolean waitForOutput(CapturedOutput output, String expectedText, int maxSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String currentOutput = output.getOut();
            if (currentOutput.contains(expectedText)) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    /**
     * Simuliert den Datenbankausfall: die Datenbank laeuft weiter, nimmt aber vom Benutzer
     * des batch-writer keine neuen Verbindungen mehr an, und seine offenen werden getrennt.
     * Fuer den batch-writer sieht das aus wie eine Datenbank, die nicht erreichbar ist.
     */
    // Warum nicht einfach den Container stoppen? Beim Neustart vergibt Docker einen neuen
    // zufaelligen Port - der batch-writer kennt aber noch den alten und kaeme nie mehr hin.
    // So hingegen bestimmt der Test auf die Millisekunde, wann der Ausfall beginnt und endet.
    private void blockDatabaseLogin() throws SQLException {
        String username = postgresContainer.getUsername();
        try (Statement statement = adminConnection.createStatement()) {
            // NOLOGIN: dieser Benutzer darf sich nicht mehr neu anmelden.
            statement.execute("ALTER ROLE " + username + " NOLOGIN");
        }

        // Bestehende Verbindungen des batch-writer beenden - ausser unserer eigenen
        // (pg_backend_pid() ist die Nummer der Verbindung, ueber die wir gerade sprechen).
        String sql = "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                   + "WHERE usename = ? AND pid <> pg_backend_pid()";
        try (PreparedStatement statement = adminConnection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.execute();
        }
    }

    /**
     * Beendet den simulierten Ausfall: der Benutzer darf sich wieder anmelden.
     */
    private void allowDatabaseLogin() throws SQLException {
        String username = postgresContainer.getUsername();
        try (Statement statement = adminConnection.createStatement()) {
            statement.execute("ALTER ROLE " + username + " LOGIN");
        }
    }
}
