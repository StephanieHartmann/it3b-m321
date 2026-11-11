package ch.benedict.m321.chat.stream;

import java.io.IOException;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Oeffnet die Server-Sent-Events-Verbindungen fuer die Live-Anzeige eines Raums.
 * Anders als bei einem normalen REST-Aufruf endet die HTTP-Antwort hier nicht, sondern bleibt
 * offen, damit der Server spaeter jederzeit Nachrichten an den Client nachschieben kann.
 */
@RestController
@Tag(name = "Live-Stream", description = "Offene SSE-Verbindung fuer die Live-Anzeige eines Raums")
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    // 0 bedeutet bei SseEmitter "kein Zeitlimit". Ohne diese Angabe gilt das Standardlimit des
    // Webservers, und Tomcat wuerde die Verbindung nach 30 Sekunden schliessen.
    private static final long NO_TIMEOUT = 0L;

    private final SseConnectionRegistry connectionRegistry;
    private final MessageCatchUp messageCatchUp;

    // Beide kommen per Konstruktor-Injektion herein, wie der Service beim MessageController.
    public StreamController(SseConnectionRegistry connectionRegistry, MessageCatchUp messageCatchUp) {
        this.connectionRegistry = connectionRegistry;
        this.messageCatchUp = messageCatchUp;
    }

    /**
     * Oeffnet eine SSE-Verbindung fuer einen Raum und traegt sie in der Registry ein.
     * Schickt der Client den Header Last-Event-ID mit, holt sie zuerst die verpassten Nachrichten nach.
     * Danach gibt Spring den Tomcat-Thread wieder frei und haelt nur die Verbindung offen, deshalb
     * belegt ein wartender Client keinen Thread.
     */
    @Operation(
            summary = "Live-Stream eines Raums oeffnen",
            description = "Oeffnet eine SSE-Verbindung, die offen bleibt. "
                        + "Zuerst kommt ein SSE-Kommentar zur Bestaetigung der Verbindung, danach "
                        + "ein Ereignis pro Nachricht des Raums: id ist die Nachrichten-ID, "
                        + "data die Nachricht als JSON. Mit dem Header Last-Event-ID kommen zuerst "
                        + "alle Nachrichten nach dieser ID aus der Datenbank, danach geht es live "
                        + "weiter. Ist die Datenbank nicht erreichbar, endet die Antwort, damit "
                        + "der Client es erneut versucht.")
    @ApiResponse(responseCode = "200", description = "Verbindung ist offen")
    @ApiResponse(responseCode = "400", description = "roomId fehlt, oder roomId oder Last-Event-ID ist keine gueltige UUID")
    // "produces" setzt den Content-Type der Antwort auf text/event-stream.
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter openStream(
            @Parameter(description = "ID des Raums", required = true,
                       example = "11111111-1111-1111-1111-111111111111")
            @RequestParam UUID roomId,

            // Den Header setzt der Browser beim Wiederverbinden selbst auf die zuletzt erhaltene id.
            // required = false: Bei einer ersten Verbindung fehlt er, der Wert ist dann null.
            @Parameter(description = "ID der zuletzt erhaltenen Nachricht")
            @RequestHeader(name = "Last-Event-ID", required = false) UUID lastEventId) throws IOException {

        SseEmitter connection = new SseEmitter(NO_TIMEOUT);

        // onCompletion legt fest, was am Ende der Verbindung passieren soll. Laut Spring laeuft
        // dieser Code bei jedem Ende, also auch nach einem Fehler oder Timeout. Er laeuft spaeter
        // auf einem anderen Thread, lange nachdem diese Methode schon beendet ist.
        // Achtung: Trennt sich ein Client still, merkt der Server das nicht sofort, solange er
        // nichts an ihn schreibt. Der Eintrag bleibt in dieser Zeit in der Registry stehen.
        connection.onCompletion(() -> connectionRegistry.remove(roomId, connection));

        // Spring schickt die Kopfzeilen der Antwort erst mit dem ersten geschriebenen Inhalt ab.
        // Ohne diesen Schritt wuerde der Client bis zur ersten Nachricht auf die Antwort warten
        // und den Content-Type nie sehen. Ein SSE-Kommentar ist keine Nachricht: EventSource im
        // Browser ueberspringt ihn, er loest beim Client also kein Ereignis aus.
        SseEmitter.SseEventBuilder connectedComment = SseEmitter.event().comment("verbunden");
        connection.send(connectedComment);

        if (lastEventId == null) {
            connectionRegistry.add(roomId, connection);
        } else {
            catchUpOrClose(roomId, lastEventId, connection);
        }
        log.info("SSE-Verbindung geoeffnet: Raum {}", roomId);

        // Weil wir den SseEmitter zurueckgeben, laesst Spring die Verbindung nach dem Ende dieser
        // Methode offen, statt die Antwort wie sonst abzuschliessen.
        return connection;
    }

    /**
     * Holt die verpassten Nachrichten nach und beendet die Verbindung, wenn die Datenbank ausfaellt.
     * Ohne Nachholen weiterzumachen waere falsch, denn dem Client fehlten still Nachrichten.
     */
    private void catchUpOrClose(UUID roomId, UUID lastEventId, SseEmitter connection) {
        try {
            messageCatchUp.run(roomId, lastEventId, connection);
        } catch (DataAccessException databaseFailed) {
            log.warn("Nachholen fuer Raum {} gescheitert, Verbindung wird beendet: {}",
                    roomId, databaseFailed.getMessage());
            connectionRegistry.remove(roomId, connection);

            // Wir beenden die Antwort normal und nicht mit einem Fehlerstatus: Nach einem normalen
            // Ende verbindet sich EventSource selbst neu und schickt Last-Event-ID wieder mit.
            // Nach einem Status wie 500 wuerde es dagegen endgueltig aufgeben.
            connection.complete();
        }
    }

    /**
     * Behandelt den Fehler, den Spring nach einem gescheiterten Senden hierher zurueckmeldet.
     * Ein geschlossener Browser-Tab ist bei SSE normal und soll keinen ERROR im Log erzeugen.
     */
    // Ohne diese Methode reicht Spring die IOException an Tomcat weiter, und Tomcat schreibt pro
    // getrenntem Client Stacktraces ins Log. Eine Antwort schicken wir nicht: Der Client ist weg.
    @ExceptionHandler(IOException.class)
    public void handleClientDisconnected(IOException disconnected) {
        log.debug("SSE-Verbindung vom Client getrennt: {}", disconnected.getMessage());
    }
}
