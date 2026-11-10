package com.stayon.stayon_backend.repository;

import com.stayon.stayon_backend.entity.ServiceOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.List;

public interface ServiceOrderRepository extends JpaRepository<ServiceOrder, Long> {
    @EntityGraph(attributePaths = "reservation")
    Optional<ServiceOrder> findByIdAndReservation_Id(Long id, Long reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query("select o from ServiceOrder o where o.id = :orderId and o.reservation.id = :reservationId")
    Optional<ServiceOrder> findForCustomerCancellationForUpdate(@Param("orderId") Long orderId,
                                                              @Param("reservationId") Long reservationId);

    Optional<ServiceOrder> findByReservation_IdAndIdempotencyKeyHash(Long reservationId, String idempotencyKeyHash);

    Page<ServiceOrder> findByReservation_Id(Long reservationId, Pageable pageable);

    @Query("select distinct o from ServiceOrder o left join fetch o.items where o.reservation.id = :reservationId and o.id in :orderIds")
    List<ServiceOrder> findCustomerOrdersWithItems(@Param("reservationId") Long reservationId,
                                                 @Param("orderIds") List<Long> orderIds);
}
