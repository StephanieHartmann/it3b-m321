package ch.benedict.m321.chat.rabbit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueft die Einstellungen der Live-Queue als gewoehnliche Klasse, ganz ohne Spring und Broker.
 */
class RabbitConfigurationTest {

    /**
     * Prueft die Eigenschaften der Live-Queue, denn eine falsche Einstellung faellt im Betrieb
     * erst auf, wenn Nachrichten fehlen oder alte Queues im Broker liegen bleiben.
     */
    @Test
    void liveQueueHasRequestedProperties() {
        RabbitConfiguration configuration = new RabbitConfiguration();

        AnonymousQueue liveQueue = configuration.liveQueue();

        assertTrue(liveQueue.isExclusive());
        assertEquals(1000, liveQueue.getArguments().get("x-max-length"));
        assertEquals("drop-head", liveQueue.getArguments().get("x-overflow"));
        // TODO Übung: Prüfe hier, dass sich die Queue selbst löscht und einen Broker-Neustart nicht übersteht.
    }

    /**
     * Ohne die richtige Bindung bliebe die Live-Queue fuer immer leer.
     */
    @Test
    void liveBindingConnectsLiveQueueToChatExchange() {
        RabbitConfiguration configuration = new RabbitConfiguration();
        AnonymousQueue liveQueue = configuration.liveQueue();

        Binding liveBinding = configuration.liveBinding(liveQueue);

        assertEquals(liveQueue.getName(), liveBinding.getDestination());
        assertEquals(RabbitConfiguration.EXCHANGE_NAME, liveBinding.getExchange());
    }
}
