package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.PgMerchant;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

final class FakePgSecretResolver implements PgSecretResolver {
    @Override public boolean testOnly(){return true;}
    static String value(String merchantId){return "test-only-merchant-key-"+merchantId;}
    @Override public PgSecret resolve(PgMerchantContext scope){
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        if(scope.environment()!=PgMerchant.Environment.SANDBOX||!scope.secretReference().equals("test/"+scope.ownerId()+"/"+scope.provider()+"/"+scope.id()))throw new IllegalArgumentException("Secret scope denied");
        return new PgSecret(value(scope.id()).toCharArray());
    }
}
