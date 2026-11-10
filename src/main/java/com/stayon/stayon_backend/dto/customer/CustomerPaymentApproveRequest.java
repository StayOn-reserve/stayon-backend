package com.stayon.stayon_backend.dto.customer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.stayon.stayon_backend.service.PgAdapter;
import java.util.Map;

public record CustomerPaymentApproveRequest(BrowserProof authentication) {
    @JsonAnySetter public void reject(String name, Object value) { throw new IllegalArgumentException("Unsupported payment field"); }
    public PgAdapter.BrowserAuthentication proof() {
        return authentication == null ? PgAdapter.BrowserAuthentication.none()
                : new PgAdapter.BrowserAuthentication(authentication.version(), authentication.payload());
    }
    public record BrowserProof(String version, Map<String, String> payload) {
        @JsonAnySetter public void reject(String name, Object value) { throw new IllegalArgumentException("Unsupported authentication field"); }
        @Override public String toString() { return "BrowserProof[REDACTED]"; }
    }
}
