package org.fleetloom.simulator.scenario;

import java.util.List;

import org.fleetloom.contracts.dispatch.CreateOrderDto;
import org.fleetloom.contracts.dispatch.OrderViewDto;

/** The platform services a scenario run talks to. */
public interface Platform {

    OrderViewDto createOrder(CreateOrderDto order);

    OrderViewDto order(String orderNo);

    /** Registers a vehicle in fleetloom-resource's registry; one that exists already is left as it is. */
    void registerVehicle(String code, String typeCode, String manufacturer, String initialPoint);

    /** The storage locations of a zone. */
    List<Location> locations(String zone);

    /** Codes of the carriers holding goods with this SKU (prefix). */
    List<String> carriersWithGoods(String sku);

    /** A new carrier standing at {@code location}, holding the given goods (SKU → quantity). */
    void createCarrier(String code, String typeCode, String location, List<GoodsLine> goods);

    /** The carrier leaves the site: taken out of its location and deleted. */
    void removeCarrier(String code);

    /** @param status EMPTY, OCCUPIED, LOCKED_IN, LOCKED_OUT or DISABLED */
    record Location(String code, String status, String carrierCode) {

        public boolean empty() {
            return "EMPTY".equals(status);
        }

        /** Holds a carrier that no order is about to take away. */
        public boolean idleCarrier() {
            return "OCCUPIED".equals(status) && carrierCode != null;
        }
    }

    record GoodsLine(String sku, double quantity) {
    }
}
