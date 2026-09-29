package ch.benedict.m321.batch;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schreibt Nachrichten in die Tabelle message. Wie im chat-service ohne ORM, mit JdbcTemplate:
 * das SQL steht im Klartext da und jede Spalte wird von Hand befuellt.
 */
@Repository   // Spring baut diese Klasse beim Start selbst und reicht sie dort herein, wo sie
              // gebraucht wird (hier: im MessageConsumer).
public class MessageRepository {

    // Das SQL fuer genau eine Nachricht. Die fuenf "?" sind Platzhalter, die der
    // Datenbank-Treiber sicher befuellt - nie Werte direkt in den Text kleben (SQL-Injection).
    //
    // ON CONFLICT (id) DO NOTHING: gibt es schon eine Zeile mit dieser id, passiert einfach
    // nichts - kein Fehler, keine zweite Zeile. Das ist wichtig, weil RabbitMQ eine Nachricht
    // auch zweimal zustellen kann (z.B. wenn das ACK unterwegs verloren geht, Szenario S5/S6).
    private static final String INSERT_SQL =
            "INSERT INTO message (id, room_id, sender, text, sent_at) "
          + "VALUES (?, ?, ?, ?, ?) "
          + "ON CONFLICT (id) DO NOTHING";

    // Werkzeug von Spring fuer SQL. Kuemmert sich um Verbindung oeffnen/schliessen.
    private final JdbcTemplate jdbcTemplate;

    /**
     * Spring reicht den JdbcTemplate ueber den Konstruktor herein (Konstruktor-Injektion).
     * Er ist fertig eingerichtet mit der Datenbank aus application.yml.
     */
    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Schreibt genau eine Nachricht in die Datenbank. Gibt die Anzahl eingefuegter Zeilen
     * zurueck: 1 fuer eine neue Nachricht, 0 wenn sie schon gespeichert war.
     * Scheitert der INSERT, wirft JdbcTemplate eine Exception - die geben wir bewusst weiter.
     */
    public int insertOne(Message message) {
        // Der PostgreSQL-Treiber kann ein Instant nicht direkt in eine TIMESTAMPTZ-Spalte
        // schreiben, ein OffsetDateTime aber schon. atOffset(UTC) haengt an den Zeitpunkt die
        // Angabe "+00:00" an - der Zeitpunkt selbst bleibt exakt derselbe. So spielt es keine
        // Rolle, in welcher Zeitzone der Rechner laeuft, auf dem der batch-writer startet.
        OffsetDateTime sentAtUtc = message.sentAt().atOffset(ZoneOffset.UTC);

        // update(...) fuehrt das SQL aus und setzt die Werte der Reihe nach in die "?" ein.
        int insertedRows = jdbcTemplate.update(
                INSERT_SQL,
                message.id(),
                message.roomId(),
                message.sender(),
                message.text(),
                sentAtUtc);
        return insertedRows;
    }

    /**
     * Schreibt ein ganzes Paket Nachrichten mit einem einzigen batchUpdate. Das ist der Kern
     * des Buendelns: statt 500 Rundreisen zur Datenbank gibt es nur wenige.
     * Scheitert es, wirft JdbcTemplate eine Exception - die geben wir bewusst weiter.
     */
    // @Transactional: Spring oeffnet vor dieser Methode EINE Datenbank-Transaktion und
    // schliesst sie danach mit einem Commit ab. Wirft die Methode eine Exception, macht Spring
    // stattdessen ein Rollback: dann steht keine einzige Zeile des Pakets in der Tabelle.
    // Das ist wichtig, weil der Treiber das Paket in mehrere INSERTs aufteilt (bis 128 Zeilen
    // pro INSERT) - ohne Transaktion koennte sonst eine Haelfte gespeichert sein und die
    // andere nicht. Entweder das ganze Paket oder nichts.
    @Transactional
    public void insertBatch(List<Message> messages) {
        // batchUpdate erwartet pro Zeile ein Array mit den Werten fuer die fuenf "?" - in
        // derselben Reihenfolge wie im SQL. Diese Arrays sammeln wir in einer Liste.
        List<Object[]> rowValues = new ArrayList<>();
        for (Message message : messages) {
            // Gleiche Umwandlung wie in insertOne: Instant -> OffsetDateTime fuer TIMESTAMPTZ.
            OffsetDateTime sentAtUtc = message.sentAt().atOffset(ZoneOffset.UTC);

            Object[] valuesForOneRow = new Object[] {
                    message.id(),
                    message.roomId(),
                    message.sender(),
                    message.text(),
                    sentAtUtc
            };
            rowValues.add(valuesForOneRow);
        }

        // Ein Aufruf fuer das ganze Paket. Zusammen mit reWriteBatchedInserts=true (siehe
        // application.yml) baut der Treiber daraus grosse INSERTs mit vielen VALUES-Zeilen.
        jdbcTemplate.batchUpdate(INSERT_SQL, rowValues);
    }
}
