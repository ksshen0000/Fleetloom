package org.rcs3000.contracts.map;

import java.util.List;

/** Complete, read-only topology of one map version. Consumed by traffic, fleet and simulator. */
public record MapTopologyDto(
        String mapCode,
        int versionNo,
        List<PointDto> points,
        List<PathDto> paths,
        List<AreaDto> areas) {
}
