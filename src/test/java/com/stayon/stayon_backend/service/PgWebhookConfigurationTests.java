package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;

class PgWebhookConfigurationTests {
    @ParameterizedTest @ValueSource(strings={"prod","production","dev","none","test,prod"})
    void fakeAdaptersAndVerifiersCannotBeRegisteredOutsideIsolatedTest(String profiles){
        var environment=new MockEnvironment();if(!profiles.equals("none"))environment.setActiveProfiles(profiles.split(","));
        var guard=new PgRuntimeGuard(environment);var beans=new StaticListableBeanFactory();beans.addBean("fake",new FakePgAdapter());
        assertThrows(IllegalStateException.class,()->new PgAdapters(beans.getBeanProvider(PgAdapter.class),guard));
        assertThrows(IllegalStateException.class,()->guard.verify("REAL_NAME",true));assertThrows(IllegalStateException.class,()->guard.verify("FAKE_PG",false));
    }
    @Test void isolatedTestAllowsFakeAndProductionAllowsOnlyNonTestContracts(){
        var test=new MockEnvironment();test.setActiveProfiles("test");new PgRuntimeGuard(test).verify("TEST_PG",true);
        var live=new MockEnvironment();live.setActiveProfiles("prod");new PgRuntimeGuard(live).verify("NAVER_PAY",false);
    }
    @Test void fakeVerifierCannotBypassRuntimeGuardEvenWithAProductionNamedAdapter(){
        var env=new MockEnvironment();env.setActiveProfiles("prod");var guard=new PgRuntimeGuard(env);
        var adapters=new StaticListableBeanFactory();var adapter=mock(PgAdapter.class);when(adapter.provider()).thenReturn("REAL_NAME");adapters.addBean("real",adapter);
        var registered=new PgAdapters(adapters.getBeanProvider(PgAdapter.class),guard);
        var verifiers=new StaticListableBeanFactory();var verifier=mock(PgWebhookVerifier.class);when(verifier.provider()).thenReturn("REAL_NAME");when(verifier.testOnly()).thenReturn(true);verifiers.addBean("fakeVerifier",verifier);
        assertThrows(IllegalStateException.class,()->new PgWebhookVerifiers(verifiers.getBeanProvider(PgWebhookVerifier.class),registered,guard));
    }
    @Test void webhookSchedulerIsDisabledByDefault(){
        var worker=mock(PgWebhookWorker.class);runner(worker).run(context->{assertFalse(context.containsBean("pgWebhookWorkerConfig"));verifyNoInteractions(worker);});
    }
    @Test void explicitlyEnabledTestSchedulerInvokesWorker(){
        var worker=mock(PgWebhookWorker.class);runner(worker).withPropertyValues("payment.webhook.enabled=true","payment.webhook.delay-ms=10")
                .run(context->{assertNull(context.getStartupFailure());verify(worker,timeout(2000).atLeastOnce()).drain(20);});
    }
    private ApplicationContextRunner runner(PgWebhookWorker worker){return new ApplicationContextRunner().withUserConfiguration(PgWebhookWorkerConfig.class)
            .withBean(PgWebhookWorker.class,()->worker).withBean(PgWebhookProperties.class,PgWebhookProperties::new);}
    @Test void retrySettingsAreBounded(){
        var settings=new PgWebhookProperties();assertFalse(settings.isEnabled());assertEquals(Duration.ofSeconds(5),settings.backoff(1));
        assertEquals(Duration.ofMinutes(15),settings.backoff(100));settings.setMaxAttempts(0);assertThrows(IllegalArgumentException.class,settings::validate);
    }
}
