package ch.benedict.m321.chat.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legt fest, wie der chat-service mit RabbitMQ spricht.
 *
 * Ein Fanout-Exchange verteilt jede Nachricht an ALLE Queues, die an ihm haengen - ohne auf
 * einen Schluessel zu schauen (anders als z.B. ein Direct- oder Topic-Exchange). Genau das
 * brauchen wir: spaeter je eine Kopie fuer jede chat-service-Instanz (Anzeige per SSE) und eine
 * fuer den batch-service (Speichern in der Datenbank).
 */
@Configuration   // Markiert diese Klasse als Konfigurationsklasse. Spring liest sie beim Start
                 // und ruft jede @Bean-Methode darin genau einmal auf (siehe OpenApiConfiguration
                 // aus Task 1 - gleiches Prinzip, nur fuer RabbitMQ statt Swagger).
public class RabbitConfiguration {

    // Name des Exchange, auf den jede neue Nachricht publiziert wird. "public static final",
    // damit andere Klassen (z.B. MessageService gleich unten) diesen Namen benutzen koennen,
    // ohne ihn als String erneut abzutippen - ein Tippfehler wuerde sonst erst zur Laufzeit
    // auffallen, weil der Exchange dann "verfehlt" wuerde.
    public static final String EXCHANGE_NAME = "chat.messages";

    // Name der Queue, in der jede Nachricht wartet, bis der batch-service sie in die Datenbank
    // schreibt. Auch hier eine Konstante: der batch-service wird spaeter genau auf diesen Namen
    // hoeren. Ein Tippfehler an einer der beiden Stellen wuerde sonst still dazu fuehren, dass
    // er auf eine falsche, leere Queue wartet, waehrend sich die echte immer weiter fuellt.
    public static final String PERSIST_QUEUE_NAME = "chat.persist";

    /**
     * Meldet den Exchange beim Broker an. Spring legt ihn beim Start automatisch an, falls es
     * ihn noch nicht gibt - man muss in der RabbitMQ-Management-Oberflaeche nichts von Hand
     * anklicken.
     */
    @Bean   // Diese Methode wird beim Start genau einmal aufgerufen, das Ergebnis merkt sich
            // Spring und reicht es spaeter ueberall dort herein, wo ein FanoutExchange gebraucht
            // wird (Konstruktor-Injektion, wie wir es schon von JdbcTemplate kennen).
    public FanoutExchange chatExchange() {
        // Die drei Argumente des Konstruktors:
        // 1. EXCHANGE_NAME - der Name, unter dem der Exchange im Broker erscheint.
        // 2. durable = true - der Exchange uebersteht einen Neustart des Brokers. Ohne das
        //    wuerde RabbitMQ ihn beim naechsten Neustart einfach vergessen.
        // 3. autoDelete = false - der Exchange verschwindet NICHT von selbst, nur weil gerade
        //    keine Queue an ihm haengt. Seit es "chat.persist" gibt (siehe persistQueue() unten),
        //    haengt zwar immer mindestens eine Queue dran - der Exchange soll aber auch dann
        //    bestehen bleiben, wenn wir Queues einmal loeschen und neu anlegen.
        return new FanoutExchange(EXCHANGE_NAME, true, false);
    }

    /**
     * Meldet die Queue "chat.persist" beim Broker an. Sie ist sozusagen der Briefkasten des
     * batch-service: jede Nachricht bleibt darin liegen, bis er sie abholt - auch dann, wenn er
     * gerade gar nicht laeuft.
     */
    @Bean
    public Queue persistQueue() {
        // Die vier Argumente des Konstruktors:
        // 1. PERSIST_QUEUE_NAME - der Name, unter dem die Queue im Broker erscheint.
        // 2. durable = true - die Queue uebersteht einen Neustart des Brokers. Genau darum geht
        //    es bei dieser Queue: was noch nicht in der Datenbank steht, darf nicht verloren gehen.
        //    (Spring verschickt Nachrichten ausserdem von sich aus als "persistent". Erst beides
        //    zusammen - dauerhafte Queue UND dauerhafte Nachricht - schreibt sie auf die Festplatte.)
        // 3. exclusive = false - die Queue gehoert nicht einer einzigen Verbindung. Eine exklusive
        //    Queue duerfte nur die Verbindung benutzen, die sie angelegt hat (hier der chat-service),
        //    und sie wuerde mit dieser Verbindung verschwinden. Der batch-service kaeme nie heran.
        // 4. autoDelete = false - die Queue loescht sich nicht selbst, wenn sich ihr letzter Leser
        //    (spaeter der batch-service) abmeldet. Gerade dann soll sie ja weiter sammeln.
        return new Queue(PERSIST_QUEUE_NAME, true, false, false);
    }

    /**
     * Verbindet die Queue "chat.persist" mit dem Exchange "chat.messages". Ohne diese Verbindung
     * bliebe die Queue fuer immer leer: ein Exchange liefert nur an Queues, die an ihn gebunden sind.
     */
    @Bean
    public Binding persistBinding() {
        // Die fuenf Argumente des Konstruktors:
        // 1. PERSIST_QUEUE_NAME - das Ziel, also wohin die Nachrichten fliessen.
        // 2. Binding.DestinationType.QUEUE - das Ziel ist eine Queue. (RabbitMQ koennte auch einen
        //    Exchange an einen anderen Exchange binden - das brauchen wir nicht.)
        // 3. EXCHANGE_NAME - die Quelle, also der Exchange, von dem die Nachrichten kommen.
        // 4. "" - der Routing-Key. Ein Fanout-Exchange schaut ihn gar nicht an, er verteilt an
        //    alle gebundenen Queues. Darum geben wir bewusst einen leeren Text mit.
        // 5. null - keine Zusatzeinstellungen fuer diese Verbindung.
        return new Binding(PERSIST_QUEUE_NAME, Binding.DestinationType.QUEUE, EXCHANGE_NAME, "", null);
    }

    /**
     * Wandelt Nachrichten beim Senden in JSON um. Ohne diese Bean wuerde Spring die Objekte in
     * ein Java-eigenes Binaerformat serialisieren - in der RabbitMQ-Management-Oberflaeche waere
     * dann nur unlesbarer Zeichensalat zu sehen, statt dem JSON, das wir gleich im Broker
     * pruefen wollen.
     *
     * Wir reichen absichtlich den ObjectMapper von Spring Boot herein (Parameter der Methode):
     * der ist bereits so eingestellt, dass Zeitpunkte als lesbares "2026-09-04T08:05:00Z"
     * geschrieben werden und nicht als blosse Zahl (Millisekunden seit 1970).
     */
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
