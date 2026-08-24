package com.stayon.stayon_backend.repository;

import com.stayon.stayon_backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByBusinessNumber(String businessNumber);
    boolean existsByEmail(String email);
}
