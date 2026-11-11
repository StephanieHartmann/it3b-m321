package ch.benedict.m321.chat.stream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Ein einfacher SSE-Client fuer die Integrationstests, der ueber eine echte HTTP-Verbindung liest.
 * Er macht von Hand, was im Browser EventSource erledigt: Zeilen lesen und zu Ereignissen
 * zusammensetzen. So pruefen die Tests genau das, was wirklich uebers Netzwerk geht.
 */
class SseTestClient implements AutoCloseable {

    // So lange warten wir hoechstens auf ein Ereignis, bevor der Test scheitert.
    private static final int WAIT_SECONDS = 15;

    /**
     * Ein empfangenes SSE-Ereignis: die Zeile "id:" und die Zeile "data:".
     */
    record SseEvent(String id, String data) {
    }

    // Der Koerper der HTTP-Antwort. Ihn zu schliessen bricht die Verbindung ab.
    private final InputStream responseBody;

    // Der Lese-Thread legt Ereignisse hinein, der Test-Thread nimmt sie heraus. Eine BlockingQueue
    // ist dafuer gemacht: Sie ist thread-sicher, und poll kann warten, bis etwas ankommt.
    private final BlockingQueue<SseEvent> receivedEvents = new LinkedBlockingQueue<>();

    /**
     * Startet das Lesen der Antwort auf einem eigenen Thread, weil das Lesen blockiert, solange
     * nichts ankommt. Als Daemon-Thread haelt er die JVM am Ende nicht am Leben.
     */
    private SseTestClient(InputStream responseBody) {
        this.responseBody = responseBody;

        Thread readerThread = new Thread(() -> readEvents());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Verbindet sich erstmals mit einem Raum, also ohne Header Last-Event-ID.
     */
    static SseTestClient connect(int port, UUID roomId) throws IOException, InterruptedException {
        HttpRequest.Builder request = newRequest(port, roomId);
        return send(request);
    }

    /**
     * Verbindet sich neu mit einem Raum und meldet wie der Browser die zuletzt erhaltene id.
     */
    static SseTestClient reconnect(int port, UUID roomId, UUID lastEventId) throws IOException, InterruptedException {
        HttpRequest.Builder request = newRequest(port, roomId);
        request.header("Last-Event-ID", lastEventId.toString());
        return send(request);
    }

    /**
     * Bereitet die HTTP-Anfrage an /stream vor.
     */
    private static HttpRequest.Builder newRequest(int port, UUID roomId) {
        URI address = URI.create("http://localhost:" + port + "/stream?roomId=" + roomId);
        HttpRequest.Builder request = HttpRequest.newBuilder(address);
        request.header("Accept", "text/event-stream");
        return request;
    }

    /**
     * Schickt die Anfrage ab und wartet auf die Kopfzeilen der Antwort.
     * Der Server schickt sie erst, nachdem er die Verbindung eingetragen hat. Danach kann also
     * keine Nachricht mehr an diesem Client vorbeigehen, und die Tests brauchen kein sleep.
     */
    private static SseTestClient send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpClient httpClient = HttpClient.newHttpClient();

        // ofInputStream gibt die Antwort zurueck, sobald die Kopfzeilen da sind. Den Koerper lesen
        // wir danach selbst. Das passt zu SSE, denn dieser Koerper endet ja nie von allein.
        HttpResponse<InputStream> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());

        return new SseTestClient(response.body());
    }

    /**
     * Liest Zeile fuer Zeile, bis die Verbindung endet, und setzt daraus Ereignisse zusammen.
     * Eine leere Zeile schliesst bei SSE ein Ereignis ab.
     */
    private void readEvents() {
        String id = null;
        String data = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                if (line.startsWith("id:")) {
                    id = line.substring("id:".length());
                } else if (line.startsWith("data:")) {
                    data = line.substring("data:".length());
                } else if (line.isEmpty() && id != null) {
                    // Der Kommentar ":verbunden" hat keine id und erzeugt darum kein Ereignis.
                    receivedEvents.add(new SseEvent(id, data));
                    id = null;
                    data = null;
                }
                line = reader.readLine();
            }
        } catch (IOException connectionClosed) {
            // Das ist hier der normale Weg: close() schliesst die Verbindung, waehrend wir lesen.
        }
    }

    /**
     * Liefert das naechste Ereignis und wartet noetigenfalls darauf.
     * Kommt nichts, scheitert der Test mit einer klaren Meldung, statt endlos zu haengen.
     */
    SseEvent nextEvent() throws InterruptedException {
        SseEvent event = receivedEvents.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(event, "Innerhalb von " + WAIT_SECONDS + " Sekunden kam kein SSE-Ereignis an");
        return event;
    }

    /**
     * Bricht die Verbindung ab, wie wenn ein Browser-Tab geschlossen wird oder das Netz ausfaellt.
     */
    // Wir schliessen den Antwort-Koerper von diesem Thread aus, waehrend der Lese-Thread darin
    // wartet. Dieser InputStream ist dafuer gebaut: Das Lesen endet dann sofort.
    @Override
    public void close() throws IOException {
        responseBody.close();
    }
}
