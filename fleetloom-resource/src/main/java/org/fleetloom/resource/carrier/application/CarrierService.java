package org.fleetloom.resource.carrier.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.fleetloom.common.error.BizException;
import org.fleetloom.resource.carrier.application.CarrierViews.CarrierTypeView;
import org.fleetloom.resource.carrier.application.CarrierViews.CarrierView;
import org.fleetloom.resource.carrier.domain.Carrier;
import org.fleetloom.resource.carrier.domain.CarrierCategory;
import org.fleetloom.resource.carrier.domain.CarrierErrorCode;
import org.fleetloom.resource.carrier.domain.CarrierLocationType;
import org.fleetloom.resource.carrier.domain.CarrierMovement;
import org.fleetloom.resource.carrier.domain.CarrierRepository;
import org.fleetloom.resource.carrier.domain.CarrierStatus;
import org.fleetloom.resource.carrier.domain.CarrierType;
import org.fleetloom.resource.carrier.domain.CarrierTypeRepository;
import org.fleetloom.resource.carrier.domain.Goods;
import org.fleetloom.resource.carrier.domain.GoodsRepository;
import org.fleetloom.resource.storage.domain.StorageErrorCode;
import org.fleetloom.resource.storage.domain.StorageLocation;
import org.fleetloom.resource.storage.domain.StorageLocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Carrier types, carriers and manual corrections of where a carrier is (CAR-01, CAR-02, CAR-06). */
@Service
@Transactional
public class CarrierService {

    private final CarrierTypeRepository types;
    private final CarrierRepository carriers;
    private final StorageLocationRepository locations;
    private final GoodsRepository goods;
    private final Clock clock;

    public CarrierService(CarrierTypeRepository types, CarrierRepository carriers, StorageLocationRepository locations,
            GoodsRepository goods, Clock clock) {
        this.types = types;
        this.carriers = carriers;
        this.locations = locations;
        this.goods = goods;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ goods (CAR-05)

    @Transactional(readOnly = true)
    public List<Goods> goods(String code) {
        require(code);
        return goods.find(code);
    }

    /** Replaces what is recorded on the carrier; an empty list means it is empty. */
    public List<Goods> replaceGoods(String code, List<Goods> lines) {
        require(code);
        goods.replace(code, lines);
        return goods.find(code);
    }

    /** Where goods are: carriers holding a SKU, with the carrier's current place. */
    @Transactional(readOnly = true)
    public List<StockedView> findGoods(String skuPrefix, int limit) {
        return goods.findBySku(skuPrefix, limit).stream().map(s -> {
            Carrier c = carriers.findByCode(s.carrierCode()).orElse(null);
            return new StockedView(s.carrierCode(), c == null ? null : c.getLocationType(),
                    c == null ? null : c.getLocationRef(), s.goods());
        }).toList();
    }

    public record StockedView(String carrierCode, CarrierLocationType locationType, String locationRef, Goods goods) {
    }

    // ------------------------------------------------------------------ types

    public CarrierTypeView createType(String code, String name, CarrierCategory category, double length, double width,
            double height, double maxLoad, Set<String> compatibleVehicleTypes) {
        if (types.findByCode(code).isPresent()) {
            throw new BizException(CarrierErrorCode.CARRIER_TYPE_CODE_DUPLICATE, "carrier type %s already exists".formatted(code));
        }
        Instant now = clock.instant();
        return CarrierTypeView.of(types.save(new CarrierType(null, code, name, category, length, width, height, maxLoad,
                compatibleVehicleTypes == null ? Set.of() : compatibleVehicleTypes, now, now)));
    }

    public CarrierTypeView updateType(String code, String name, CarrierCategory category, double length, double width,
            double height, double maxLoad, Set<String> compatibleVehicleTypes) {
        CarrierType old = requireType(code);
        return CarrierTypeView.of(types.save(new CarrierType(old.id(), code, name, category, length, width, height, maxLoad,
                compatibleVehicleTypes == null ? Set.of() : compatibleVehicleTypes, old.createdAt(), clock.instant())));
    }

    public void deleteType(String code) {
        CarrierType type = requireType(code);
        if (carriers.existsOfType(code)) {
            throw new BizException(CarrierErrorCode.CARRIER_TYPE_IN_USE, "carriers of type %s still exist".formatted(code));
        }
        types.delete(type);
    }

    @Transactional(readOnly = true)
    public List<CarrierTypeView> listTypes() {
        return types.findAll().stream().map(CarrierTypeView::of).toList();
    }

    // ------------------------------------------------------------------ carriers

    public CarrierView create(String code, String typeCode) {
        if (carriers.findByCode(code).isPresent()) {
            throw new BizException(CarrierErrorCode.CARRIER_CODE_DUPLICATE, "carrier %s already exists".formatted(code));
        }
        requireType(typeCode);
        Carrier carrier = Carrier.create(code, typeCode, clock.instant());
        carriers.insert(carrier);
        return CarrierView.of(carrier);
    }

    public void delete(String code) {
        Carrier carrier = require(code);
        if (carrier.getLocationType() != CarrierLocationType.NONE) {
            throw new BizException(CarrierErrorCode.CARRIER_STATE_INVALID,
                    "carrier %s is at %s %s; take it out first".formatted(code, carrier.getLocationType(), carrier.getLocationRef()));
        }
        carriers.delete(carrier);
    }

    @Transactional(readOnly = true)
    public CarrierView get(String code) {
        return CarrierView.of(require(code));
    }

    @Transactional(readOnly = true)
    public List<CarrierView> list() {
        return carriers.findAll().stream().map(CarrierView::of).toList();
    }

    public CarrierView disable(String code) {
        Carrier carrier = require(code);
        carrier.disable(clock.instant());
        carriers.update(carrier);
        return CarrierView.of(carrier);
    }

    public CarrierView enable(String code) {
        Carrier carrier = require(code);
        carrier.enable(clock.instant());
        carriers.update(carrier);
        return CarrierView.of(carrier);
    }

    /** Records that someone put the carrier into a location by hand. */
    public CarrierView placeManually(String code, String locationCode) {
        Carrier carrier = require(code);
        StorageLocation location = requireLocation(locationCode);
        if (!location.accepts(carrier.getTypeCode())) {
            throw new BizException(StorageErrorCode.CARRIER_TYPE_NOT_ALLOWED,
                    "location %s does not accept carrier type %s".formatted(locationCode, carrier.getTypeCode()));
        }
        Instant now = clock.instant();
        location.place(code, now);
        carrier.placeIn(locationCode, now);
        locations.update(location);
        carriers.update(carrier);
        carriers.record(new CarrierMovement(code, CarrierLocationType.NONE, null, CarrierLocationType.STORAGE, locationCode, null, now));
        return CarrierView.of(carrier);
    }

    /** Records that someone took the carrier out of its location by hand. */
    public CarrierView takeOutManually(String code) {
        Carrier carrier = require(code);
        if (carrier.getStatus() == CarrierStatus.IN_TRANSIT || carrier.getLocationType() != CarrierLocationType.STORAGE) {
            throw new BizException(CarrierErrorCode.CARRIER_STATE_INVALID, "carrier %s is not in a storage location".formatted(code));
        }
        String from = carrier.getLocationRef();
        StorageLocation location = requireLocation(from);
        Instant now = clock.instant();
        location.remove(code, now);
        carrier.takeOut(now);
        locations.update(location);
        carriers.update(carrier);
        carriers.record(new CarrierMovement(code, CarrierLocationType.STORAGE, from, CarrierLocationType.NONE, null, null, now));
        return CarrierView.of(carrier);
    }

    @Transactional(readOnly = true)
    public List<CarrierMovement> movements(String code, int limit) {
        require(code);
        return carriers.movements(code, limit);
    }

    private CarrierType requireType(String code) {
        return types.findByCode(code).orElseThrow(() ->
                new BizException(CarrierErrorCode.CARRIER_TYPE_NOT_FOUND, "carrier type %s not found".formatted(code)));
    }

    private Carrier require(String code) {
        return carriers.findByCode(code).orElseThrow(() ->
                new BizException(CarrierErrorCode.CARRIER_NOT_FOUND, "carrier %s not found".formatted(code)));
    }

    private StorageLocation requireLocation(String code) {
        return locations.findByCode(code).orElseThrow(() ->
                new BizException(StorageErrorCode.LOCATION_NOT_FOUND, "location %s not found".formatted(code)));
    }
}
