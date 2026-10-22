package org.rcs3000.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.rcs3000.contracts.dispatch.OrderType;
import tools.jackson.databind.json.JsonMapper;

class ScenarioTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void readsTheDocumentedExample() {
        Scenario s = Scenario.parse("""
                name: shuttle
                timeScale: 5
                vehicles:
                  - {serialNumber: SC-001, initialPoint: P20, registerAs: JACK}
                orders:
                  - {every: 20s, count: 10, type: MOVE, targetPoints: [P00, P22]}
                  - {start: 5s, every: 2m, count: 6, type: TRANSPORT, carriers: [S1, S2], targetZone: A}
                """, json);

        assertThat(s.name()).isEqualTo("shuttle");
        assertThat(s.vehicles()).singleElement().satisfies(v -> assertThat(v.registerAs()).isEqualTo("JACK"));
        assertThat(s.plannedOrders()).isEqualTo(16);
        assertThat(s.orders().get(1).type()).isEqualTo(OrderType.TRANSPORT);
        assertThat(s.orders().get(1).startDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(s.orders().get(1).interval()).isEqualTo(Duration.ofMinutes(2));
        assertThat(s.maxRunTime()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void readsTheWarehouseFlows() {
        Scenario s = Scenario.parse("""
                orders:
                  - {name: inbound, every: 30s, count: 10, type: TRANSPORT, targetZone: STORE,
                     arrive: {zone: IN, carrierType: SHELF, goods: [SKU-APPLE], quantity: 20}}
                  - {every: 40s, count: 8, type: TRANSPORT, fromZone: STORE, targetZone: PICK, returnAfter: 15s}
                """, json);

        Scenario.OrderStream inbound = s.orders().get(0);
        assertThat(inbound.label(0)).isEqualTo("inbound");
        assertThat(inbound.arrive().codePrefix()).isEqualTo("IB");
        assertThat(inbound.arrive().goodsQuantity()).isEqualTo(20);
        Scenario.OrderStream picking = s.orders().get(1);
        assertThat(picking.label(1)).isEqualTo("orders[1]");
        assertThat(picking.returnDelay()).isEqualTo(Duration.ofSeconds(15));
        assertThat(picking.returnZone()).isEqualTo("STORE");
    }

    @Test
    void saysWhatIsWrong() {
        assertThatThrownBy(() -> Scenario.parse("- just a list", json)).hasMessageContaining("mapping");
        assertThatThrownBy(() -> Scenario.parse("name: [unclosed", json)).hasMessageContaining("YAML");
        assertThatThrownBy(() -> Scenario.parse("""
                orders:
                  - {type: MOVE}
                  - {type: TRANSPORT, carriers: [S1]}
                  - {type: MOVE, targetPoints: [P1], count: 3}
                  - {type: MOVE, targetPoints: [P1], every: soon}
                  - {type: TRANSPORT, fromZone: A, carriers: [S1], targetZone: B}
                  - {type: TRANSPORT, arrive: {zone: IN}, targetZone: B, depart: 1s, returnAfter: 1s}
                  - {type: TRANSPORT, carriers: [S1], sku: X, targetZone: B}
                """, json))
                .hasMessageContaining("orders[0]: a MOVE stream needs targetPoints")
                .hasMessageContaining("orders[1]: a TRANSPORT stream needs one of carriers, fromZone or arrive")
                .hasMessageContaining("orders[2]: several orders need every")
                .hasMessageContaining("orders[3]: start, every, depart, returnAfter and maxWait are durations")
                .hasMessageContaining("orders[4]: a TRANSPORT stream needs one of")
                .hasMessageContaining("orders[5]: arrive needs the zone and the carrierType")
                .hasMessageContaining("orders[5]: a carrier either departs or returns")
                .hasMessageContaining("orders[6]: sku only goes with fromZone");
    }
}
