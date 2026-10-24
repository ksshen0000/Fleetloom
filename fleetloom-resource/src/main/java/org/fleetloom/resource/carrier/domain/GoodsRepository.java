package org.fleetloom.resource.carrier.domain;

import java.util.List;

public interface GoodsRepository {

    /** In the order they were saved. */
    List<Goods> find(String carrierCode);

    void replace(String carrierCode, List<Goods> goods);

    /** Carriers holding goods whose SKU starts with {@code skuPrefix}. */
    List<Stocked> findBySku(String skuPrefix, int limit);

    record Stocked(String carrierCode, Goods goods) {
    }
}
