package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.Platform;
import com.stayon.stayon_backend.repository.ExternalSiteRepository;
import com.stayon.stayon_backend.repository.ReservationSiteRepository.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.stayon.stayon_backend.service.ReservationSiteLinkResult.Status.*;

@Service
public class ReservationSiteResolver {
    private final ExternalSiteRepository externalSites;
    private final boolean accommodationItemIdIsInternalPk;
    private final boolean externalSiteIdIsBlockTarget;

    public ReservationSiteResolver(ExternalSiteRepository externalSites,
            @Value("${reservation-site-link.accommodation-item-id-is-internal-pk:false}")
            boolean accommodationItemIdIsInternalPk,
            @Value("${reservation-site-link.external-site-id-is-block-target:false}")
            boolean externalSiteIdIsBlockTarget) {
        this.externalSites = externalSites;
        this.accommodationItemIdIsInternalPk = accommodationItemIdIsInternalPk;
        this.externalSiteIdIsBlockTarget = externalSiteIdIsBlockTarget;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationSiteLinkResult resolve(Snapshot reservation) {
        if ("CANCELLED".equals(reservation.eventType())) return result(RESERVATION_CANCELLED, null);
        if (!"RESERVED".equals(reservation.eventType()) || !"SUCCESS".equals(reservation.syncStatus())) {
            return result(RESERVATION_NOT_READY, null);
        }
        if (reservation.platform() == null || reservation.externalSiteId() == null
                || reservation.externalSiteId().isBlank()) return result(INVALID_EXTERNAL_KEY, null);
        Platform sourcePlatform;
        try {
            sourcePlatform = Platform.valueOf(reservation.platform());
        } catch (IllegalArgumentException e) {
            return result(INVALID_EXTERNAL_KEY, null);
        }
        // sms-webhook blockCamfit/blockTripEleven store the OTHER platform's target ID.
        // Historical rows must be confirmed before enabling this interpretation.
        if (!externalSiteIdIsBlockTarget) return result(UNVERIFIED_EXTERNAL_SITE_ID, null);
        Platform targetPlatform = switch (sourcePlatform) {
            case CAMFIT -> Platform.TRIP_ELEVEN;
            case TRIP_ELEVEN -> Platform.CAMFIT;
        };
        var candidates = externalSites.findCandidatesForUpdate(
                targetPlatform.name(), reservation.externalSiteId());
        if (candidates.isEmpty()) return result(UNMAPPED, null);
        if (candidates.size() != 1) return result(AMBIGUOUS, null);
        var mapping = candidates.getFirst();
        if (!targetPlatform.name().equals(mapping.platform())
                || !reservation.externalSiteId().equals(mapping.externalSiteId())) {
            return result(EXTERNAL_KEY_MISMATCH, null);
        }
        if (!mapping.active()) return result(INACTIVE, null);
        if (mapping.siteId() == null) return result(SITE_NOT_FOUND, null);
        if (mapping.accommodationItemId() == null) return result(ACCOMMODATION_ITEM_NOT_FOUND, null);
        if (reservation.accommodationItemId() != null) {
            if (!accommodationItemIdIsInternalPk) return result(UNVERIFIED_ACCOMMODATION_ITEM_ID, null);
            // Never coerce an external identifier into an internal primary key.
            if (!mapping.accommodationItemId().toString().equals(reservation.accommodationItemId())) {
                return result(ACCOMMODATION_ITEM_MISMATCH, null);
            }
        }
        if (reservation.siteId() != null) {
            return result(reservation.siteId().equals(mapping.siteId())
                    ? ALREADY_LINKED : EXISTING_SITE_CONFLICT, reservation.siteId());
        }
        return result(LINKED, mapping.siteId());
    }

    private ReservationSiteLinkResult result(ReservationSiteLinkResult.Status status, Long siteId) {
        return new ReservationSiteLinkResult(status, siteId);
    }
}
