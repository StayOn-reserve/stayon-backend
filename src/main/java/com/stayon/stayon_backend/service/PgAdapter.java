package com.stayon.stayon_backend.service;

/** No credentials belong in these durable correlation contracts. DECLINED means definitive, terminal no-charge. */
public interface PgAdapter {
    /** Transient browser proof only. Never persist or log; each provider validates its own versioned contract. */
    final class BrowserAuthentication {
        private final String version;
        private final java.util.Map<String, String> payload;
        public BrowserAuthentication(String version, java.util.Map<String, String> payload) {
            if (version == null || !version.matches("[A-Za-z0-9._-]{1,32}") || payload == null || payload.size() > 16)
                throw new IllegalArgumentException("Invalid browser authentication");
            int length = 0;
            for (var entry : payload.entrySet()) {
                if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9._-]{1,64}") || entry.getValue() == null || entry.getValue().length() > 2048)
                    throw new IllegalArgumentException("Invalid browser authentication");
                length += entry.getValue().length();
            }
            if (length > 8192) throw new IllegalArgumentException("Invalid browser authentication");
            this.version = version; this.payload = java.util.Map.copyOf(payload);
        }
        public String version() { return version; }
        public java.util.Map<String, String> payload() { return payload; }
        public static BrowserAuthentication none() { return new BrowserAuthentication("NONE", java.util.Map.of()); }
        @Override public String toString() { return "BrowserAuthentication[REDACTED]"; }
    }
    String provider();
    default boolean testOnly() { return false; }
    default boolean supportsCancellation(){return true;}
    record Preparation(String tid,String userReference,long taxFreeAmount){}
    record Command(String reference, String provider, Long orderId, long amount, String currency,
                   String operationKey, String transactionId, PgMerchantContext merchant,Preparation preparation) { }
    enum ApprovalOutcome { APPROVED, DECLINED, UNKNOWN }
    record ApprovalResult(String reference, String provider, ApprovalOutcome outcome,
                          String transactionId, long amount, String currency, String pgMerchantId) {
        public ApprovalResult(String reference,String provider,ApprovalOutcome outcome,String transactionId,long amount,String currency){this(reference,provider,outcome,transactionId,amount,currency,null);}
    }
    enum CancelOutcome { CANCELED, UNKNOWN }
    record CancelResult(String reference, String provider, CancelOutcome outcome,
                        String transactionId, long amount, String currency, String pgMerchantId) {
        public CancelResult(String reference,String provider,CancelOutcome outcome,String transactionId,long amount,String currency){this(reference,provider,outcome,transactionId,amount,currency,null);}
    }
    default ApprovalResult approve(Command command,BrowserAuthentication authentication,PgSecret secret){throw new UnsupportedOperationException("Merchant-scoped approval is required");}
    default CancelResult cancel(Command command,PgSecret secret){throw new UnsupportedOperationException("Merchant-scoped cancellation is required");}
    default ApprovalResult lookupApproval(Command command,PgSecret secret){throw new UnsupportedOperationException("Merchant-scoped lookup is required");}
    default CancelResult lookupCancellation(Command command,PgSecret secret){throw new UnsupportedOperationException("Merchant-scoped lookup is required");}
    ApprovalResult approve(Command command);
    default void validateAuthentication(BrowserAuthentication authentication) {
        if (!authentication.version().equals("NONE") || !authentication.payload().isEmpty())
            throw new IllegalArgumentException("Browser authentication is not supported by this adapter");
    }
    default ApprovalResult approve(Command command, BrowserAuthentication authentication) {
        validateAuthentication(authentication); return approve(command);
    }
    CancelResult cancel(Command command);
    default ApprovalResult lookupApproval(Command command) { throw new UnsupportedOperationException("Provider lookup not implemented"); }
    default CancelResult lookupCancellation(Command command) { throw new UnsupportedOperationException("Provider lookup not implemented"); }
}
