package org.rcs3000.vda5050.client;

import java.time.Clock;
import java.util.function.BiConsumer;

import org.rcs3000.vda5050.transport.MqttTransport;
import org.rcs3000.vda5050.v2.Connection;
import org.rcs3000.vda5050.v2.InstantActions;
import org.rcs3000.vda5050.v2.Order;
import org.rcs3000.vda5050.v2.State;
import org.rcs3000.vda5050.v2.TopicPath;
import org.rcs3000.vda5050.v2.Vda5050Codec;
import org.rcs3000.vda5050.v2.Vda5050Message;
import org.rcs3000.vda5050.v2.Vda5050Topic;
import org.rcs3000.vda5050.v2.Visualization;
import org.rcs3000.vda5050.v2.factsheet.Factsheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fleet-control side of VDA 5050: sends orders and instant actions to any vehicle and receives
 * the messages of all vehicles through wildcard subscriptions.
 */
public final class MasterClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MasterClient.class);

    private final MqttTransport transport;
    private final String interfaceName;
    private final Vda5050Codec codec = new Vda5050Codec();
    private final HeaderSequence headers;

    public MasterClient(MqttTransport transport, String interfaceName, Clock clock) {
        this.transport = transport;
        this.interfaceName = interfaceName;
        this.headers = new HeaderSequence(clock);
    }

    public void start(MasterListener listener) {
        transport.connect(null);
        subscribe(Vda5050Topic.CONNECTION, Connection.class, listener::onConnection);
        subscribe(Vda5050Topic.STATE, State.class, listener::onState);
        subscribe(Vda5050Topic.VISUALIZATION, Visualization.class, listener::onVisualization);
        subscribe(Vda5050Topic.FACTSHEET, Factsheet.class, listener::onFactsheet);
    }

    /** Sends an order; its header is assigned here. */
    public void sendOrder(VehicleId vehicle, Order order) {
        publish(vehicle, Vda5050Topic.ORDER, order);
    }

    public void sendInstantActions(VehicleId vehicle, InstantActions actions) {
        publish(vehicle, Vda5050Topic.INSTANT_ACTIONS, actions);
    }

    @Override
    public void close() {
        transport.close();
    }

    private void publish(VehicleId vehicle, Vda5050Topic topic, Vda5050Message message) {
        Vda5050Message stamped = message.withHeader(headers.next(vehicle.manufacturer(), vehicle.serialNumber(), topic));
        TopicPath path = new TopicPath(interfaceName, TopicPath.MAJOR_VERSION, vehicle.manufacturer(),
                vehicle.serialNumber(), topic);
        transport.publish(path.toString(), codec.encode(stamped), topic.qos(), topic.retained());
    }

    private <T extends Vda5050Message> void subscribe(Vda5050Topic topic, Class<T> type, BiConsumer<VehicleId, T> consumer) {
        transport.subscribe(TopicPath.filterForAllVehicles(interfaceName, topic), topic.qos(), (t, payload) -> {
            TopicPath path;
            T message;
            try {
                path = TopicPath.parse(t);
                message = codec.decode(payload, type);
            } catch (IllegalArgumentException e) {
                log.warn("dropping malformed message on {}: {}", t, e.getMessage());
                return;
            }
            consumer.accept(new VehicleId(path.manufacturer(), path.serialNumber()), message);
        });
    }
}
