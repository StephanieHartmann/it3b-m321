package ch.benedict.m321.chat.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testet den Controller allein, ohne echte Datenbank und ohne echten RabbitMQ-Broker.
 *
 * @WebMvcTest(MessageController.class) startet NUR die Webschicht von Spring (alles, was mit
 * HTTP zu tun hat) - nicht die ganze Anwendung. Deshalb braucht dieser Test weder eine laufende
 * Datenbank noch einen laufenden Broker und ist entsprechend schnell.
 *
 * Den MessageService, den der Controller normalerweise braucht, ersetzen wir durch eine
 * "Attrappe" (englisch: Mock). Mit @MockitoBean sagen wir Spring: "Baue hier keinen echten
 * MessageService, sondern ein Fake-Objekt, das ich im Test selbst steuere."
 */
@WebMvcTest(MessageController.class)
class MessageControllerTest {

    // Die feste Demo-Raum-ID aus db/02-demo-data.sql. Wir benutzen dieselbe ID wie in den
    // echten Demo-Daten, damit die Beispiele im Test zu einem spaeteren manuellen Test passen.
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // MockMvc simuliert echte HTTP-Anfragen (GET, POST, ...) gegen unseren Controller, ohne dass
    // wirklich ein Netzwerk-Port geoeffnet wird. @Autowired laesst sich Spring dieses Objekt
    // "einspritzen" - wir bauen es nicht selbst mit "new".
    @Autowired
    private MockMvc mockMvc;

    // Die Attrappe fuer den MessageService. Ueber "when(...).thenReturn(...)" legen wir weiter
    // unten fest, was diese Attrappe zurueckgeben soll, wenn der Controller sie aufruft.
    @MockitoBean
    private MessageService messageService;

    @Test
    void historyReturnsMessagesAsJson() throws Exception {
        // Wir bauen von Hand eine einzelne Beispiel-Nachricht. Instant.parse(...) erzeugt einen
        // festen Zeitpunkt aus einem Text - so liefert der Test immer dasselbe Ergebnis,
        // unabhaengig davon, wann genau er ausgefuehrt wird.
        Message example = new Message(
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"),
                ROOM_ID,
                "lehrperson",
                "Willkommen im Raum Allgemein.",
                Instant.parse("2026-09-04T08:00:00Z"));

        // Wir sagen der Attrappe: "Egal, mit welchem roomId und welchem limit du aufgerufen
        // wirst (any() / anyInt() heisst 'irgendein Wert') - gib immer diese eine
        // Beispiel-Nachricht als Liste zurueck." So testen wir nur den Controller, nicht die
        // echte Datenbank-Logik dahinter.
        when(messageService.loadHistory(any(), anyInt())).thenReturn(List.of(example));

        // mockMvc.perform(...) simuliert eine echte HTTP-Anfrage: GET /api/messages?roomId=...
        // .andExpect(...) prueft danach Stueck fuer Stueck, ob die Antwort so aussieht, wie wir
        // es erwarten.
        mockMvc.perform(get("/api/messages").param("roomId", ROOM_ID.toString()))
                .andExpect(status().isOk())                                    // HTTP 200 erwartet
                .andExpect(jsonPath("$[0].sender").value("lehrperson"))        // erstes Element im JSON-Array
                .andExpect(jsonPath("$[0].text").value("Willkommen im Raum Allgemein."));
    }

    @Test
    void sendAcceptsMessageAndReturns202() throws Exception {
        // Das ist die Nachricht, die unsere Attrappe zurueckgeben soll, SOBALD der Controller
        // messageService.sendMessage(...) aufruft - id und sentAt so, als haette der Service sie
        // bereits vergeben (das ist in Wirklichkeit die Aufgabe des Service, nicht des Tests).
        Message created = new Message(
                UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001"),
                ROOM_ID,
                "lernende1",
                "Hallo zusammen",
                Instant.parse("2026-09-04T08:05:00Z"));
        when(messageService.sendMessage(any())).thenReturn(created);

        // Das ist der JSON-Koerper, den ein Client beim Senden schicken wuerde. Ein
        // Text-Block ("""..."""), praktisch fuer mehrzeiligen Text wie JSON im Testcode.
        String body = """
                {
                  "roomId": "11111111-1111-1111-1111-111111111111",
                  "sender": "lernende1",
                  "text": "Hallo zusammen"
                }
                """;

        // 202 Accepted heisst: angenommen und weitergegeben - aber noch nicht gespeichert.
        // Genau das ist bei uns der Fall, denn schreiben wird spaeter der batch-service.
        mockMvc.perform(post("/api/messages")
                        .contentType(MediaType.APPLICATION_JSON)   // sagt dem Server: "der Body ist JSON"
                        .content(body))
                .andExpect(status().isAccepted())                          // HTTP 202 erwartet
                .andExpect(jsonPath("$.id").value("bbbbbbbb-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.text").value("Hallo zusammen"));
    }

    @Test
    void sendRejectsBlankText() throws Exception {
        // Der Text besteht nur aus Leerzeichen - das ist inhaltlich leer und muss abgelehnt
        // werden, noch bevor irgendetwas an RabbitMQ geschickt wird.
        String body = """
                {
                  "roomId": "11111111-1111-1111-1111-111111111111",
                  "sender": "lernende1",
                  "text": "   "
                }
                """;

        mockMvc.perform(post("/api/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());   // HTTP 400 erwartet
    }
}
