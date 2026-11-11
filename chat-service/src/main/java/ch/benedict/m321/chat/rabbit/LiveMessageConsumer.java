package ch.benedict.m321.chat.rabbit;

import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.message.Message;
import ch.benedict.m321.chat.stream.MessageEventSender;
import ch.benedict.m321.chat.stream.SseConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Empfaengt jede Nachricht aus der Live-Queue dieser chat-service-Instanz und schickt sie an alle
 * offenen SSE-Verbindungen ihres Raums. Diese Klasse ist die Bruecke zwischen RabbitMQ und den
 * Browsern: Ohne sie saehe ein Client neue Nachrichten erst beim naechsten Laden des Verlaufs.
 */
@Component   // Spring baut diese Klasse beim Start und meldet den @RabbitListener unten an.
public class LiveMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(LiveMessageConsumer.class);

    private final SseConnectionRegistry connectionRegistry;
    private final MessageEventSender eventSender;

    // Beide kommen per Konstruktor-Injektion herein. Die Registry ist dasselbe Objekt, in das auch
    // der StreamController die neuen Verbindungen eintraegt.
    public LiveMessageConsumer(SseConnectionRegistry connectionRegistry, MessageEventSender eventSender) {
        this.connectionRegistry = connectionRegistry;
        this.eventSender = eventSender;
    }

    /**
     * Wird von Spring fuer jede Nachricht aus der Live-Queue einmal aufgerufen und verteilt sie an
     * den Raum. Das JSON ist hier schon in ein Message-Objekt umgewandelt, weil Spring am Parameter
     * sieht, welche Klasse wir erwarten, und dafuer unseren jsonMessageConverter benutzt.
     */
    // Der Name der Queue steht erst beim Start fest, er laesst sich also nicht fest hinschreiben.
    // "#{liveQueue.name}" heisst deshalb: Nimm die Bean "liveQueue" und frage sie nach ihrem Namen.
    // Der Listener laeuft auf einem eigenen Hintergrund-Thread, nicht auf einem Tomcat-Thread.
    @RabbitListener(queues = "#{liveQueue.name}")
    public void receive(Message message) {
        // Der chat-service setzt beide Felder immer, aber jede Anwendung kann in den Exchange
        // publizieren. Eine Exception duerfen wir hier nicht ausloesen: Spring wuerde die Nachricht
        // sonst immer wieder in die Queue legen. Darum loggen wir sie und lassen sie fallen.
        if (message.id() == null || message.roomId() == null) {
            log.warn("Live-Nachricht ohne id oder roomId verworfen: {}", message);
            return;
        }

        UUID roomId = message.roomId();

        // findConnections liefert eine Kopie der Liste. Der Sender darf also weiter unten
        // Verbindungen aus der Registry entfernen, waehrend wir diese Kopie durchlaufen.
        List<SseEmitter> connections = connectionRegistry.findConnections(roomId);
        log.debug("Live-Nachricht {} empfangen: Raum {}, {} offene Verbindungen",
                message.id(), roomId, connections.size());

        for (SseEmitter connection : connections) {
            // Eine Verbindung, die gerade nachholt, bekommt die Nachricht erst danach. Der Consumer
            // wartet nicht darauf, sonst stuenden waehrenddessen auch alle anderen Raeume still.
            boolean held = connectionRegistry.holdIfCatchingUp(connection, message);
            if (!held) {
                eventSender.send(connection, message);
            }
        }
    }
}
