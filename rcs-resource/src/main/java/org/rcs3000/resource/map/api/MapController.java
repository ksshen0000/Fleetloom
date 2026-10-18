package org.rcs3000.resource.map.api;

import java.util.List;

import jakarta.validation.Valid;
import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.contracts.map.MapTopologyDto;
import org.rcs3000.resource.map.application.MapApplicationService;
import org.rcs3000.resource.map.application.MapDtoMapper;
import org.rcs3000.resource.map.application.MapVersionView;
import org.rcs3000.resource.map.application.MapView;
import org.rcs3000.resource.map.application.SaveDraftResult;
import org.rcs3000.resource.map.domain.MapVersionSummary;
import org.rcs3000.resource.map.domain.ValidationReport;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resource/v1/maps")
public class MapController {

    /** Set by the gateway from the authenticated JWT. */
    static final String USER_HEADER = "X-User-Name";

    private final MapApplicationService service;

    public MapController(MapApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MapView> create(@Valid @RequestBody MapRequests.CreateMap request) {
        return ApiResponse.ok(service.createMap(request.code(), request.name(), request.description()));
    }

    @GetMapping
    public ApiResponse<List<MapView>> list() {
        return ApiResponse.ok(service.listMaps());
    }

    @GetMapping("/{code}")
    public ApiResponse<MapView> get(@PathVariable String code) {
        return ApiResponse.ok(service.getMap(code));
    }

    @PutMapping("/{code}")
    public ApiResponse<MapView> update(@PathVariable String code, @Valid @RequestBody MapRequests.UpdateMap request) {
        return ApiResponse.ok(service.updateMap(code, request.name(), request.description()));
    }

    @DeleteMapping("/{code}")
    public ApiResponse<Void> delete(@PathVariable String code) {
        service.deleteMap(code);
        return ApiResponse.ok();
    }

    @PostMapping("/{code}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MapVersionView> createDraft(@PathVariable String code,
            @RequestBody(required = false) MapRequests.CreateDraft request) {
        Integer copyFrom = request == null ? null : request.copyFromVersionNo();
        return ApiResponse.ok(service.createDraft(code, copyFrom));
    }

    @GetMapping("/{code}/versions")
    public ApiResponse<List<MapVersionSummary>> listVersions(@PathVariable String code) {
        return ApiResponse.ok(service.listVersions(code));
    }

    @GetMapping("/{code}/versions/{versionNo}")
    public ApiResponse<MapVersionView> getVersion(@PathVariable String code, @PathVariable int versionNo) {
        return ApiResponse.ok(service.getVersion(code, versionNo));
    }

    @PutMapping("/{code}/versions/{versionNo}/content")
    public ApiResponse<SaveDraftResult> saveContent(@PathVariable String code, @PathVariable int versionNo,
            @Valid @RequestBody MapRequests.SaveContent request) {
        return ApiResponse.ok(service.saveDraftContent(code, versionNo,
                MapDtoMapper.toContent(request.points(), request.paths(), request.areas()), request.revision()));
    }

    @DeleteMapping("/{code}/versions/{versionNo}")
    public ApiResponse<Void> deleteDraft(@PathVariable String code, @PathVariable int versionNo) {
        service.deleteDraft(code, versionNo);
        return ApiResponse.ok();
    }

    @PostMapping("/{code}/versions/{versionNo}/validate")
    public ApiResponse<ValidationReport> validate(@PathVariable String code, @PathVariable int versionNo) {
        return ApiResponse.ok(service.validate(code, versionNo));
    }

    @PostMapping("/{code}/versions/{versionNo}/publish")
    public ApiResponse<ValidationReport> publish(@PathVariable String code, @PathVariable int versionNo,
            @RequestHeader(value = USER_HEADER, defaultValue = "system") String user) {
        return ApiResponse.ok(service.publish(code, versionNo, user));
    }

    @GetMapping("/{code}/active")
    public ApiResponse<MapTopologyDto> activeTopology(@PathVariable String code) {
        return ApiResponse.ok(service.getActiveTopology(code));
    }
}
