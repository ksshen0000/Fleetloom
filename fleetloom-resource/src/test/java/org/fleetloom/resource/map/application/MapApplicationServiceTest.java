package org.fleetloom.resource.map.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.fleetloom.common.error.BizException;
import org.fleetloom.contracts.map.MapPublishedEvent;
import org.fleetloom.contracts.map.MapTopologyDto;
import org.fleetloom.resource.map.domain.MapContent;
import org.fleetloom.resource.map.domain.MapErrorCode;
import org.fleetloom.resource.map.domain.MapFixtures;
import org.fleetloom.resource.map.domain.MapVersionStatus;
import org.fleetloom.resource.map.domain.MapVersionSummary;
import org.fleetloom.resource.map.domain.ValidationReport;

class MapApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    private InMemoryMapRepositories.RecordingEvents events;
    private MapApplicationService service;

    @BeforeEach
    void setUp() {
        events = new InMemoryMapRepositories.RecordingEvents();
        service = new MapApplicationService(new InMemoryMapRepositories.Maps(), new InMemoryMapRepositories.Versions(),
                events, Clock.fixed(NOW, ZoneOffset.UTC));
        service.createMap("WH1", "Warehouse 1", "main hall");
    }

    @Test
    void createAndReadMap() {
        MapView map = service.getMap("WH1");

        assertThat(map.name()).isEqualTo("Warehouse 1");
        assertThat(map.activeVersionNo()).isNull();
        assertThat(map.createdAt()).isEqualTo(NOW);
        assertThat(service.listMaps()).extracting(MapView::code).containsExactly("WH1");
    }

    @Test
    void duplicateMapCodeIsRejected() {
        assertError(() -> service.createMap("WH1", "Again", null), MapErrorCode.MAP_CODE_DUPLICATE);
    }

    @Test
    void unknownMapIsNotFound() {
        assertError(() -> service.getMap("NOPE"), MapErrorCode.MAP_NOT_FOUND);
    }

    @Test
    void updateMapChangesNameAndDescription() {
        service.updateMap("WH1", "Hall A", null);

        assertThat(service.getMap("WH1")).extracting(MapView::name, MapView::description).containsExactly("Hall A", null);
    }

    @Test
    void firstDraftIsVersionOneAndEmpty() {
        MapVersionView draft = service.createDraft("WH1", null);

        assertThat(draft.versionNo()).isEqualTo(1);
        assertThat(draft.status()).isEqualTo(MapVersionStatus.DRAFT);
        assertThat(draft.points()).isEmpty();
    }

    @Test
    void onlyOneDraftAtATime() {
        service.createDraft("WH1", null);

        assertError(() -> service.createDraft("WH1", null), MapErrorCode.MAP_DRAFT_ALREADY_EXISTS);
    }

    @Test
    void saveDraftReturnsNewRevisionAndValidation() {
        service.createDraft("WH1", null);

        SaveDraftResult result = service.saveDraftContent("WH1", 1, MapFixtures.validSquare(), 0);

        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.validation().issues()).isEmpty();
        assertThat(service.getVersion("WH1", 1).points()).hasSize(5);
    }

    @Test
    void saveWithStaleRevisionIsRejected() {
        service.createDraft("WH1", null);
        service.saveDraftContent("WH1", 1, MapFixtures.validSquare(), 0);

        assertError(() -> service.saveDraftContent("WH1", 1, MapContent.empty(), 0), MapErrorCode.MAP_REVISION_CONFLICT);
        assertThat(service.getVersion("WH1", 1).points()).hasSize(5);
    }

    @Test
    void publishActivatesVersionAndEmitsEvent() {
        publishSquare();

        assertThat(service.getMap("WH1").activeVersionNo()).isEqualTo(1);
        assertThat(service.getVersion("WH1", 1).status()).isEqualTo(MapVersionStatus.PUBLISHED);
        assertThat(service.getVersion("WH1", 1).publishedBy()).isEqualTo("alice");
        assertThat(events.published).containsExactly(new MapPublishedEvent("WH1", 1, NOW));
    }

    @Test
    void publishWithValidationErrorsIsRejectedAndChangesNothing() {
        service.createDraft("WH1", null);

        assertThatThrownBy(() -> service.publish("WH1", 1, "alice"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(MapErrorCode.MAP_VALIDATION_FAILED);
                    assertThat(((ValidationReport) e.getDetails()).hasErrors()).isTrue();
                });
        assertThat(service.getMap("WH1").activeVersionNo()).isNull();
        assertThat(events.published).isEmpty();
    }

    @Test
    void publishedVersionCannotBeEditedOrDeleted() {
        publishSquare();

        assertError(() -> service.saveDraftContent("WH1", 1, MapContent.empty(), 1), MapErrorCode.MAP_VERSION_NOT_DRAFT);
        assertError(() -> service.deleteDraft("WH1", 1), MapErrorCode.MAP_VERSION_NOT_DRAFT);
    }

    @Test
    void publishingNextVersionArchivesThePreviousOne() {
        publishSquare();
        MapVersionView draft = service.createDraft("WH1", 1);
        assertThat(draft.versionNo()).isEqualTo(2);
        assertThat(draft.points()).hasSize(5);

        service.publish("WH1", 2, "bob");

        assertThat(service.listVersions("WH1"))
                .extracting(MapVersionSummary::versionNo, MapVersionSummary::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, MapVersionStatus.PUBLISHED),
                        org.assertj.core.groups.Tuple.tuple(1, MapVersionStatus.ARCHIVED));
        assertThat(service.getMap("WH1").activeVersionNo()).isEqualTo(2);
    }

    @Test
    void deletingDraftAllowsANewOne() {
        service.createDraft("WH1", null);
        service.deleteDraft("WH1", 1);

        assertThat(service.createDraft("WH1", null).versionNo()).isEqualTo(1);
    }

    @Test
    void publishedMapCannotBeDeleted() {
        publishSquare();

        assertError(() -> service.deleteMap("WH1"), MapErrorCode.MAP_IN_USE);
    }

    @Test
    void unpublishedMapCanBeDeleted() {
        service.deleteMap("WH1");

        assertError(() -> service.getMap("WH1"), MapErrorCode.MAP_NOT_FOUND);
    }

    @Test
    void activeTopologyContainsPathLengths() {
        publishSquare();

        MapTopologyDto topology = service.getActiveTopology("WH1");

        assertThat(topology.versionNo()).isEqualTo(1);
        assertThat(topology.points()).hasSize(5);
        assertThat(topology.paths()).filteredOn(p -> p.code().equals("AB")).singleElement()
                .satisfies(p -> assertThat(p.length()).isEqualTo(10.0));
        assertThat(topology.paths()).filteredOn(p -> p.code().equals("AE")).singleElement()
                .satisfies(p -> assertThat(p.length()).isEqualTo(5.0));
    }

    @Test
    void activeTopologyRequiresPublishedVersion() {
        service.createDraft("WH1", null);

        assertError(() -> service.getActiveTopology("WH1"), MapErrorCode.MAP_NO_ACTIVE_VERSION);
    }

    @Test
    void unknownVersionIsNotFound() {
        assertError(() -> service.getVersion("WH1", 7), MapErrorCode.MAP_VERSION_NOT_FOUND);
        assertError(() -> service.createDraft("WH1", 7), MapErrorCode.MAP_VERSION_NOT_FOUND);
    }

    @Test
    void validateReportsWithoutChangingState() {
        service.createDraft("WH1", null);

        assertThat(service.validate("WH1", 1).errors()).extracting(i -> i.rule()).containsExactly("EMPTY_MAP");
        assertThat(service.getVersion("WH1", 1).status()).isEqualTo(MapVersionStatus.DRAFT);
    }

    private void publishSquare() {
        service.createDraft("WH1", null);
        service.saveDraftContent("WH1", 1, MapFixtures.validSquare(), 0);
        service.publish("WH1", 1, "alice");
    }

    private static void assertError(ThrowingCallable call, MapErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void listVersionsIsNewestFirst() {
        publishSquare();
        service.createDraft("WH1", 1);

        List<Integer> numbers = service.listVersions("WH1").stream().map(MapVersionSummary::versionNo).toList();

        assertThat(numbers).containsExactly(2, 1);
    }
}
