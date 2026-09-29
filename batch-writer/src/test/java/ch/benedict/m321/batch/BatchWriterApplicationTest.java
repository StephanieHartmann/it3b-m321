package ch.benedict.m321.batch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prueft, dass Spring alle Klassen des batch-writer zusammenbauen kann. Der Test hat
 * absichtlich keinen Rumpf: fehlt beim Hochfahren eine Bean oder ist die Konfiguration
 * fehlerhaft, schlaegt er hier fehl, bevor irgendjemand den Dienst startet.
 *
 * Der Listener wird im Test nicht gestartet (auto-startup=false). Sonst wuerde der Test
 * echte Nachrichten aus der laufenden Queue "chat.persist" abholen und in die Datenbank
 * schreiben - ein Test soll aber keine Daten veraendern, die ihm nicht gehoeren.
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class BatchWriterApplicationTest {

    /**
     * Leerer Test: bestanden ist er, sobald der Spring-Kontext fehlerfrei geladen wurde.
     */
    @Test
    void contextLoads() {
        // Kein Inhalt noetig - der Test besteht darin, dass @SpringBootTest oben durchlaeuft.
    }
}
