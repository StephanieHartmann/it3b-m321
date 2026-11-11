package ch.benedict.m321.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Prueft, dass Spring alle Klassen zusammenbauen kann. Der Test hat absichtlich keinen
 * Rumpf: faellt beim Hochfahren irgendwo eine Bean weg oder ist eine Konfiguration
 * fehlerhaft, schlaegt er hier fehl, bevor irgendjemand die Anwendung startet.
 */
// Seit es den Consumer auf der Live-Queue gibt, verbindet sich die Anwendung schon beim Start mit
// RabbitMQ. Ohne Broker wuerde sie es den ganzen Testlauf lang vergeblich versuchen. Die Einstellungen
// sind dieselben wie im LiveStreamIntegrationTest: Nur dann verwendet Spring denselben Kontext wieder
// und startet die Container nicht ein zweites Mal.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ChatServiceApplicationTest {

    /**
     * Leerer Test: bestanden ist er, sobald der Spring-Kontext fehlerfrei geladen wurde.
     */
    @Test
    void contextLoads() {
        // Kein Inhalt noetig - der Test besteht darin, dass @SpringBootTest oben durchlaeuft.
    }
}
