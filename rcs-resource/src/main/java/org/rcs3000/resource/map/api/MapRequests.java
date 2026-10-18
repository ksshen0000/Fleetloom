package org.rcs3000.resource.map.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.rcs3000.contracts.map.AreaDto;
import org.rcs3000.contracts.map.PathDto;
import org.rcs3000.contracts.map.PointDto;

/** Request bodies of the map API. */
final class MapRequests {

    static final String CODE_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private MapRequests() {
    }

    record CreateMap(
            @NotBlank @Pattern(regexp = CODE_PATTERN, message = "letters, digits, '_' or '-', at most 64") String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 512) String description) {
    }

    record UpdateMap(@NotBlank @Size(max = 128) String name, @Size(max = 512) String description) {
    }

    /** @param copyFromVersionNo version to copy into the new draft; {@code null} starts empty */
    record CreateDraft(Integer copyFromVersionNo) {
    }

    /**
     * Full replacement of a draft's content.
     *
     * @param revision the revision the client's copy is based on (optimistic locking)
     */
    record SaveContent(@NotNull Integer revision, List<PointDto> points, List<PathDto> paths, List<AreaDto> areas) {
    }
}
