package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

@Entity
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long userId;

    @Getter
    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    @Getter
    private String name;

    @Column(unique = true)
    private String businessNumber;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Getter
    private Role role;

    @Builder
    public User(
            String email,
            String password,
            String name,
            String businessNumber,
            Role role
    ) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.businessNumber = businessNumber;
        this.role = role;
    }
    public boolean checkPassword(
            String rawPassword,
            PasswordEncoder passwordEncoder
    ) {
        return passwordEncoder.matches(rawPassword, password);
    }
}