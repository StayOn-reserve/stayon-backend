package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.repository.ExternalSiteRepository;
import com.stayon.stayon_backend.repository.ExternalSiteRepository.Mapping;
import com.stayon.stayon_backend.repository.ReservationSiteRepository;
import com.stayon.stayon_backend.repository.ReservationSiteRepository.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static com.stayon.stayon_backend.service.ReservationSiteLinkResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationSiteLinkTests {
    private final ExternalSiteRepository mappings = mock(ExternalSiteRepository.class);
    private final ReservationSiteRepository reservations = mock(ReservationSiteRepository.class);
    private final ReservationSiteResolver resolver = new ReservationSiteResolver(mappings, true, true);
    private final ReservationSiteLinkService service = new ReservationSiteLinkService(reservations, resolver);

    private Snapshot snapshot(Long siteId) {
        return new Snapshot(1L, "CAMFIT", "external-1", "30", siteId, "RESERVED", "SUCCESS");
    }

    private Mapping mapping(boolean active, Long siteId, Long itemId) {
        return new Mapping("TRIP_ELEVEN", "external-1", active, siteId, itemId);
    }

    private void prepare(Snapshot snapshot, List<Mapping> candidates) {
        when(reservations.findSnapshot(1L)).thenReturn(Optional.of(snapshot));
        when(mappings.findCandidatesForUpdate("TRIP_ELEVEN", "external-1")).thenReturn(candidates);
    }

    @Test
    void linksUniqueActiveMapping() {
        var observed = snapshot(null);
        prepare(observed, List.of(mapping(true, 20L, 30L)));
        when(reservations.linkIfUnchanged(observed, 20L)).thenReturn(1);
        assertEquals(new ReservationSiteLinkResult(LINKED, 20L), service.link(1L));
        verify(reservations).findSnapshot(1L);
        verify(reservations).linkIfUnchanged(observed, 20L);
        verifyNoMoreInteractions(reservations);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationSiteLinkResult.Status.class, names = {
            "UNMAPPED", "AMBIGUOUS", "INACTIVE", "SITE_NOT_FOUND",
            "ACCOMMODATION_ITEM_NOT_FOUND", "ACCOMMODATION_ITEM_MISMATCH"})
    void rejectsInvalidMappingsWithoutWriting(ReservationSiteLinkResult.Status expected) {
        List<Mapping> candidates = switch (expected) {
            case UNMAPPED -> List.of();
            case AMBIGUOUS -> List.of(mapping(true, 20L, 30L), mapping(false, 21L, 30L));
            case INACTIVE -> List.of(mapping(false, 20L, 30L));
            case SITE_NOT_FOUND -> List.of(mapping(true, null, null));
            case ACCOMMODATION_ITEM_NOT_FOUND -> List.of(mapping(true, 20L, null));
            case ACCOMMODATION_ITEM_MISMATCH -> List.of(mapping(true, 20L, 31L));
            default -> throw new AssertionError();
        };
        prepare(snapshot(null), candidates);
        assertEquals(expected, service.link(1L).status());
        verify(reservations, never()).linkIfUnchanged(any(), any());
    }

    @Test
    void existingSameSiteIsIdempotent() {
        prepare(snapshot(20L), List.of(mapping(true, 20L, 30L)));
        assertEquals(ALREADY_LINKED, service.link(1L).status());
        assertEquals(ALREADY_LINKED, service.link(1L).status());
        verify(reservations, never()).linkIfUnchanged(any(), any());
    }

    @Test
    void neverOverwritesAnotherSite() {
        prepare(snapshot(99L), List.of(mapping(true, 20L, 30L)));
        assertEquals(EXISTING_SITE_CONFLICT, service.link(1L).status());
        verify(reservations, never()).linkIfUnchanged(any(), any());
    }

    @Test
    void reportsConcurrentUpdateWithoutRetry() {
        var observed = snapshot(null);
        prepare(observed, List.of(mapping(true, 20L, 30L)));
        when(reservations.linkIfUnchanged(observed, 20L)).thenReturn(0);
        assertEquals(CONCURRENT_UPDATE, service.link(1L).status());
        verify(reservations, times(1)).linkIfUnchanged(observed, 20L);
    }

    @Test
    void separatesPlatformsWithTheSameExternalId() {
        when(mappings.findCandidatesForUpdate("CAMFIT", "same"))
                .thenReturn(List.of(new Mapping("CAMFIT", "same", true, 20L, 30L)));
        when(mappings.findCandidatesForUpdate("TRIP_ELEVEN", "same"))
                .thenReturn(List.of(new Mapping("TRIP_ELEVEN", "same", true, 21L, 30L)));
        assertEquals(21L, resolver.resolve(new Snapshot(1L, "CAMFIT", "same", "30", null, "RESERVED", "SUCCESS")).siteId());
        assertEquals(20L, resolver.resolve(new Snapshot(2L, "TRIP_ELEVEN", "same", "30", null, "RESERVED", "SUCCESS")).siteId());
    }

    @Test
    void failsClosedUntilItemIdMeaningIsConfirmed() {
        when(mappings.findCandidatesForUpdate("TRIP_ELEVEN", "external-1"))
                .thenReturn(List.of(mapping(true, 20L, 30L)));
        var unverifiedResolver = new ReservationSiteResolver(mappings, false, true);
        assertEquals(UNVERIFIED_ACCOMMODATION_ITEM_ID, unverifiedResolver.resolve(snapshot(null)).status());
    }

    @Test
    void nullItemIdUsesOnlyVerifiedExternalMapping() {
        when(mappings.findCandidatesForUpdate("TRIP_ELEVEN", "external-1"))
                .thenReturn(List.of(mapping(true, 20L, 30L)));
        assertEquals(LINKED, resolver.resolve(new Snapshot(1L, "CAMFIT", "external-1", null, null, "RESERVED", "SUCCESS")).status());
    }

    @Test
    void rejectsCollationOnlyMatch() {
        when(mappings.findCandidatesForUpdate("TRIP_ELEVEN", "external-1"))
                .thenReturn(List.of(new Mapping("TRIP_ELEVEN", "EXTERNAL-1", true, 20L, 30L)));
        assertEquals(EXTERNAL_KEY_MISMATCH, resolver.resolve(snapshot(null)).status());
    }

    @Test
    void missingReservationIsNotCreated() {
        when(reservations.findSnapshot(1L)).thenReturn(Optional.empty());
        assertEquals(RESERVATION_NOT_FOUND, service.link(1L).status());
        verifyNoInteractions(mappings);
    }

    @Test
    void cancelledReservationIsNeverLinked() {
        when(reservations.findSnapshot(1L)).thenReturn(Optional.of(
                new Snapshot(1L, "CAMFIT", "external-1", null, null, "CANCELLED", "SUCCESS")));
        assertEquals(RESERVATION_CANCELLED, service.link(1L).status());
        verifyNoInteractions(mappings);
        verify(reservations, never()).linkIfUnchanged(any(), any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PENDING", "FAILED", "UNKNOWN"})
    void incompleteSynchronizationIsNotLinked(String syncStatus) {
        when(reservations.findSnapshot(1L)).thenReturn(Optional.of(
                new Snapshot(1L, "CAMFIT", "external-1", null, null, "RESERVED", syncStatus)));
        assertEquals(RESERVATION_NOT_READY, service.link(1L).status());
        verifyNoInteractions(mappings);
        verify(reservations, never()).linkIfUnchanged(any(), any());
    }

    @Test
    void unknownHistoricalExternalIdMeaningBlocksLookup() {
        var unverifiedResolver = new ReservationSiteResolver(mappings, false, false);
        assertEquals(UNVERIFIED_EXTERNAL_SITE_ID, unverifiedResolver.resolve(snapshot(null)).status());
        verifyNoInteractions(mappings);
    }

    @Test
    void neverFallsBackToSourcePlatformOnMissingTarget() {
        when(mappings.findCandidatesForUpdate("TRIP_ELEVEN", "external-1")).thenReturn(List.of());
        assertEquals(UNMAPPED, resolver.resolve(snapshot(null)).status());
        verify(mappings, never()).findCandidatesForUpdate("CAMFIT", "external-1");
    }

    @Test
    void infrastructureFailuresAreNotMisreportedAsUnmapped() {
        when(reservations.findSnapshot(1L)).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> service.link(1L));
        verifyNoInteractions(mappings);
    }

    @Test
    void nullPrimaryKeyIsRejectedBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.link(null));
        verifyNoInteractions(reservations, mappings);
    }
}
