package com.stayon.stayon_backend.repository;

import com.stayon.stayon_backend.entity.PgWebhookInbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface PgWebhookInboxRepository extends JpaRepository<PgWebhookInbox, String> {
    Optional<PgWebhookInbox> findByProviderAndEventKeyHash(String provider, String hash);
    Optional<PgWebhookInbox> findByProviderAndMerchantIdAndEventKeyHash(String provider,String merchantId,String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PgWebhookInbox i where i.id=:id")
    Optional<PgWebhookInbox> lockById(@Param("id") String id);
    @Query("""
        select i.id from PgWebhookInbox i where i.status in :statuses
        and (i.nextAt is null or i.nextAt<=:now) and (i.leaseUntil is null or i.leaseUntil<=:now)
        order by coalesce(i.nextAt,i.receivedAt),i.receivedAt,i.id
        """)
    List<String> findDue(@Param("statuses") Set<PgWebhookInbox.Status> statuses, @Param("now") Instant now, Pageable pageable);
}
