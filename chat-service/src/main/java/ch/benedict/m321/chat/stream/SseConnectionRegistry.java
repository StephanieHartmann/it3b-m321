package ch.benedict.m321.chat.stream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.benedict.m321.chat.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Merkt sich, welche SSE-Verbindungen gerade offen sind, getrennt nach Raum.
 * Es gibt diese Klasse, damit der LiveMessageConsumer eine Nachricht gezielt an genau einen Raum
 * schicken kann, ohne dass der Controller die Verwaltung der Verbindungen kennen muss.
 *
 * Thread-Sicherheit: Jede HTTP-Anfrage laeuft auf einem eigenen Thread von Tomcat, das heisst,
 * mehrere Threads koennen gleichzeitig Verbindungen eintragen und austragen. HashMap und ArrayList
 * vertragen das nicht von selbst. Deshalb sind alle Methoden "synchronized": Es kann immer
 * nur ein Thread gleichzeitig in einer dieser Methoden sein, alle anderen warten kurz.
 */
@Component   // Spring baut genau ein Objekt dieser Klasse. So sehen alle Anfragen dieselbe Map.
public class SseConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseConnectionRegistry.class);

    // Schluessel ist die Raum-ID, Wert ist die Liste aller offenen Verbindungen dieses Raums.
    private final Map<UUID, List<SseEmitter>> connectionsByRoom = new HashMap<>();

    // Enthaelt nur Verbindungen, die gerade nachholen. Wert sind die Live-Nachrichten, die in dieser
    // Zeit angekommen sind und warten muessen, damit sie die nachgeholten nicht ueberholen.
    private final Map<SseEmitter, List<Message>> heldMessagesByConnection = new HashMap<>();

    /**
     * Traegt eine neue Verbindung bei ihrem Raum ein.
     * Der Controller ruft das auf, sobald sich ein Client verbunden hat.
     */
    public synchronized void add(UUID roomId, SseEmitter connection) {
        List<SseEmitter> connections = connectionsByRoom.get(roomId);

        // Die erste Verbindung eines Raums findet noch keine Liste vor und legt sie deshalb an.
        if (connections == null) {
            connections = new ArrayList<>();
            connectionsByRoom.put(roomId, connections);
        }

        connections.add(connection);
        log.debug("SSE-Verbindung eingetragen: Raum {}, jetzt {} offen", roomId, connections.size());
    }

    /**
     * Traegt eine Verbindung ein, die zuerst verpasste Nachrichten nachholt.
     * Ab jetzt sammeln wir ihre Live-Nachrichten, bis takeHeldMessages das Sammeln beendet.
     */
    public synchronized void startCatchUp(UUID roomId, SseEmitter connection) {
        add(roomId, connection);
        heldMessagesByConnection.put(connection, new ArrayList<>());
    }

    /**
     * Legt eine Live-Nachricht zur Seite, falls die Verbindung gerade nachholt.
     * Das Ergebnis sagt dem Consumer, ob er die Nachricht selbst noch senden muss (false).
     */
    public synchronized boolean holdIfCatchingUp(SseEmitter connection, Message message) {
        List<Message> heldMessages = heldMessagesByConnection.get(connection);

        if (heldMessages == null) {
            return false;
        }

        heldMessages.add(message);
        return true;
    }

    /**
     * Gibt die gesammelten Nachrichten heraus und leert die Sammlung.
     * Ist nichts mehr da, endet das Nachholen: Ab dann sendet der Consumer wieder selbst.
     */
    public synchronized List<Message> takeHeldMessages(SseEmitter connection) {
        List<Message> heldMessages = heldMessagesByConnection.get(connection);

        if (heldMessages == null) {
            return new ArrayList<>();
        }

        // Pruefen und Umschalten muessen im selben synchronized-Block passieren. Sonst koennte der
        // Consumer genau dazwischen noch eine Nachricht sammeln, die dann niemand mehr abholt.
        if (heldMessages.isEmpty()) {
            heldMessagesByConnection.remove(connection);
            return new ArrayList<>();
        }

        heldMessagesByConnection.put(connection, new ArrayList<>());
        return heldMessages;
    }

    /**
     * Traegt eine beendete Verbindung wieder aus.
     * Ohne diesen Schritt wuerde die Liste mit jeder getrennten Verbindung weiter wachsen.
     */
    public synchronized void remove(UUID roomId, SseEmitter connection) {
        heldMessagesByConnection.remove(connection);

        List<SseEmitter> connections = connectionsByRoom.get(roomId);

        // Der Raum kann schon weg sein, wenn seine letzte Verbindung bereits ausgetragen wurde.
        if (connections == null) {
            return;
        }

        // Eine Verbindung kann hier zweimal ankommen: einmal vom Consumer nach einem gescheiterten
        // Senden und einmal von onCompletion. Beim zweiten Mal gibt es nichts mehr zu tun.
        boolean wasRegistered = connections.remove(connection);
        if (!wasRegistered) {
            return;
        }

        log.debug("SSE-Verbindung ausgetragen: Raum {}, noch {} offen", roomId, connections.size());

        // Leere Listen entfernen wir auch. Die roomId kommt vom Client, und sonst bliebe fuer
        // jede jemals angefragte ID ein Eintrag in der Map liegen.
        if (connections.isEmpty()) {
            connectionsByRoom.remove(roomId);
        }
    }

    /**
     * Liefert alle offenen Verbindungen eines Raums, bei einem leeren Raum eine leere Liste.
     * Der LiveMessageConsumer holt sich hier die Empfaenger einer Nachricht.
     */
    public synchronized List<SseEmitter> findConnections(UUID roomId) {
        List<SseEmitter> connections = connectionsByRoom.get(roomId);

        if (connections == null) {
            return new ArrayList<>();
        }

        // Wir geben eine Kopie zurueck. Wer sie durchlaeuft, ist ausserhalb von "synchronized"
        // unterwegs. Die echte Liste koennte sich dabei gleichzeitig aendern, die Kopie nicht.
        // So haelt auch ein langsamer Client beim Senden die Sperre dieser Klasse nicht fest, und
        // neue Verbindungen koennen sich weiterhin eintragen.
        return new ArrayList<>(connections);
    }
}
