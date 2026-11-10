package com.stayon.stayon_backend.repository;

import com.stayon.stayon_backend.entity.AdditionalService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import java.util.List;
import java.util.Optional;

public interface AdditionalServiceRepository extends JpaRepository<AdditionalService, Long> {
    List<AdditionalService> findBySiteIdAndActiveTrueAndInputTypeOrderByIdAsc(
            Long siteId, AdditionalService.ServiceInputType inputType);
    List<AdditionalService> findBySiteIdOrderByIdAsc(Long siteId);
    Optional<AdditionalService> findByIdAndSiteId(Long id, Long siteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query("select s from AdditionalService s where s.id = :id and s.site.id = :siteId")
    Optional<AdditionalService> findForCustomerOrderForUpdate(@Param("id") Long id, @Param("siteId") Long siteId);
}
