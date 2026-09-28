package ch.benedict.m321.chat.message;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enthaelt die Fachlogik rund um Nachrichten.
 *
 * Der Controller (die naechste Klasse) kennt NUR diesen Service - er kennt weder das
 * Repository noch spaeter RabbitMQ direkt. Diese Trennung in drei Schichten
 * (Controller -> Service -> Repository) ist bewusst so gewaehlt: die Weboberflaeche (Controller)
 * bleibt getrennt von der Technik dahinter (Datenbank, Broker). Aendert sich spaeter zum
 * Beispiel, WIE wir Nachrichten lesen oder pruefen, muss der Controller davon nichts wissen.
 *
 * In diesem Task kann der Service nur LESEN. Das Senden (Task 4) wird hier spaeter ergaenzt.
 */
@Service   // Markiert diese Klasse als "Fachlogik-Baustein" - genauso wie @Repository, nur fuer
           // eine andere Schicht. Spring baut auch sie automatisch beim Start.
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    // Auch hier gilt: das Repository wird nicht selbst mit "new" gebaut, sondern von Spring
    // ueber den Konstruktor hereingereicht (Konstruktor-Injektion, siehe MessageRepository).
    private final MessageRepository messageRepository;

    public MessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Liefert den Verlauf eines Raums - reines Lesen, hier wird nichts veraendert.
     * Die Methode ist bewusst duenn: sie loggt und reicht den Aufruf einfach an das Repository
     * weiter. Trotzdem lohnt sich eine eigene Service-Methode, weil hier spaeter zum Beispiel
     * eine Mitgliedschaftspruefung dazukommen koennte (Raumverwaltung, Phase 6 im Gesamtplan),
     * ohne dass sich am Controller etwas aendern muesste.
     */
    public List<Message> loadHistory(UUID roomId, int limit) {
        log.debug("Verlauf angefordert: Raum {}, hoechstens {} Nachrichten", roomId, limit);
        return messageRepository.findLatest(roomId, limit);
    }
}
