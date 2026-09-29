package ch.benedict.m321.batch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prueft, dass Spring alle Klassen des batch-writer zusammenbauen kann. Der Test hat
 * absichtlich keinen Rumpf: fehlt beim Hochfahren eine Bean oder ist die Konfiguration
 * fehlerhaft, schlaegt er hier fehl, bevor irgendjemand den Dienst startet.
 */
@SpringBootTest
class BatchWriterApplicationTest {

    /**
     * Leerer Test: bestanden ist er, sobald der Spring-Kontext fehlerfrei geladen wurde.
     */
    @Test
    void contextLoads() {
        // Kein Inhalt noetig - der Test besteht darin, dass @SpringBootTest oben durchlaeuft.
    }
}
