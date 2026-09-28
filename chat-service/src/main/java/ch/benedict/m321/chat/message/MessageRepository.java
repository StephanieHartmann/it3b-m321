package ch.benedict.m321.chat.message;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Liest Nachrichten aus der Datenbank.
 *
 * Wir benutzen bewusst KEIN ORM (also kein Hibernate/JPA), sondern JdbcTemplate: Das SQL steht
 * im Klartext im Code und wir uebertragen jede Spalte von Hand in ein Java-Feld. Der Vorteil im
 * Unterricht: man kann jede Zeile lesen und genau sagen, was sie tut - bei einem ORM waere
 * vieles "Magie" im Hintergrund, die man nicht sofort sieht.
 *
 * Diese Klasse kann NUR lesen. Schreiben gibt es hier absichtlich nicht: in die
 * Nachrichtentabelle schreibt ausschliesslich der batch-service (siehe PLANUNG.md, Abschnitt
 * 2.3) - der chat-service publiziert neue Nachrichten nur auf RabbitMQ (das kommt in Task 4).
 */
@Repository   // Markiert diese Klasse als "Datenbank-Zugriffs-Baustein". Spring erkennt diese
              // Annotation beim Start (wegen @SpringBootApplication aus Task 1) und baut die
              // Klasse automatisch - wir rufen "new MessageRepository(...)" nirgends selbst auf.
public class MessageRepository {

    // Der Logger schreibt Meldungen in die Konsole. static final, weil die ganze Klasse sich
    // EINEN Logger teilt - es braucht nicht pro Objekt einen eigenen.
    private static final Logger log = LoggerFactory.getLogger(MessageRepository.class);

    // JdbcTemplate ist das Werkzeug von Spring, mit dem wir SQL-Abfragen ausfuehren, ohne uns
    // selbst um das Oeffnen/Schliessen von Datenbank-Verbindungen und um Fehlerbehandlung
    // kuemmern zu muessen. Spring baut dieses Objekt automatisch, weil wir in application.yml
    // die Datenbank-Verbindung eingetragen haben.
    private final JdbcTemplate jdbcTemplate;

    /**
     * Spring erzeugt beim Start automatisch einen JdbcTemplate und "reicht" ihn hier ueber den
     * Konstruktor herein (Konstruktor-Injektion). Wir bauen ihn NICHT selbst mit "new" - so
     * laesst er sich in einem Test leicht durch eine Attrappe ersetzen, ohne diese Klasse
     * aendern zu muessen.
     */
    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Holt die letzten "limit" Nachrichten eines Raums, neueste zuerst.
     * Der Index idx_message_room_time aus 01-schema.sql passt genau auf diese Abfrage
     * (Filter auf room_id, Sortierung nach sent_at).
     */
    public List<Message> findLatest(UUID roomId, int limit) {
        // Das SQL-Statement als Text. Die beiden Fragezeichen "?" sind Platzhalter - sie werden
        // NICHT durch simples Zusammenkleben von Text gefuellt, sondern weiter unten sicher vom
        // Datenbank-Treiber eingesetzt.
        String sql = "SELECT id, room_id, sender, text, sent_at "
                   + "FROM message "
                   + "WHERE room_id = ? "        // erster Platzhalter -> wird mit roomId gefuellt
                   + "ORDER BY sent_at DESC "     // neueste Nachricht zuerst
                   + "LIMIT ?";                   // zweiter Platzhalter -> wird mit limit gefuellt

        log.debug("Lese die letzten {} Nachrichten aus Raum {}", limit, roomId);

        // jdbcTemplate.query(...) macht drei Dinge auf einmal:
        // 1. Es oeffnet eine Verbindung zur Datenbank und fuehrt das SQL aus.
        // 2. Es setzt roomId und limit sicher an die Stelle der beiden "?" ein (als sogenanntes
        //    PreparedStatement). WICHTIG: Werte NIEMALS direkt in den SQL-Text hineinkleben
        //    (also niemals "WHERE room_id = '" + roomId + "'" schreiben) - das waere eine
        //    offene Tuer fuer SQL-Injection, also fuer manipulierte Eingaben, die die
        //    Datenbank-Abfrage absichtlich veraendern koennten.
        // 3. Fuer jede gefundene Ergebniszeile ruft es unsere Methode mapRow(...) auf (siehe
        //    "this::mapRow" unten - das ist eine Methodenreferenz, quasi ein Zeiger auf die
        //    Methode) und sammelt alle Ergebnisse in einer Liste.
        List<Message> found = jdbcTemplate.query(sql, this::mapRow, roomId, limit);

        log.debug("{} Nachrichten aus Raum {} gelesen", found.size(), roomId);
        return found;
    }

    /**
     * Wandelt EINE Zeile aus dem Datenbank-Ergebnis (ResultSet) in ein Message-Objekt um.
     * Diese Methode wird automatisch einmal pro gefundener Zeile aufgerufen.
     */
    private Message mapRow(ResultSet row, int rowNumber) throws SQLException {
        // row.getObject(spaltenname, Zieltyp) liest eine Spalte aus und wandelt sie direkt in
        // den gewuenschten Java-Typ um - hier UUID, weil die Spalten id/room_id in der
        // Datenbank vom Typ UUID sind.
        UUID id = row.getObject("id", UUID.class);
        UUID roomId = row.getObject("room_id", UUID.class);

        // Einfache Text-Spalten liest man mit getString(...).
        String sender = row.getString("sender");
        String text = row.getString("text");

        // Zeitstempel kommen aus der Datenbank zunaechst als java.sql.Timestamp, nicht direkt
        // als java.time.Instant. Deshalb lesen wir erst einen Timestamp und wandeln ihn dann um
        // (.toInstant()) - Instant ist der moderne, in Java empfohlene Zeittyp.
        Timestamp timestamp = row.getTimestamp("sent_at");
        Instant sentAt = timestamp.toInstant();

        // Aus den gelesenen Werten bauen wir ein neues, unveraenderliches Message-Objekt.
        return new Message(id, roomId, sender, text, sentAt);
    }
}
