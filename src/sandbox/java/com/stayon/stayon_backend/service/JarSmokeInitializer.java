package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.*;
import com.stayon.stayon_backend.entity.PgMerchant;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Packaged in a separate verification support JAR only, never in the executable application. */
public final class JarSmokeInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext application){
        var env=application.getEnvironment();
        boolean development=Set.copyOf(Arrays.asList(env.getActiveProfiles())).equals(Set.of("dev"));
        if(!(development||Set.copyOf(Arrays.asList(env.getActiveProfiles())).equals(Set.of("jar-verification")))
                ||!"true".equals(env.getProperty("verification.enabled")))throw new IllegalStateException("Isolated JAR verification required");
        application.addApplicationListener((ApplicationListener<ApplicationEvent>)event->{
            if(!(event instanceof ApplicationReadyEvent))return;
            var settings=application.getBean(KakaoPayProperties.class);
            if(application.getBean(PaymentOrchestrator.class)==null||application.getBean(KakaoPayReadyService.class)==null
                    ||!(application.getBean(PgAdapters.class).require("KAKAOPAY") instanceof KakaoPayAdapter)
                    ||!settings.isEnabled()||settings.isMockTransport()||settings.isLiveEnabled()
                    ||!"https://open-api.kakaopay.com".equals(settings.getApiOrigin())
                    ||!Duration.ofSeconds(8).equals(settings.getRequestTimeout())
                    ||application.getBean(PgMerchantProperties.class).getEnvironment()!=PgMerchant.Environment.SANDBOX)throw new IllegalStateException("Packaged payment configuration mismatch");
            for(String name:List.of("payment.recovery.enabled","payment.cancellation-worker.enabled","payment.webhook.enabled"))
                if(env.getProperty(name,Boolean.class,true))throw new IllegalStateException("Worker must remain disabled");
            if(development){
                var resolver=application.getBean(PgSecretResolver.class);
                if(!(resolver instanceof DevelopmentPgSecretResolver))throw new IllegalStateException("Development resolver missing");
                var scope=new PgMerchantContext("jar-fixture",1L,"KAKAOPAY",PgMerchant.Environment.SANDBOX,"TC0ONETIME","dev/kakaopay/sandbox/1/jar-fixture");
                try(var secret=resolver.resolve(scope)){
                    char[] value=secret.copy();try{if(!Arrays.equals(value,"synthetic-jar-fixture".toCharArray()))throw new IllegalStateException("Credential injection failed");}finally{Arrays.fill(value,'\0');}
                }
            }else if(!application.getBeansOfType(PgSecretResolver.class).isEmpty())throw new IllegalStateException("Unexpected credential resolver in production archive");
            try{application.getBean(PgSecrets.class).resolve(null);throw new IllegalStateException("Missing credentials were accepted");}
            catch(ResponseStatusException expected){if(expected.getStatusCode().value()!=503)throw expected;}
            String marker=System.getenv("JAR_VERIFICATION_MARKER");
            if(marker==null||!marker.equals(env.getProperty("verification.marker")))throw new IllegalStateException("Environment injection failed");
            Path report=Path.of(env.getRequiredProperty("verification.report")).toAbsolutePath().normalize();
            if(!report.startsWith(Path.of("build","jar-verification").toAbsolutePath().normalize()))throw new IllegalStateException("Invalid report location");
            try{Files.writeString(report,"paymentOrchestrator=true\nkakaoPayAdapter=true\npgSecrets=true\n"+(development?"developmentResolverInjected=true\n":"resolverAbsentFailClosed=true\n")+"sandboxEnvironment=true\nworkersDisabled=true\nenvironmentInjection=true\n");}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot write verification evidence");}
            application.close();
        });
    }
}
