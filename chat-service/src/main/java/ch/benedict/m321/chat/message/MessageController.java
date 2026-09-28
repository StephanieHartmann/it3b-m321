package ch.benedict.m321.chat.message;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Die REST-Schnittstelle fuer Nachrichten - der einzige Teil dieser drei Klassen
 * (Controller/Service/Repository), der ueber HTTP von aussen erreichbar ist.
 *
 * Die Annotationen aus io.swagger.v3 (@Operation, @Parameter, @ApiResponse, @Tag) beschreiben
 * jeden Endpunkt fuer Menschen - daraus baut springdoc automatisch die Swagger-Oberflaeche, die
 * wir aus Task 1 schon kennen. Was hier nicht beschrieben ist, taucht in der Dokumentation auch
 * nicht auf.
 */
@RestController                                     // Controller, dessen Rueckgabewerte automatisch
                                                      // als JSON in die HTTP-Antwort geschrieben werden
                                                      // (statt z.B. als HTML-Seite).
@RequestMapping("/api/messages")                     // Alle Endpunkte dieser Klasse beginnen mit
                                                      // diesem Pfad-Praefix.
@Tag(name = "Nachrichten", description = "Nachrichten senden und den Verlauf eines Raums lesen")
public class MessageController {

    private static final Logger log = LoggerFactory.getLogger(MessageController.class);

    // Obergrenze fuer den Query-Parameter "limit". Ohne diese Grenze koennte jemand z.B.
    // limit=5000000 anfragen und damit die Datenbank unnoetig stark belasten.
    private static final int MAX_LIMIT = 100;

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Liefert die letzten Nachrichten eines Raums, neueste zuerst.
     * Gelesen wird direkt aus der Datenbank - dieser Weg laeuft komplett getrennt vom Senden
     * ueber RabbitMQ, das erst in Task 4 dazukommt.
     */
    @Operation(
            summary = "Verlauf eines Raums lesen",
            description = "Gibt die letzten Nachrichten eines Raums zurueck, neueste zuerst. "
                        + "Demo-Raum zum Ausprobieren: 11111111-1111-1111-1111-111111111111")
    @ApiResponse(responseCode = "200", description = "Verlauf, moeglicherweise leer")
    @ApiResponse(responseCode = "400", description = "limit ist kleiner als 1 oder groesser als 100")
    @GetMapping   // Reagiert auf HTTP GET /api/messages
    public List<Message> history(

            // @RequestParam liest den Wert aus der URL, z.B. ?roomId=1111...
            // required = true heisst: fehlt dieser Parameter, antwortet Spring automatisch mit
            // HTTP 400, noch bevor unsere Methode ueberhaupt aufgerufen wird.
            @Parameter(description = "ID des Raums", required = true,
                       example = "11111111-1111-1111-1111-111111111111")
            @RequestParam UUID roomId,

            // defaultValue heisst: fehlt "limit" in der URL, wird automatisch 50 verwendet.
            @Parameter(description = "Wie viele Nachrichten hoechstens (1 bis 100)", example = "50")
            @RequestParam(defaultValue = "50") int limit) {

        log.info("Verlauf abgerufen: Raum {}, limit {}", roomId, limit);

        // Eingabe pruefen, BEVOR sie an die Datenbank-Abfrage weitergegeben wird. Einfache
        // Grenzwertpruefung: liegt limit ausserhalb von 1..100, brechen wir sofort ab.
        if (limit < 1 || limit > MAX_LIMIT) {
            log.warn("Ungueltiges limit {} fuer Raum {} - Anfrage abgelehnt", limit, roomId);

            // ResponseStatusException ist der einfachste Weg in Spring, eine HTTP-Fehlerantwort
            // mit eigenem Statuscode und eigener Fehlermeldung zu erzeugen. Spring faengt diese
            // Exception automatisch ab und baut daraus die passende HTTP-Antwort (hier: 400).
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }

        // Die eigentliche Arbeit macht der Service - der Controller selbst enthaelt keine
        // Fachlogik, nur HTTP-Kram (Parameter lesen, Statuscodes, Validierung der Eingabeform).
        List<Message> history = messageService.loadHistory(roomId, limit);

        log.info("Verlauf geliefert: Raum {}, {} Nachrichten", roomId, history.size());

        // Der Rueckgabewert (eine List<Message>) wird von Spring automatisch in JSON
        // umgewandelt, weil die Klasse mit @RestController markiert ist.
        return history;
    }
}
