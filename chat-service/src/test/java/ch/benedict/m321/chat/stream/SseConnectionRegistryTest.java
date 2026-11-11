package ch.benedict.m321.chat.stream;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testet die Registry als gewoehnliche Klasse, ganz ohne Spring.
 */
class SseConnectionRegistryTest {

    private static final UUID ROOM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /**
     * Das gezielte Senden an einen Raum funktioniert nur, wenn die Raeume sauber getrennt sind.
     */
    @Test
    void keepsConnectionsOfRoomsSeparate() {
        SseConnectionRegistry registry = new SseConnectionRegistry();
        SseEmitter connectionInRoomA = new SseEmitter();
        SseEmitter connectionInRoomB = new SseEmitter();

        registry.add(ROOM_A, connectionInRoomA);
        registry.add(ROOM_B, connectionInRoomB);

        List<SseEmitter> connections = registry.findConnections(ROOM_A);
        assertEquals(List.of(connectionInRoomA), connections);
    }

    /**
     * Eine beendete Verbindung darf nicht mehr gefunden werden, sonst wuerden wir spaeter
     * an eine tote Verbindung senden.
     */
    @Test
    void removedConnectionIsNoLongerFound() {
        SseConnectionRegistry registry = new SseConnectionRegistry();
        SseEmitter connection = new SseEmitter();

        registry.add(ROOM_A, connection);
        registry.remove(ROOM_A, connection);

        List<SseEmitter> connections = registry.findConnections(ROOM_A);
        assertTrue(connections.isEmpty());
    }

    /**
     * Prueft die Thread-Sicherheit: Viele Threads tragen gleichzeitig ein. Ohne "synchronized"
     * wuerden in der ArrayList Eintraege verloren gehen oder es gaebe eine Exception.
     */
    @Test
    void keepsAllConnectionsWhenManyThreadsAddAtOnce() throws Exception {
        SseConnectionRegistry registry = new SseConnectionRegistry();
        int threadCount = 8;
        int connectionsPerThread = 1000;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < connectionsPerThread; j++) {
                    registry.add(ROOM_A, new SseEmitter());
                }
            });
            threads[i].start();
        }

        // join wartet, bis der Thread fertig ist. Sonst wuerden wir zu frueh zaehlen.
        for (int i = 0; i < threadCount; i++) {
            threads[i].join();
        }

        List<SseEmitter> connections = registry.findConnections(ROOM_A);
        assertEquals(threadCount * connectionsPerThread, connections.size());
    }
}
