package org.rcs3000.resource.carrier.domain;

import java.math.BigDecimal;

/** One line of goods on a carrier (CAR-05). */
public record Goods(String sku, String name, BigDecimal quantity, String unit, String batchNo) {
}
