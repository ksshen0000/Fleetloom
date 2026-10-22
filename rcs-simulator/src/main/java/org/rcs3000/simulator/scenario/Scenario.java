package org.rcs3000.simulator.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.rcs3000.contracts.dispatch.OrderType;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * A scripted test run (SIM-12): extra virtual vehicles and streams of orders sent to dispatch at set
 * times, for regression and load tests. Written in YAML, e.g.
 *
 * <pre>
 * name: shuttle
 * timeScale: 5
 * vehicles:
 *   - {serialNumber: SC-001, initialPoint: P20, registerAs: JACK}
 * orders:
 *   - {every: 20s, count: 10, type: MOVE, targetPoints: [P00, P22]}
 *   - {start: 5s, every: 30s, count: 6, type: TRANSPORT, carriers: [S1, S2], targetZone: A}
 * </pre>
 *
 * Transport streams can also play the warehouse flows around the orders:
 *
 * <pre>
 *   # inbound: a shelf with goods arrives at a free dock, then goes to storage
 *   - {name: inbound, every: 30s, count: 10, type: TRANSPORT, targetZone: STORE,
 *      arrive: {zone: IN, carrierType: SHELF, prefix: IB, goods: [SKU-APPLE], quantity: 20}}
 *   # outbound: a stored shelf (holding the SKU, if given) goes to a dock and is loaded 20 s later
 *   - {name: outbound, every: 45s, count: 6, type: TRANSPORT, fromZone: STORE, sku: SKU-APPLE,
 *      targetZone: OUT, depart: 20s}
 *   # goods to person: a shelf visits a picking station for 15 s and goes back to storage
 *   - {name: picking, every: 40s, count: 8, type: TRANSPORT, fromZone: STORE, targetZone: PICK, returnAfter: 15s}
 * </pre>
 *
 * Times are real (wall clock) time. Lists are used round robin: the n-th order of a stream gets the
 * n-th entry. An order that finds no free dock or no carrier to move waits and is tried again.
 *
 * @param timeScale simulation speed while the run lasts, unchanged if absent
 * @param maxDuration the run is stopped after this long even if orders are still open
 */
public record Scenario(String name, Double timeScale, String maxDuration, List<VehicleSpec> vehicles,
        List<OrderStream> orders) {

    public Scenario {
        vehicles = vehicles == null ? List.of() : List.copyOf(vehicles);
        orders = orders == null ? List.of() : List.copyOf(orders);
    }

    /** @param registerAs vehicle type to register the vehicle with in rcs-resource, so dispatch uses it */
    public record VehicleSpec(String serialNumber, String initialPoint, Double x, Double y, Double battery,
            String registerAs) {
    }

    /**
     * @param name         label of the stream in the report, default {@code orders[i]}
     * @param start        delay of the first order, default 0
     * @param every        time between orders, needed when {@code count > 1}
     * @param count        number of orders, default 1
     * @param targetPoints MOVE orders: where to
     * @param carriers     TRANSPORT orders: which carrier; or one standing idle in {@code fromZone}; or
     *                     a new one, see {@code arrive}
     * @param sku          with {@code fromZone}: only carriers holding goods with this SKU (prefix)
     * @param targetZone   TRANSPORT orders: any free location there; or {@code targetLocation}
     * @param depart       after delivery the carrier is taken away (loaded onto a truck) this much later
     * @param returnAfter  after delivery the carrier is sent back this much later, to {@code returnTo}
     *                     (default {@code fromZone})
     * @param maxWait      how long an order waits for a free dock, location or carrier before it is given
     *                     up and counted as rejected, default 2 minutes
     */
    public record OrderStream(String name, String start, String every, Integer count, OrderType type,
            Integer priority, String mapCode, List<String> targetPoints, List<String> carriers, String fromZone,
            String sku, Arrival arrive, String targetZone, String targetLocation, String template, String depart,
            String returnAfter, String returnTo, String maxWait) {

        public int orderCount() {
            return count == null ? 1 : count;
        }

        public Duration startDelay() {
            return duration(start);
        }

        public Duration interval() {
            return duration(every);
        }

        public Duration departDelay() {
            return duration(depart);
        }

        public Duration returnDelay() {
            return duration(returnAfter);
        }

        public Duration maxWaitTime() {
            return maxWait == null ? Duration.ofMinutes(2) : duration(maxWait);
        }

        public String returnZone() {
            return returnTo != null ? returnTo : fromZone;
        }

        String label(int index) {
            return name == null || name.isBlank() ? "orders[" + index + "]" : name;
        }

        private static Duration duration(String text) {
            return text == null ? Duration.ZERO : DurationStyle.detectAndParse(text);
        }
    }

    /**
     * A new carrier that turns up at a free location of {@code zone}, as from a truck, before its
     * order is sent.
     *
     * @param prefix   start of the generated carrier codes, default {@code IB}
     * @param goods    SKUs put on the carriers, round robin; none if empty
     * @param quantity of each SKU, default 10
     */
    public record Arrival(String zone, String carrierType, String prefix, List<String> goods, Double quantity) {

        public Arrival {
            goods = goods == null ? List.of() : List.copyOf(goods);
        }

        public String codePrefix() {
            return prefix == null || prefix.isBlank() ? "IB" : prefix;
        }

        public double goodsQuantity() {
            return quantity == null ? 10 : quantity;
        }
    }

    public Duration maxRunTime() {
        return maxDuration == null ? Duration.ofHours(2) : DurationStyle.detectAndParse(maxDuration);
    }

    public int plannedOrders() {
        return orders.stream().mapToInt(OrderStream::orderCount).sum();
    }

    /** Reads and checks a scenario; throws {@link IllegalArgumentException} saying what is wrong. */
    public static Scenario parse(String yaml, JsonMapper json) {
        Object tree;
        try {
            tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not valid YAML: " + e.getMessage(), e);
        }
        if (!(tree instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("a scenario is a YAML mapping with name, vehicles and orders");
        }
        Scenario scenario;
        try {
            scenario = json.convertValue(tree, Scenario.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("scenario does not fit the expected structure: " + e.getMessage(), e);
        }
        scenario.validate();
        return scenario;
    }

    private void validate() {
        List<String> problems = new ArrayList<>();
        if (orders.isEmpty() && vehicles.isEmpty()) {
            problems.add("nothing to do: no vehicles and no orders");
        }
        if (timeScale != null && (timeScale <= 0 || timeScale > 100)) {
            problems.add("timeScale must be in (0, 100]");
        }
        for (VehicleSpec v : vehicles) {
            if (v.serialNumber() == null || v.serialNumber().isBlank()) {
                problems.add("every vehicle needs a serialNumber");
            }
        }
        for (int i = 0; i < orders.size(); i++) {
            OrderStream o = orders.get(i);
            String where = "orders[" + i + "]: ";
            try {
                o.startDelay();
                o.interval();
                o.departDelay();
                o.returnDelay();
                o.maxWaitTime();
            } catch (IllegalArgumentException e) {
                problems.add(where + "start, every, depart, returnAfter and maxWait are durations such as 30s or 2m");
                continue;
            }
            if (o.type() == null) {
                problems.add(where + "type is MOVE or TRANSPORT");
            } else if (o.type() == OrderType.MOVE && (o.targetPoints() == null || o.targetPoints().isEmpty())) {
                problems.add(where + "a MOVE stream needs targetPoints");
            } else if (o.type() == OrderType.TRANSPORT) {
                int sources = (o.carriers() == null || o.carriers().isEmpty() ? 0 : 1) + (o.fromZone() == null ? 0 : 1)
                        + (o.arrive() == null ? 0 : 1);
                if (sources != 1 || o.targetZone() == null && o.targetLocation() == null) {
                    problems.add(where + "a TRANSPORT stream needs one of carriers, fromZone or arrive, and a targetZone"
                            + " or targetLocation");
                }
                if (o.arrive() != null && (o.arrive().zone() == null || o.arrive().carrierType() == null)) {
                    problems.add(where + "arrive needs the zone and the carrierType");
                }
                if (o.sku() != null && o.fromZone() == null) {
                    problems.add(where + "sku only goes with fromZone");
                }
                if (o.depart() != null && o.returnAfter() != null) {
                    problems.add(where + "a carrier either departs or returns, not both");
                }
                if (o.returnAfter() != null && o.returnZone() == null) {
                    problems.add(where + "returnAfter needs returnTo or fromZone");
                }
            } else if (o.type() == OrderType.CHARGE) {
                problems.add(where + "type is MOVE or TRANSPORT");
            }
            if (o.orderCount() < 1 || o.orderCount() > 10_000) {
                problems.add(where + "count must be between 1 and 10000");
            }
            if (o.orderCount() > 1 && o.interval().isZero()) {
                problems.add(where + "several orders need every");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
    }
}
