package com.stayon.stayon_backend.repository;

import com.stayon.stayon_backend.entity.PaymentAttempt;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, String> {
    interface Route { Long getReservationId(); Long getOrderId(); }
    @Query("select a.serviceOrder.reservation.id as reservationId, a.serviceOrder.id as orderId from PaymentAttempt a where a.id=:id")
    Optional<Route> findRoute(@Param("id") String id);
    Optional<PaymentAttempt> findByServiceOrder_IdAndIdempotencyKeyHash(Long orderId, String keyHash);
    Optional<PaymentAttempt> findByIdAndServiceOrder_Reservation_Id(String id, Long reservationId);
    Page<PaymentAttempt> findByServiceOrder_IdAndServiceOrder_Reservation_Id(Long orderId, Long reservationId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query("select a from PaymentAttempt a where a.id=:id and a.serviceOrder.id=:orderId")
    Optional<PaymentAttempt> lockById(@Param("id") String id, @Param("orderId") Long orderId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query("select a from PaymentAttempt a where a.activeOrderId=:orderId")
    Optional<PaymentAttempt> lockActive(@Param("orderId") Long orderId);
    @Query("select a.id from PaymentAttempt a where a.status=:status order by a.updatedAt,a.id")
    List<String> findWork(@Param("status") PaymentAttempt.Status status, Pageable pageable);
    @Query("select a.id from PaymentAttempt a where a.status=:status and a.cancelClaim is null and a.cancelUnknown=false order by a.updatedAt,a.id")
    List<String> findUnclaimedCancellations(@Param("status") PaymentAttempt.Status status, Pageable pageable);
    @Query("""
        select a.id from PaymentAttempt a where a.status in :statuses
        and a.recoveryAttempts < :maximum
        and (a.recoveryNextAt is null or a.recoveryNextAt <= :now)
        and (a.recoveryLeaseUntil is null or a.recoveryLeaseUntil <= :now)
        and (a.status <> :approving or a.approvalStartedAt <= :cutoff)
        and (a.status <> :cancelPending or a.cancelClaim is null or a.cancelStartedAt <= :cutoff)
        order by coalesce(a.recoveryNextAt,a.createdAt),a.id
        """)
    List<String> findRecoveryDue(@Param("statuses") java.util.Set<PaymentAttempt.Status> statuses,
            @Param("approving") PaymentAttempt.Status approving, @Param("cancelPending") PaymentAttempt.Status cancelPending,
            @Param("maximum") int maximum, @Param("now") java.time.Instant now, @Param("cutoff") java.time.Instant cutoff, Pageable pageable);
}
