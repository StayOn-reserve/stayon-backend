package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "external_site",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_site_platform",
                        columnNames = {"site_id", "platform"}
                ),
                @UniqueConstraint(
                        name = "uk_platform_external_id",
                        columnNames = {"platform", "external_site_id"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ExternalSite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Platform platform;

    @Column(name = "external_site_id", nullable = false, length = 100)
    private String externalSiteId;

    @Column(name = "external_site_name", nullable = false, length = 100)
    private String externalSiteName;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public ExternalSite(
            Platform platform,
            String externalSiteId,
            String externalSiteName
    ) {
        this.platform = platform;
        this.externalSiteId = externalSiteId;
        this.externalSiteName = externalSiteName;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}