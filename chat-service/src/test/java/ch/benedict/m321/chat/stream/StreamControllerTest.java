package ch.benedict.m321.chat.stream;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.message.Message;
import ch.benedict.m321.chat.message.MessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testet den SSE-Endpunkt ohne Netzwerk, zusammen mit den echten Stream-Klassen.
 * @WebMvcTest laedt nur Controller, deshalb holen wir die uebrigen mit @Import dazu. Der
 * MessageService ist eine Attrappe, damit der Test weder Datenbank noch Broker braucht.
 *
 * Die Registry bleibt ueber alle Tests dieser Klasse dieselbe. Darum benutzt jeder Test seinen
 * eigenen Raum, sonst wuerden sich die Zaehlungen gegenseitig stoeren.
 */
@WebMvcTest(StreamController.class)
@Import({SseConnectionRegistry.class, MessageEventSender.class, MessageCatchUp.class})
class StreamControllerTest {

    private static final UUID LAST_EVENT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SseConnectionRegistry connectionRegistry;

    @MockitoBean
    private MessageService messageService;

    /**
     * Prueft die Zusagen des Endpunkts ohne Header: richtiger Content-Type, Antwort bleibt offen,
     * Verbindung steht in der Registry und die Datenbank wird gar nicht gefragt.
     */
    @Test
    void openStreamKeepsConnectionOpenAndRegistersIt() throws Exception {
        UUID roomId = UUID.randomUUID();

        // asyncStarted heisst: Die Methode ist fertig, die HTTP-Antwort aber noch nicht beendet.
        mockMvc.perform(get("/stream").param("roomId", roomId.toString()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        List<SseEmitter> connections = connectionRegistry.findConnections(roomId);
        assertEquals(1, connections.size());
        verifyNoInteractions(messageService);
    }

    /**
     * Ohne roomId wuessten wir nicht, in welchen Raum die Verbindung gehoert.
     */
    @Test
    void openStreamRejectsMissingRoomId() throws Exception {
        mockMvc.perform(get("/stream"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Mit Header kommen die verpassten Nachrichten als Ereignisse in der Antwort an, in der
     * Reihenfolge der Datenbank. Danach steht die Verbindung fuer den Live-Betrieb in der Registry.
     */
    @Test
    void openStreamSendsMissedMessagesInOrderAndStaysOpen() throws Exception {
        UUID roomId = UUID.randomUUID();
        Message first = newMessage("aaaaaaaa-0000-0000-0000-000000000001", roomId, "Erste");
        Message second = newMessage("aaaaaaaa-0000-0000-0000-000000000002", roomId, "Zweite");
        when(messageService.loadMessagesAfter(roomId, LAST_EVENT_ID)).thenReturn(List.of(first, second));

        MvcResult result = mockMvc.perform(get("/stream")
                        .param("roomId", roomId.toString())
                        .header("Last-Event-ID", LAST_EVENT_ID.toString()))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        int firstPosition = body.indexOf("id:" + first.id());
        int secondPosition = body.indexOf("id:" + second.id());
        assertTrue(firstPosition >= 0);
        assertTrue(firstPosition < secondPosition);
        assertTrue(body.contains("\"text\":\"Zweite\""));
        assertEquals(1, connectionRegistry.findConnections(roomId).size());
    }

    /**
     * Ein Header, der keine UUID ist, wird wie eine ungueltige roomId abgelehnt.
     */
    @Test
    void openStreamRejectsMalformedLastEventId() throws Exception {
        mockMvc.perform(get("/stream")
                        .param("roomId", UUID.randomUUID().toString())
                        .header("Last-Event-ID", "keine-uuid"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Faellt die Datenbank aus, darf die Verbindung nicht ohne die verpassten Nachrichten weiterlaufen.
     */
    @Test
    void openStreamClosesConnectionWhenDatabaseFails() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(messageService.loadMessagesAfter(roomId, LAST_EVENT_ID))
                .thenThrow(new DataAccessResourceFailureException("Datenbank nicht erreichbar"));

        mockMvc.perform(get("/stream")
                        .param("roomId", roomId.toString())
                        .header("Last-Event-ID", LAST_EVENT_ID.toString()))
                .andExpect(status().isOk());

        assertTrue(connectionRegistry.findConnections(roomId).isEmpty());
    }

    /**
     * Baut eine Beispiel-Nachricht mit der angegebenen id und dem angegebenen Text.
     */
    private Message newMessage(String id, UUID roomId, String text) {
        return new Message(UUID.fromString(id), roomId, "lernende1", text, Instant.parse("2026-09-04T08:05:00Z"));
    }
}
