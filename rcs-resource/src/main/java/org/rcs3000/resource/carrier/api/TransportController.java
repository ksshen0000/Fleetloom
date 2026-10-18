package org.rcs3000.resource.carrier.api;

import java.util.List;

import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.contracts.storage.DroppedOffDto;
import org.rcs3000.contracts.storage.PickedUpDto;
import org.rcs3000.contracts.storage.ReleaseLocksDto;
import org.rcs3000.contracts.storage.TransportLockDto;
import org.rcs3000.contracts.storage.TransportLockRequestDto;
import org.rcs3000.resource.carrier.application.CarrierViews.CarrierView;
import org.rcs3000.resource.carrier.application.TransportService;
import org.rcs3000.resource.storage.application.StorageViews.LocationView;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal API for the dispatch service: the storage side of transport orders. */
@RestController
@RequestMapping("/api/resource/v1/transports")
public class TransportController {

    private final TransportService transports;

    public TransportController(TransportService transports) {
        this.transports = transports;
    }

    @PostMapping("/locks")
    public ApiResponse<TransportLockDto> lock(@RequestBody TransportLockRequestDto r) {
        return ApiResponse.ok(transports.lock(r.owner(), r.carrierCode(), r.targetLocation()));
    }

    @PostMapping("/picked-up")
    public ApiResponse<CarrierView> pickedUp(@RequestBody PickedUpDto r) {
        return ApiResponse.ok(transports.pickedUp(r.owner(), r.carrierCode(), r.vehicleCode()));
    }

    @PostMapping("/dropped-off")
    public ApiResponse<CarrierView> droppedOff(@RequestBody DroppedOffDto r) {
        return ApiResponse.ok(transports.droppedOff(r.owner(), r.carrierCode(), r.targetLocation()));
    }

    @PostMapping("/release")
    public ApiResponse<List<LocationView>> release(@RequestBody ReleaseLocksDto r) {
        return ApiResponse.ok(transports.release(r.owner(), r.locations()));
    }
}
