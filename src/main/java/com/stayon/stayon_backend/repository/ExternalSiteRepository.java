package com.stayon.stayon_backend.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ExternalSiteRepository {
    private final JdbcTemplate jdbcTemplate;

    public record Mapping(String platform, String externalSiteId, boolean active,
                          Long siteId, Long accommodationItemId) {}

    // Lock mappings and parents in the link transaction. Inactive rows count as candidates.
    public List<Mapping> findCandidatesForUpdate(String platform, String externalSiteId) {
        return jdbcTemplate.query("""
                SELECT es.platform, es.external_site_id, es.active,
                       s.id AS site_id, ai.id AS accommodation_item_id
                FROM external_site es
                LEFT JOIN site s ON s.id = es.site_id
                LEFT JOIN accommodation_item ai ON ai.id = s.accommodation_item_id
                WHERE es.platform = ? AND es.external_site_id = ?
                ORDER BY es.id FOR UPDATE
                """, (rs, row) -> new Mapping(rs.getString("platform"),
                rs.getString("external_site_id"), rs.getBoolean("active"),
                rs.getObject("site_id", Long.class), rs.getObject("accommodation_item_id", Long.class)),
                platform, externalSiteId);
    }
}
