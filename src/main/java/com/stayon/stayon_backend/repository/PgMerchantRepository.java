package com.stayon.stayon_backend.repository;

import com.stayon.stayon_backend.entity.PgMerchant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PgMerchantRepository extends JpaRepository<PgMerchant,String>{
    List<PgMerchant> findByOwner_UserIdAndProviderAndEnvironmentAndActiveTrue(Long ownerId,String provider,PgMerchant.Environment environment);
}
