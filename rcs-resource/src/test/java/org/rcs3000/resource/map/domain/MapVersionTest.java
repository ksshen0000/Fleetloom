package org.rcs3000.resource.map.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.rcs3000.common.error.BizException;

class MapVersionTest {

    private static final Instant T0 = Instant.parse("2026-10-18T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    private final RcsMap map = new RcsMap(1L, "WH1", "Warehouse 1", null, null, T0, T0);

    @Test
    void newDraftStartsAtRevisionZero() {
        MapVersion draft = MapVersion.newDraft(1L, 1, MapContent.empty(), T0);

        assertThat(draft.getStatus()).isEqualTo(MapVersionStatus.DRAFT);
        assertThat(draft.getRevision()).isZero();
    }

    @Test
    void replacingContentIncrementsRevision() {
        MapVersion draft = MapVersion.newDraft(1L, 1, MapContent.empty(), T0);

        draft.replaceContent(MapFixtures.validSquare(), 0, T1);

        assertThat(draft.getRevision()).isEqualTo(1);
        assertThat(draft.getContent()).isEqualTo(MapFixtures.validSquare());
        assertThat(draft.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void staleRevisionIsRejected() {
        MapVersion draft = MapVersion.newDraft(1L, 1, MapContent.empty(), T0);
        draft.replaceContent(MapFixtures.validSquare(), 0, T1);

        assertThatThrownBy(() -> draft.replaceContent(MapContent.empty(), 0, T1))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(MapErrorCode.MAP_REVISION_CONFLICT);
        assertThat(draft.getContent()).isEqualTo(MapFixtures.validSquare());
    }

    @Test
    void publishingActivatesTheVersionOnItsMap() {
        MapVersion draft = MapVersion.newDraft(1L, 3, MapFixtures.validSquare(), T0);

        draft.publish(map, "alice", T1);

        assertThat(draft.getStatus()).isEqualTo(MapVersionStatus.PUBLISHED);
        assertThat(draft.getPublishedBy()).isEqualTo("alice");
        assertThat(draft.getPublishedAt()).isEqualTo(T1);
        assertThat(map.getActiveVersionNo()).isEqualTo(3);
        assertThat(map.hasBeenPublished()).isTrue();
    }

    @Test
    void publishedVersionIsImmutable() {
        MapVersion version = MapVersion.newDraft(1L, 1, MapFixtures.validSquare(), T0);
        version.publish(map, "alice", T1);

        assertThatThrownBy(() -> version.replaceContent(MapContent.empty(), version.getRevision(), T1))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(MapErrorCode.MAP_VERSION_NOT_DRAFT);
        assertThatThrownBy(() -> version.publish(map, "bob", T1)).isInstanceOf(BizException.class);
    }

    @Test
    void onlyPublishedVersionCanBeArchived() {
        MapVersion draft = MapVersion.newDraft(1L, 1, MapContent.empty(), T0);

        assertThatThrownBy(() -> draft.archive(T1)).isInstanceOf(IllegalStateException.class);

        draft.publish(map, "alice", T1);
        draft.archive(T1);
        assertThat(draft.getStatus()).isEqualTo(MapVersionStatus.ARCHIVED);
    }

    @Test
    void cannotPublishVersionOfAnotherMap() {
        MapVersion foreign = MapVersion.newDraft(2L, 1, MapContent.empty(), T0);

        assertThatThrownBy(() -> foreign.publish(map, "alice", T1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(map.getActiveVersionNo()).isNull();
    }
}
