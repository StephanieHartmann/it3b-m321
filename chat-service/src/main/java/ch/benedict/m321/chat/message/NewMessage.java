package ch.benedict.m321.chat.message;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Was der Client beim Senden mitschickt - der JSON-Koerper von POST /api/messages.
 *
 * Bewusst NICHT dasselbe wie Message: id und sentAt vergibt der Server (in MessageService),
 * nicht der Client. Duerfte der Client sie selbst mitschicken, koennte er sich eine fremde
 * Sendezeit aussuchen oder - schlimmer - eine ID verwenden, die schon fuer eine andere Nachricht
 * vergeben ist.
 */
public record NewMessage(

        // In welchen Raum die Nachricht gehoert. @Schema beschreibt dieses Feld nur fuer die
        // Swagger-Dokumentation - es hat keine Auswirkung auf das Verhalten des Programms.
        @Schema(description = "In welchen Raum die Nachricht gehoert",
                example = "11111111-1111-1111-1111-111111111111")
        UUID roomId,

        // PLATZHALTER bis Keycloak angebunden ist (siehe PLANUNG.md, Abschnitt 4, Punkt 1 und
        // Gesamtplan Phase 7). Danach kommt der Absender aus dem Login-Token und dieses Feld
        // faellt ersatzlos weg - deshalb steht das ausdruecklich auch in der Beschreibung.
        @Schema(description = "PLATZHALTER bis Keycloak da ist. Danach kommt der Absender "
                            + "aus dem Token und dieses Feld faellt ersatzlos weg.",
                example = "lernende1")
        String sender,

        // Der eigentliche Nachrichtentext.
        @Schema(description = "Der Nachrichtentext", example = "Hallo zusammen")
        String text) {
}
