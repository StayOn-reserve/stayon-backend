package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.repository.ReservationSiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.stayon.stayon_backend.service.ReservationSiteLinkResult.Status.*;

@Service
@RequiredArgsConstructor
public class ReservationSiteLinkService {
    private final ReservationSiteRepository reservations;
    private final ReservationSiteResolver resolver;

    // No managed Reservation is mutated or flushed by this operation.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReservationSiteLinkResult link(Long reservationPk) {
        if (reservationPk == null) throw new IllegalArgumentException("예약 내부 PK는 필수입니다.");
        var snapshot = reservations.findSnapshot(reservationPk);
        if (snapshot.isEmpty()) return new ReservationSiteLinkResult(RESERVATION_NOT_FOUND, null);
        var resolution = resolver.resolve(snapshot.get());
        if (resolution.status() != LINKED) return resolution;
        if (reservations.linkIfUnchanged(snapshot.get(), resolution.siteId()) == 1) return resolution;
        return new ReservationSiteLinkResult(CONCURRENT_UPDATE, null);
    }
}
