package ch.benedict.m321.batch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Startet fuer die Tests eine eigene PostgreSQL- und eine eigene RabbitMQ-Instanz in Docker.
 * Es gibt diese Klasse, damit die Tests unabhaengig vom docker-compose-Stack laufen - der
 * veroeffentlicht seit der Containerisierung keine Ports mehr auf localhost.
 *
 * Alle Testklassen, die diese Konfiguration mit @Import einbinden (und sonst gleich
 * eingestellt sind), teilen sich denselben Spring-Kontext und damit dieselben Container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * Die Test-Datenbank: dasselbe Image wie in docker-compose.yml, mit demselben Schema.
     */
    // @ServiceConnection: Spring Boot liest aus dem Container Adresse, Port, Benutzer und
    // Passwort aus und setzt damit spring.datasource.* - statt der localhost-Werte aus
    // application.yml. Den zufaelligen Port, den Docker vergibt, muessen wir nie kennen.
    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        DockerImageName image = DockerImageName.parse("postgres:17-alpine");
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(image);

        // Dieselben Skripte wie im echten Betrieb: das Schema (Tabelle message) und die
        // Demo-Daten (der Raum 1111..., auf den der Fremdschluessel room_id zeigt).
        // Der Pfad ist relativ zum Modulordner batch-writer, darum "../db".
        MountableFile schema = MountableFile.forHostPath("../db/01-schema.sql");
        MountableFile demoData = MountableFile.forHostPath("../db/02-demo-data.sql");
        container.withCopyFileToContainer(schema, "/docker-entrypoint-initdb.d/01-schema.sql");
        container.withCopyFileToContainer(demoData, "/docker-entrypoint-initdb.d/02-demo-data.sql");
        return container;
    }

    /**
     * Der Test-Broker: dasselbe Image wie in docker-compose.yml. Die Queues chat.persist und
     * chat.dlq legt der batch-writer beim Start selbst an (RabbitConfiguration).
     */
    @Bean
    @ServiceConnection
    public RabbitMQContainer rabbitContainer() {
        DockerImageName image = DockerImageName.parse("rabbitmq:4-management-alpine");
        return new RabbitMQContainer(image);
    }
}
