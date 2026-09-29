package ch.benedict.m321.batch;

import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.ClassMapper;

/**
 * Sagt dem JSON-Umwandler, in welche Java-Klasse er eine eingehende Nachricht umwandeln soll:
 * immer in unsere Message, ohne Ausnahme. Es gibt diese Klasse, weil der Standard-Umwandler
 * sonst dem Header __TypeId__ des Absenders vertrauen wuerde - und der nennt eine Klasse aus
 * dem chat-service, die es hier nicht gibt, oder fehlt ganz (Szenario S5).
 */
public class FixedMessageClassMapper implements ClassMapper {

    /**
     * Wird beim SENDEN aufgerufen, um den Klassennamen in die Header zu schreiben. Der
     * batch-writer sendet nichts, darum bleibt die Methode absichtlich leer.
     */
    @Override
    public void fromClass(Class<?> clazz, MessageProperties properties) {
        // Nichts zu tun: wir lesen nur, wir senden keine Nachrichten.
    }

    /**
     * Wird beim EMPFANGEN aufgerufen und liefert die Zielklasse fuer das JSON. Die Header der
     * Nachricht (properties) schauen wir absichtlich nicht an.
     */
    @Override
    public Class<?> toClass(MessageProperties properties) {
        return Message.class;
    }
}
