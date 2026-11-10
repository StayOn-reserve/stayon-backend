package com.stayon.stayon_backend.repository;

import com.stayon.stayon_backend.entity.Platform;
import com.stayon.stayon_backend.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    Optional<Reservation> findByPlatformAndReservationId(Platform platform, String reservationId);

    @Query("select r from Reservation r left join fetch r.site where r.id = :reservationId")
    Optional<Reservation> findForCustomerAccessById(@Param("reservationId") Long reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query("select r from Reservation r left join fetch r.site where r.id = :reservationId")
    Optional<Reservation> findForCustomerOrderForUpdate(@Param("reservationId") Long reservationId);
}
