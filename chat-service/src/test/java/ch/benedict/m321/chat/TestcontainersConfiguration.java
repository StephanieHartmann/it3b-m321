package ch.benedict.m321.chat;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Startet fuer die Integrationstests eine eigene RabbitMQ- und eine eigene PostgreSQL-Instanz in
 * Docker. Es gibt diese Klasse, damit die Tests unabhaengig vom docker-compose-Stack laufen und
 * dort keine Daten veraendern. Sie ist gleich aufgebaut wie die im batch-writer.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * Der Test-Broker: dasselbe Image wie in docker-compose.yml.
     */
    // @ServiceConnection: Spring Boot liest aus dem Container Adresse, Port, Benutzer und Passwort
    // aus und ersetzt damit die localhost-Werte aus application.yml. Den zufaelligen Port, den
    // Docker vergibt, muessen wir also nie kennen.
    @Bean
    @ServiceConnection
    public RabbitMQContainer rabbitContainer() {
        DockerImageName image = DockerImageName.parse("rabbitmq:4-management-alpine");
        return new RabbitMQContainer(image);
    }

    /**
     * Die Test-Datenbank: dasselbe Image wie in docker-compose.yml, mit demselben Schema.
     * Das Nachholen liest aus der Datenbank, deshalb brauchen die Tests auch sie echt.
     */
    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        DockerImageName image = DockerImageName.parse("postgres:17-alpine");
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(image);

        // Dasselbe Skript wie im echten Betrieb. Der Pfad ist relativ zum Modulordner
        // chat-service, darum "../db".
        MountableFile schema = MountableFile.forHostPath("../db/01-schema.sql");
        container.withCopyFileToContainer(schema, "/docker-entrypoint-initdb.d/01-schema.sql");
        return container;
    }
}
