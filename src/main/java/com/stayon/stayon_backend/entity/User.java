package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    @Getter
    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String businessNumber;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Builder
    private User(
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
}