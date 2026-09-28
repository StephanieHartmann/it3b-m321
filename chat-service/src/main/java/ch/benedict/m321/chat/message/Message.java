package ch.benedict.m321.chat.message;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine einzelne Chat-Nachricht - so wie sie in der Datenbank steht und so wie sie ueber die
 * REST-API nach aussen gegeben wird (automatisch als JSON, das erledigt Spring fuer uns).
 *
 * Wir benutzen hier ein "record" statt einer normalen Klasse. Ein record ist eine Kurzform
 * fuer eine Klasse, die nur Daten transportiert und sonst nichts tut. Java erzeugt automatisch:
 * - einen Konstruktor mit allen Feldern in der angegebenen Reihenfolge,
 * - Lesemethoden (hier: id(), roomId(), sender(), text(), sentAt()) statt klassischer Getter,
 * - equals(), hashCode() und toString().
 * Wir muessen das alles nicht selbst von Hand schreiben.
 *
 * Ausserdem sind die Felder eines records IMMER unveraenderlich (final): einmal erzeugt, kann
 * sich eine Message nicht mehr aendern. Das passt gut zu einer Nachricht, die ja auch in der
 * Realitaet nicht nachtraeglich veraendert wird.
 */
public record Message(

        // Eindeutige ID der Nachricht. Ab Task 4 vergibt der chat-service diese ID selbst,
        // nicht die Datenbank - der genaue Grund dafuer steht in PLANUNG.md, Abschnitt 2.3.
        UUID id,

        // In welchem Raum die Nachricht steht. Zeigt auf die id-Spalte der Tabelle room.
        UUID roomId,

        // Benutzername der Person, die die Nachricht geschrieben hat.
        String sender,

        // Der eigentliche Nachrichtentext.
        String text,

        // Zeitpunkt, an dem die Nachricht GESENDET wurde - nicht der Zeitpunkt, an dem sie
        // gespeichert wurde. Diese beiden koennen spaeter (nach dem Buendeln) auseinanderliegen.
        Instant sentAt) {
}
