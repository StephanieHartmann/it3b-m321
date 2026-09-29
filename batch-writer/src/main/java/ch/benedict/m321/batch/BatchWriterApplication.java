package ch.benedict.m321.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer. Im Unterschied zum chat-service startet hier kein Webserver,
 * weil der Dienst keine REST-Schnittstelle hat. Spring durchsucht dieses Paket samt
 * Unterpaketen nach Klassen, die es verwalten soll, und richtet die Verbindungen zu
 * PostgreSQL und RabbitMQ aus der application.yml ein.
 */
@SpringBootApplication
public class BatchWriterApplication {

    /**
     * Uebergibt die Startklasse an Spring Boot. Alles Weitere - Beans, Verbindungen,
     * Konfigurationsdateien - erledigt der Aufruf darunter.
     */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
