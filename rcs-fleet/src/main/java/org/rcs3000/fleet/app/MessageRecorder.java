package org.rcs3000.fleet.app;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.rcs3000.fleet.core.MessageLog;
import org.rcs3000.fleet.core.MessageLog.Direction;
import org.rcs3000.vda5050.client.MessageTap;
import org.rcs3000.vda5050.client.VehicleId;
import org.rcs3000.vda5050.v2.ActionState;
import org.rcs3000.vda5050.v2.State;
import org.rcs3000.vda5050.v2.Vda5050Message;
import org.rcs3000.vda5050.v2.Vda5050Topic;

/**
 * Writes the message traffic of every vehicle to the {@link MessageLog} (FLT-07). Commands,
 * connection changes and factsheets are kept in full. States arrive about once a second, so one is
 * kept only when something other than position, velocity or battery changed, or when
 * {@code stateInterval} has passed since the last one kept; visualization messages are skipped.
 */
public class MessageRecorder implements MessageTap {

    private final MessageLog log;
    private final Clock clock;
    private final Duration stateInterval;
    private final Map<String, Kept> lastStates = new ConcurrentHashMap<>();

    public MessageRecorder(MessageLog log, Clock clock, Duration stateInterval) {
        this.log = log;
        this.clock = clock;
        this.stateInterval = stateInterval;
    }

    @Override
    public void sent(VehicleId vehicle, Vda5050Topic topic, Vda5050Message message, byte[] payload) {
        log.append(vehicle.serialNumber(), Direction.OUT, topic.segment(), text(payload));
    }

    @Override
    public void received(VehicleId vehicle, Vda5050Topic topic, Vda5050Message message, byte[] payload) {
        if (topic == Vda5050Topic.VISUALIZATION) {
            return;
        }
        if (message instanceof State state && !worthKeeping(vehicle.serialNumber(), state)) {
            return;
        }
        log.append(vehicle.serialNumber(), Direction.IN, topic.segment(), text(payload));
    }

    private boolean worthKeeping(String code, State state) {
        Instant now = clock.instant();
        Signature signature = Signature.of(state);
        Kept previous = lastStates.get(code);
        if (previous != null && previous.signature.equals(signature)
                && previous.at.plus(stateInterval).isAfter(now)) {
            return false;
        }
        lastStates.put(code, new Kept(signature, now));
        return true;
    }

    private static String text(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }

    private record Kept(Signature signature, Instant at) {
    }

    /** The parts of a state that matter when tracing what a vehicle did. */
    private record Signature(String orderId, long orderUpdateId, String lastNodeId, boolean driving, Boolean paused,
            Boolean newBaseRequest, Object operatingMode, List<String> actions, List<String> errors, int loads) {

        static Signature of(State s) {
            List<String> actions = s.actionStates() == null ? List.of()
                    : s.actionStates().stream().map(Signature::action).toList();
            List<String> errors = s.errors() == null ? List.of()
                    : s.errors().stream().map(e -> e.errorType() + "/" + e.errorLevel()).toList();
            return new Signature(s.orderId(), s.orderUpdateId(), s.lastNodeId(), s.driving(), s.paused(),
                    s.newBaseRequest(), s.operatingMode(), actions, errors, s.loads() == null ? 0 : s.loads().size());
        }

        private static String action(ActionState a) {
            return a.actionId() + "=" + Objects.toString(a.actionStatus());
        }
    }
}
