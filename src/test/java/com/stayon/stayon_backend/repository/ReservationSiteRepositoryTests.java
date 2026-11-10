package com.stayon.stayon_backend.repository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReservationSiteRepositoryTests {
    @Test
    void updateOnlyAssignsSiteAndGuardsObservedIdentity() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var repository = new ReservationSiteRepository(jdbc);
        var snapshot = new ReservationSiteRepository.Snapshot(7L, "CAMFIT", "external-1", "30", null, "RESERVED", "SUCCESS");
        repository.linkIfUnchanged(snapshot, 20L);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(20L), eq(7L), eq("CAMFIT"), eq("external-1"), eq("30"));
        String statement = sql.getValue();
        assertEquals("site_id = ?", statement.split("SET")[1].split("WHERE")[0].trim());
        assertTrue(statement.contains("site_id IS NULL"));
        assertTrue(statement.contains("BINARY event_type = BINARY 'RESERVED'"));
        assertTrue(statement.contains("BINARY sync_status = BINARY 'SUCCESS'"));
        assertTrue(statement.contains("BINARY platform = BINARY ?"));
        assertTrue(statement.contains("BINARY external_site_id = BINARY ?"));
        assertTrue(statement.contains("CAST(accommodation_item_id AS CHAR)"));
        verifyNoMoreInteractions(jdbc);
    }
}
