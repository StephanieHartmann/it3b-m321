package ch.benedict.m321.batch;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Chat-Nachricht, so wie sie als JSON aus der Queue "chat.persist" kommt und so wie sie
 * danach in die Tabelle message geschrieben wird.
 *
 * Die Feldnamen sind absichtlich genau dieselben wie im record Message des chat-service:
 * Jackson ordnet die JSON-Felder ueber den Namen zu ("roomId" im JSON landet in roomId hier).
 * Ein anders geschriebener Name bliebe beim Einlesen einfach leer (null).
 *
 * Wie beim chat-service ein record: ein unveraenderlicher Datenbehaelter, fuer den Java
 * Konstruktor, Lesemethoden (id(), roomId(), ...), equals(), hashCode() und toString()
 * selbst erzeugt.
 */
public record Message(

        // Eindeutige ID, vergeben vom chat-service. Sie ist der Primaerschluessel in der
        // Datenbank - daran erkennt der INSERT eine Nachricht, die schon gespeichert ist.
        UUID id,

        // In welchem Raum die Nachricht steht.
        UUID roomId,

        // Benutzername der Person, die die Nachricht geschrieben hat.
        String sender,

        // Der eigentliche Nachrichtentext.
        String text,

        // Zeitpunkt des SENDENS, gesetzt vom chat-service - nicht der Zeitpunkt des Speicherns.
        Instant sentAt) {
}
