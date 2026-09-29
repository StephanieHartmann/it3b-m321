package ch.benedict.m321.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legt fest, wie der batch-writer Nachrichten aus RabbitMQ liest: aus welcher Queue, wie das
 * JSON in ein Message-Objekt umgewandelt wird und wann eine Nachricht bestaetigt (ACK) wird.
 */
@Configuration   // Spring liest diese Klasse beim Start und ruft jede @Bean-Methode genau einmal auf.
public class RabbitConfiguration {

    // Name der Queue, aus der wir lesen. Muss exakt gleich heissen wie im chat-service
    // (dort RabbitConfiguration.PERSIST_QUEUE_NAME) - sonst warten wir auf eine leere Queue.
    public static final String PERSIST_QUEUE_NAME = "chat.persist";

    /**
     * Meldet die Queue "chat.persist" beim Broker an. Der chat-service legt sie zwar auch an,
     * aber wir wissen nicht, welcher Dienst zuerst startet. Ohne diese Bean wuerde der Listener
     * ins Leere greifen, wenn der batch-writer vor dem chat-service hochfaehrt.
     */
    @Bean
    public Queue persistQueue() {
        // Dieselben vier Argumente wie im chat-service - sie MUESSEN uebereinstimmen, sonst
        // lehnt RabbitMQ die zweite Anmeldung mit einem Fehler ab (PRECONDITION_FAILED):
        // 1. Name der Queue
        // 2. durable = true: die Queue uebersteht einen Neustart des Brokers
        // 3. exclusive = false: auch andere Verbindungen duerfen sie benutzen
        // 4. autoDelete = false: sie verschwindet nicht, wenn sich der letzte Leser abmeldet
        return new Queue(PERSIST_QUEUE_NAME, true, false, false);
    }

    /**
     * Wandelt den Inhalt jeder eingehenden Nachricht von JSON in ein Message-Objekt um.
     * Der Umwandler schaut dabei nur auf den content_type "application/json" und nimmt als
     * Ziel immer unsere eigene Klasse Message - egal, was der Absender sonst mitschickt.
     */
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        // Den ObjectMapper von Spring Boot nehmen wir, weil er Zeitpunkte wie
        // "2026-09-04T08:05:00Z" bereits in ein Instant umwandeln kann.
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);

        // Der chat-service schreibt in einen Header (__TypeId__) den Namen SEINER Java-Klasse:
        // ch.benedict.m321.chat.message.Message. Die gibt es hier nicht. Und eine Nachricht,
        // die jemand von Hand in die Queue legt (Szenario S5), hat diesen Header gar nicht.
        // Darum setzen wir einen eigenen ClassMapper, der den Header ignoriert und immer
        // unsere Message-Klasse als Ziel angibt.
        FixedMessageClassMapper classMapper = new FixedMessageClassMapper();
        converter.setClassMapper(classMapper);
        return converter;
    }

    /**
     * Baut die "Fabrik" fuer alle @RabbitListener dieses Dienstes. Der Bean-Name
     * rabbitListenerContainerFactory ist wichtig: unter genau diesem Namen sucht Spring die
     * Fabrik, wenn beim @RabbitListener keine andere angegeben ist.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();

        // Der configurer uebernimmt alle Einstellungen aus application.yml
        // (spring.rabbitmq.listener...). So gehen sie nicht verloren, obwohl wir die Fabrik
        // selbst bauen - wichtig fuer Schritt 3, wenn dort prefetch eingestellt wird.
        configurer.configure(factory, connectionFactory);

        // Ab hier gilt unser JSON-Umwandler von oben.
        factory.setMessageConverter(jsonMessageConverter);

        // AUTO ist schon der Standard - wir schreiben es trotzdem hin, weil es der Kern dieses
        // Schritts ist: Spring sendet das ACK erst, wenn die Listener-Methode OHNE Fehler
        // zurueckkehrt, also erst nach dem erfolgreichen INSERT. Wirft sie eine Exception,
        // gibt es kein ACK und die Nachricht geht zurueck in die Queue.
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        return factory;
    }
}
