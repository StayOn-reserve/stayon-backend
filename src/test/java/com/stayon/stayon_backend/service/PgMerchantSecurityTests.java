package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.PgMerchantProperties;
import com.stayon.stayon_backend.repository.PgMerchantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PgMerchantSecurityTests {
    @Test void testSecretResolverCannotBeRegisteredInProduction(){
        var beans=new StaticListableBeanFactory();beans.addBean("fake",new FakePgSecretResolver());var env=new MockEnvironment();env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class,()->new PgSecrets(mock(PgMerchantRepository.class),new PgMerchantProperties(),beans.getBeanProvider(PgSecretResolver.class),new PgRuntimeGuard(env),mock(PlatformTransactionManager.class)));
    }
    @Test void credentialCopiesAndLoggingAreRestricted(){
        char[] input="test-only-value".toCharArray();var secret=new PgSecret(input);input[0]='X';
        assertEquals('t',secret.copy()[0]);assertFalse(secret.toString().contains("test-only-value"));secret.close();assertThrows(IllegalStateException.class,secret::copy);
    }
}
