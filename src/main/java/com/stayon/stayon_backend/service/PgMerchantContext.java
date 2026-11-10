package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.PgMerchant;

/** Internal immutable identity, never a customer DTO. No credential values. */
public record PgMerchantContext(String id,Long ownerId,String provider,PgMerchant.Environment environment,String externalId,String secretReference){
    public static PgMerchantContext from(PgMerchant m){return new PgMerchantContext(m.getId(),m.getOwner().getUserId(),m.getProvider(),m.getEnvironment(),m.getExternalId(),m.getSecretReference());}
    @Override public String toString(){return "PgMerchantContext[REDACTED]";}
}
