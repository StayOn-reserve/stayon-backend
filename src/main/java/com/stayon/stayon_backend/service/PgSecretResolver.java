package com.stayon.stayon_backend.service;

/** Trusted server-side implementation must enforce its own environment/owner/provider allowlist. No external resolver is bundled. */
public interface PgSecretResolver {
    default boolean testOnly(){return false;}
    PgSecret resolve(PgMerchantContext authorizedScope);
}
