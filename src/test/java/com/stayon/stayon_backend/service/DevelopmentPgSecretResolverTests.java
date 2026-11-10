package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.*;
import com.stayon.stayon_backend.entity.PgMerchant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DevelopmentPgSecretResolverTests {
    final MockEnvironment env=new MockEnvironment();
    final PgMerchantProperties merchants=new PgMerchantProperties();
    final KakaoPayProperties kakao=new KakaoPayProperties();
    final Map<String,String> variables=new HashMap<>(Map.of("KAKAOPAY_SANDBOX_OWNER_ID","1","KAKAOPAY_SANDBOX_MERCHANT_ID","merchant",
            "KAKAOPAY_SANDBOX_SECRET_KIND","DEV","KAKAOPAY_SANDBOX_DEV_SECRET","synthetic-fixture"));
    final PgMerchantContext scope=new PgMerchantContext("merchant",1L,"KAKAOPAY",PgMerchant.Environment.SANDBOX,"TC0ONETIME","dev/kakaopay/sandbox/1/merchant");
    DevelopmentPgSecretResolver resolver(){env.setActiveProfiles("dev");merchants.setEnvironment(PgMerchant.Environment.SANDBOX);return new DevelopmentPgSecretResolver(env,merchants,kakao,variables::get);}
    void denied(DevelopmentPgSecretResolver resolver,PgMerchantContext scope){
        var error=assertThrows(ResponseStatusException.class,()->resolver.resolve(scope));assertEquals(503,error.getStatusCode().value());assertNull(error.getCause());
        assertEquals("Merchant credentials are unavailable",error.getReason());
    }
    @Test void injectedCredentialAndRedaction(){try(var secret=resolver().resolve(scope)){assertTrue(Arrays.equals("synthetic-fixture".toCharArray(),secret.copy()));assertEquals("PgSecret[REDACTED]",secret.toString());}}
    @Test void missingOrInvalidEnvironmentVariables(){var r=resolver();for(String name:List.copyOf(variables.keySet())){String saved=variables.remove(name);denied(r,scope);variables.put(name,saved);}for(String bad:List.of(""," ","x\nx"," x")){variables.put("KAKAOPAY_SANDBOX_DEV_SECRET",bad);denied(r,scope);}}
    @Test void refusesOtherMerchantOwnerProviderCidReferenceAndLive(){var r=resolver();for(var bad:List.of(
            new PgMerchantContext("other",1L,"KAKAOPAY",PgMerchant.Environment.SANDBOX,"TC0ONETIME",scope.secretReference()),
            new PgMerchantContext("merchant",2L,"KAKAOPAY",PgMerchant.Environment.SANDBOX,"TC0ONETIME",scope.secretReference()),
            new PgMerchantContext("merchant",1L,"OTHER",PgMerchant.Environment.SANDBOX,"TC0ONETIME",scope.secretReference()),
            new PgMerchantContext("merchant",1L,"KAKAOPAY",PgMerchant.Environment.LIVE,"TC0ONETIME",scope.secretReference()),
            new PgMerchantContext("merchant",1L,"KAKAOPAY",PgMerchant.Environment.SANDBOX,"LIVE_CID",scope.secretReference()),
            new PgMerchantContext("merchant",1L,"KAKAOPAY",PgMerchant.Environment.SANDBOX,"TC0ONETIME","dev/kakaopay/sandbox/1/other")))denied(r,bad);
        denied(r,null);variables.put("KAKAOPAY_SANDBOX_SECRET_KIND","LIVE");denied(r,scope);
    }
    @Test void refusesLiveSettingsAndMixedProfiles(){var r=resolver();merchants.setEnvironment(PgMerchant.Environment.LIVE);denied(r,scope);merchants.setEnvironment(PgMerchant.Environment.SANDBOX);kakao.setLiveEnabled(true);denied(r,scope);kakao.setLiveEnabled(false);for(String[] profiles:List.of(new String[]{"prod"},new String[]{"dev","custom"},new String[]{})){env.setActiveProfiles(profiles);denied(r,scope);}}
    @Test void beanOnlyInDevelopment(){for(String[] profiles:List.of(new String[]{"dev"},new String[]{"prod"},new String[]{"dev","prod"},new String[]{"dev","production"},new String[]{"dev","live"},new String[]{})){
        try(var context=new AnnotationConfigApplicationContext()){context.getEnvironment().setActiveProfiles(profiles);context.registerBean(PgMerchantProperties.class);context.registerBean(KakaoPayProperties.class);context.register(DevelopmentPgSecretResolver.class);context.refresh();assertEquals(Arrays.equals(profiles,new String[]{"dev"})?1:0,context.getBeansOfType(PgSecretResolver.class).size());}
    }}
}
