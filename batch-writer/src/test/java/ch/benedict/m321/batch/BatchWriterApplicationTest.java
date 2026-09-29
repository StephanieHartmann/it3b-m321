package ch.benedict.m321.batch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Prueft, dass Spring alle Klassen des batch-writer zusammenbauen kann und der Dienst mit
 * einer echten Datenbank und einem echten Broker startet. Beide kommen aus Testcontainers
 * (siehe TestcontainersConfiguration) - der Test veraendert also keine fremden Daten.
 */
// Die properties muessen in ALLEN Testklassen gleich sein, die TestcontainersConfiguration
// benutzen: nur dann verwendet Spring denselben Kontext wieder und startet die Container
// nicht ein zweites Mal. Die Bedeutung steht in MessagePersistenceTest.
@SpringBootTest(properties = "spring.datasource.hikari.connection-timeout=1000")
@Import(TestcontainersConfiguration.class)
class BatchWriterApplicationTest {

    /**
     * Leerer Test: bestanden ist er, sobald der Spring-Kontext fehlerfrei geladen wurde.
     */
    @Test
    void contextLoads() {
        // Kein Inhalt noetig - der Test besteht darin, dass @SpringBootTest oben durchlaeuft.
    }
}
