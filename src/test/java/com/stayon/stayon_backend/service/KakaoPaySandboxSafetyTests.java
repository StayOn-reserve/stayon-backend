package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KakaoPaySandboxSafetyTests {
    private Map<String,String> configured(){
        var env=new HashMap<String,String>();env.put("KAKAOPAY_SANDBOX_ENABLED","true");env.put("KAKAOPAY_SANDBOX_APPROVAL",KakaoPaySandboxSupport.ACK);
        env.put("KAKAOPAY_SANDBOX_SECRET_KIND","DEV");env.put("KAKAOPAY_SANDBOX_DOMAIN_REGISTRATION","confirmed");env.put("KAKAOPAY_SANDBOX_CID","TC0ONETIME");env.put("KAKAOPAY_SANDBOX_ENVIRONMENT","SANDBOX");
        env.put("KAKAOPAY_SANDBOX_API_ORIGIN","https://open-api.kakaopay.com");env.put("KAKAOPAY_SANDBOX_CALLBACK","https://sandbox.example.invalid/callback");env.put("KAKAOPAY_SANDBOX_REGISTERED_ORIGIN","https://sandbox.example.invalid");return env;
    }
    @Test void disabledByDefaultAndHasNoCredentialFallback(){assertThrows(IllegalArgumentException.class,()->KakaoPaySandboxSupport.Settings.read(Map.of()));}
    @ParameterizedTest @ValueSource(strings={"prod","test,prod"})
    void developerResolverIsRejectedOutsideTheExclusiveTestProfile(String profiles){
        var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles(profiles.split(","));
        var resolver=new KakaoPaySandboxSupport.Resolver(()->"fixture".toCharArray());
        assertThrows(IllegalStateException.class,()->new PgRuntimeGuard(env).verify("SECRET_RESOLVER",resolver.testOnly()));
    }
    @ParameterizedTest @ValueSource(strings={"ENABLED","APPROVAL","SECRET_KIND","DOMAIN_REGISTRATION","CID","ENVIRONMENT","API_ORIGIN","CALLBACK","REGISTERED_ORIGIN"})
    void everyPreflightFieldIsMandatory(String field){var env=configured();env.remove("KAKAOPAY_SANDBOX_"+field);assertThrows(IllegalArgumentException.class,()->KakaoPaySandboxSupport.Settings.read(env));}
    @ParameterizedTest @ValueSource(strings={"http://sandbox.example.invalid/callback","https://other.example.invalid/callback","https://sandbox.example.invalid:8443/callback","https://user@sandbox.example.invalid/callback","https://sandbox.example.invalid/callback?state=chosen","https://sandbox.example.invalid/callback#fragment"})
    void callbackMustMatchTheDeclaredHttpsRegisteredOrigin(String callback){var env=configured();env.put("KAKAOPAY_SANDBOX_CALLBACK",callback);assertThrows(IllegalArgumentException.class,()->KakaoPaySandboxSupport.Settings.read(env));}
    @ParameterizedTest @ValueSource(strings={"CID","ENVIRONMENT","API_ORIGIN","SECRET_KIND","APPROVAL"})
    void liveOrArbitraryDestinationCannotPassPreflight(String field){var env=configured();env.put("KAKAOPAY_SANDBOX_"+field,"LIVE");assertThrows(IllegalArgumentException.class,()->KakaoPaySandboxSupport.Settings.read(env));}
    @Test void resolverIsBoundOnceAndRejectsOtherOwnersMerchantsReferencesAndEnvironments(){
        var scope=new PgMerchantContext("merchant",1L,"KAKAOPAY",PgMerchant.Environment.SANDBOX,"TC0ONETIME","sandbox/1/merchant");
        var resolver=new KakaoPaySandboxSupport.Resolver(()->"local-only".toCharArray());assertTrue(resolver.testOnly());assertThrows(IllegalArgumentException.class,()->resolver.resolve(scope));resolver.bind(scope);
        try(var secret=resolver.resolve(scope)){assertEquals("local-only",new String(secret.copy()));}
        assertThrows(IllegalArgumentException.class,()->resolver.bind(scope));
        for(var bad:List.of(new PgMerchantContext("other",1L,"KAKAOPAY",PgMerchant.Environment.SANDBOX,"TC0ONETIME","sandbox/1/merchant"),
                new PgMerchantContext("merchant",2L,"KAKAOPAY",PgMerchant.Environment.SANDBOX,"TC0ONETIME","sandbox/1/merchant"),
                new PgMerchantContext("merchant",1L,"KAKAOPAY",PgMerchant.Environment.LIVE,"TC0ONETIME","sandbox/1/merchant"),
                new PgMerchantContext("merchant",1L,"KAKAOPAY",PgMerchant.Environment.SANDBOX,"TC0ONETIME","live/reference")))assertThrows(IllegalArgumentException.class,()->resolver.resolve(bad));
    }
    @ParameterizedTest @ValueSource(strings={"attempt=other&state=nonce&pg_token=token","attempt=a&state=wrong&pg_token=token","attempt=a&state=nonce&pg_token=token&pg_token=duplicate","attempt=a&state=nonce&pg_token=token&cid=chosen"})
    void callbackRejectsTamperingDuplicatesAndAdditionalFields(String query){
        var settings=KakaoPaySandboxSupport.Settings.read(configured());assertThrows(IllegalArgumentException.class,()->KakaoPaySandboxSupport.callback(settings,settings.callback+"?"+query,"a",PaymentAttempt.digest("nonce")));
    }
    @Test void validCallbackRemainsTransientAndRejectsAnotherPath(){
        var settings=KakaoPaySandboxSupport.Settings.read(configured());String query="?attempt=a&state=nonce&pg_token=ephemeral";
        var proof=KakaoPaySandboxSupport.callback(settings,settings.callback+query,"a",PaymentAttempt.digest("nonce"));assertEquals("ephemeral",proof.payload().get("pg_token"));assertFalse(proof.toString().contains("ephemeral"));
        assertThrows(IllegalArgumentException.class,()->KakaoPaySandboxSupport.callback(settings,settings.origin+"/other"+query,"a",PaymentAttempt.digest("nonce")));
    }
}
