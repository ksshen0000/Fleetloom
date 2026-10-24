package org.fleetloom.fleet.app;

import java.util.concurrent.atomic.AtomicInteger;

import org.fleetloom.vda5050.transport.MessageHandler;
import org.fleetloom.vda5050.transport.MqttTransport;
import org.fleetloom.vda5050.transport.Qos;
import org.fleetloom.vda5050.transport.WillMessage;

/** Loses the next {@link #dropOrders} order messages, as an unreliable network may with QoS 0. */
final class LossyTransport implements MqttTransport {

    final AtomicInteger dropOrders = new AtomicInteger();
    private final MqttTransport delegate;

    LossyTransport(MqttTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public void addConnectedListener(Runnable listener) {
        delegate.addConnectedListener(listener);
    }

    @Override
    public void connect(WillMessage will) {
        delegate.connect(will);
    }

    @Override
    public void publish(String topic, byte[] payload, Qos qos, boolean retained) {
        if (topic.endsWith("/order") && dropOrders.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            return;
        }
        delegate.publish(topic, payload, qos, retained);
    }

    @Override
    public void subscribe(String topicFilter, Qos qos, MessageHandler handler) {
        delegate.subscribe(topicFilter, qos, handler);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
