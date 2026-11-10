package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;
import java.time.Instant;
import java.util.*;

@Entity
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
@Table(name="pg_merchant",uniqueConstraints={
        @UniqueConstraint(name="uk_merchant_provider_identity",columnNames={"provider","environment","external_id_hash"}),
        @UniqueConstraint(name="uk_merchant_active_owner",columnNames={"provider","environment","active_owner_id"}),
        @UniqueConstraint(name="uk_merchant_id_provider",columnNames={"id","provider"})})
@Check(name="ck_merchant_owner",constraints="(active = true and active_owner_id = owner_id and active_owner_id is not null) or (active = false and active_owner_id is null)")
public class PgMerchant {
    public enum Environment { SANDBOX, LIVE }
    @Id @Column(length=36) private String id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="owner_id",nullable=false,updatable=false) private User owner;
    @Column(nullable=false,length=32,updatable=false) private String provider;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16,updatable=false) private Environment environment;
    @Column(nullable=false,length=200,updatable=false) private String externalId;
    @Column(nullable=false,length=64,updatable=false) private String externalIdHash;
    @Column(nullable=false) private boolean active;
    private Long activeOwnerId;
    @ElementCollection @CollectionTable(name="pg_merchant_currency",joinColumns=@JoinColumn(name="merchant_id"),uniqueConstraints=@UniqueConstraint(name="uk_merchant_currency",columnNames={"merchant_id","currency"}))
    @Column(name="currency",nullable=false,length=3) private Set<String> currencies=new HashSet<>();
    @Column(nullable=false,length=200,updatable=false) private String secretReference;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    @Version private long version;
    public PgMerchant(User owner,String provider,Environment environment,String externalId,Set<String> currencies,String secretReference,Instant now){
        if(owner==null||owner.getUserId()==null||!PaymentAttempt.validProvider(provider)||environment==null||externalId==null||externalId.isBlank()||externalId.length()>200
                ||currencies==null||currencies.isEmpty()||currencies.stream().anyMatch(c->c==null||!c.matches("[A-Z]{3}"))
                ||secretReference==null||!secretReference.matches("[A-Za-z0-9/_:.-]{1,200}"))throw new IllegalArgumentException("Invalid merchant configuration");
        id=UUID.randomUUID().toString();this.owner=owner;this.provider=provider;this.environment=environment;this.externalId=externalId;
        externalIdHash=PaymentAttempt.digest(externalId);this.currencies=new HashSet<>(currencies);this.secretReference=secretReference;createdAt=now;setActive(true,now);
    }
    public Set<String> getCurrencies(){return Set.copyOf(currencies);}
    public void setActive(boolean active,Instant now){this.active=active;activeOwnerId=active?owner.getUserId():null;updatedAt=now;}
}
