package com.stayon.stayon_backend.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReservationSiteRepository {
    private final JdbcTemplate jdbcTemplate;

    public record Snapshot(Long id, String platform, String externalSiteId,
                           String accommodationItemId, Long siteId, String eventType, String syncStatus) {}

    public Optional<Snapshot> findSnapshot(Long id) {
        return jdbcTemplate.query("""
                SELECT id, platform, external_site_id, accommodation_item_id, site_id, event_type, sync_status
                FROM reservation WHERE id = ?
                """, (rs, row) -> new Snapshot(rs.getLong("id"), rs.getString("platform"),
                rs.getString("external_site_id"), rs.getString("accommodation_item_id"),
                rs.getObject("site_id", Long.class), rs.getString("event_type"),
                rs.getString("sync_status")), id).stream().findFirst();
    }

    // Only site_id is updated; MySQL <=> compares nullable observed values safely.
    public int linkIfUnchanged(Snapshot observed, Long siteId) {
        return jdbcTemplate.update("""
                UPDATE reservation SET site_id = ?
                WHERE id = ? AND site_id IS NULL
                  AND BINARY event_type = BINARY 'RESERVED'
                  AND BINARY sync_status = BINARY 'SUCCESS'
                  AND BINARY platform = BINARY ?
                  AND BINARY external_site_id = BINARY ?
                  AND BINARY CAST(accommodation_item_id AS CHAR) <=> BINARY ?
                """, siteId, observed.id(), observed.platform(), observed.externalSiteId(),
                observed.accommodationItemId());
    }
}
