package org.fleetloom.resource.carrier.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.resource.carrier.application.CarrierService;
import org.fleetloom.resource.carrier.application.CarrierViews.CarrierTypeView;
import org.fleetloom.resource.carrier.application.CarrierViews.CarrierView;
import org.fleetloom.resource.carrier.domain.CarrierCategory;
import org.fleetloom.resource.carrier.domain.CarrierMovement;
import org.fleetloom.resource.carrier.domain.Goods;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resource/v1")
@Validated
public class CarrierController {

    static final String CODE = "[A-Za-z0-9_.:-]{1,64}";

    private final CarrierService carriers;

    public CarrierController(CarrierService carriers) {
        this.carriers = carriers;
    }

    @GetMapping("/carrier-types")
    public ApiResponse<List<CarrierTypeView>> types() {
        return ApiResponse.ok(carriers.listTypes());
    }

    @PostMapping("/carrier-types")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CarrierTypeView> createType(@Valid @RequestBody TypeRequest r) {
        return ApiResponse.ok(carriers.createType(r.code(), r.name(), r.category(), r.length(), r.width(), r.height(),
                r.maxLoad(), r.compatibleVehicleTypes()));
    }

    @PutMapping("/carrier-types/{code}")
    public ApiResponse<CarrierTypeView> updateType(@PathVariable String code, @Valid @RequestBody TypeRequest r) {
        return ApiResponse.ok(carriers.updateType(code, r.name(), r.category(), r.length(), r.width(), r.height(),
                r.maxLoad(), r.compatibleVehicleTypes()));
    }

    @DeleteMapping("/carrier-types/{code}")
    public ApiResponse<Void> deleteType(@PathVariable String code) {
        carriers.deleteType(code);
        return ApiResponse.ok();
    }

    @GetMapping("/carriers")
    public ApiResponse<List<CarrierView>> list() {
        return ApiResponse.ok(carriers.list());
    }

    @GetMapping("/carriers/{code}")
    public ApiResponse<CarrierView> get(@PathVariable String code) {
        return ApiResponse.ok(carriers.get(code));
    }

    @PostMapping("/carriers")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CarrierView> create(@Valid @RequestBody CarrierRequest r) {
        return ApiResponse.ok(carriers.create(r.code(), r.typeCode()));
    }

    @DeleteMapping("/carriers/{code}")
    public ApiResponse<Void> delete(@PathVariable String code) {
        carriers.delete(code);
        return ApiResponse.ok();
    }

    @PostMapping("/carriers/{code}/disable")
    public ApiResponse<CarrierView> disable(@PathVariable String code) {
        return ApiResponse.ok(carriers.disable(code));
    }

    @PostMapping("/carriers/{code}/enable")
    public ApiResponse<CarrierView> enable(@PathVariable String code) {
        return ApiResponse.ok(carriers.enable(code));
    }

    /** Records a carrier put into a location by hand (CAR-02). */
    @PostMapping("/carriers/{code}/place")
    public ApiResponse<CarrierView> place(@PathVariable String code, @Valid @RequestBody PlaceRequest r) {
        return ApiResponse.ok(carriers.placeManually(code, r.locationCode()));
    }

    /** Records a carrier taken out of its location by hand (CAR-02). */
    @PostMapping("/carriers/{code}/take-out")
    public ApiResponse<CarrierView> takeOut(@PathVariable String code) {
        return ApiResponse.ok(carriers.takeOutManually(code));
    }

    @GetMapping("/carriers/{code}/goods")
    public ApiResponse<List<Goods>> goods(@PathVariable String code) {
        return ApiResponse.ok(carriers.goods(code));
    }

    /** Records what is on the carrier (CAR-05), replacing the previous list. */
    @PutMapping("/carriers/{code}/goods")
    public ApiResponse<List<Goods>> replaceGoods(@PathVariable String code,
            @RequestBody @Size(max = 200) List<@Valid GoodsLine> lines) {
        return ApiResponse.ok(carriers.replaceGoods(code, lines.stream()
                .map(l -> new Goods(l.sku().trim(), l.name(), l.quantity(), l.unit(), l.batchNo())).toList()));
    }

    /** Which carriers hold goods with this SKU (prefix), and where they are. */
    @GetMapping("/goods")
    public ApiResponse<List<CarrierService.StockedView>> findGoods(@RequestParam @NotBlank String sku,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return ApiResponse.ok(carriers.findGoods(sku.trim(), limit));
    }

    public record GoodsLine(@NotBlank @Size(max = 64) String sku, @Size(max = 128) String name,
            @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal quantity, @Size(max = 16) String unit,
            @Size(max = 64) String batchNo) {
    }

    @GetMapping("/carriers/{code}/movements")
    public ApiResponse<List<CarrierMovement>> movements(@PathVariable String code,
            @RequestParam(defaultValue = "50") @Min(1) @Max(1000) int limit) {
        return ApiResponse.ok(carriers.movements(code, limit));
    }

    public record TypeRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank String name,
            @NotNull CarrierCategory category, @Positive double length, @Positive double width, @Positive double height,
            @Positive double maxLoad, Set<String> compatibleVehicleTypes) {
    }

    public record CarrierRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank String typeCode) {
    }

    public record PlaceRequest(@NotBlank String locationCode) {
    }
}
