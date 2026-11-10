package com.stayon.stayon_backend.repository;

import com.stayon.stayon_backend.entity.Site;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteRepository extends JpaRepository<Site, Long> {
}
