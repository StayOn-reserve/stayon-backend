package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.KakaoPayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class KakaoPayConfigurationTests {
    @Test void validatedHttpDestinationCannotBeChangedAfterClientConstruction(){
        var mock=new MockKakaoPayServer();var settings=new KakaoPayProperties();settings.setMockTransport(true);settings.setApiOrigin(mock.origin());var env=new MockEnvironment();env.setActiveProfiles("test");
        var client=new KakaoPayHttpClient(settings,JsonMapper.builder().build(),env);settings.setApiOrigin("https://evil.example.invalid");
        try(mock;var secret=new PgSecret("fixture-only".toCharArray())){client.post("ready",java.util.Map.of(),secret);}
        assertEquals(1,mock.readyCalls.get());
    }
    @Test void mockTransportCannotBeUsedInProductionOrAtARemoteEndpoint(){
        var settings=new KakaoPayProperties();settings.setMockTransport(true);settings.setApiOrigin("http://127.0.0.1:12345");var env=new MockEnvironment();env.setActiveProfiles("prod");
        assertThrows(IllegalArgumentException.class,()->new KakaoPayHttpClient(settings,JsonMapper.builder().build(),env));
        env.setActiveProfiles("test");settings.setApiOrigin("http://evil.example.invalid:12345");assertThrows(IllegalArgumentException.class,()->new KakaoPayHttpClient(settings,JsonMapper.builder().build(),env));
    }
    @ParameterizedTest @ValueSource(strings={"http://online-pay.kakao.com/a","https://online-pay.kakao.com.evil.invalid/a","https://user@online-pay.kakao.com/a","https://online-pay.kakao.com:8443/a","javascript:alert(1)","/relative"})
    void paymentRedirectMustUseAnExactHttpsAllowlist(String value){assertThrows(IllegalArgumentException.class,()->new KakaoPayUrls(new KakaoPayProperties()).payment(value));}
    @Test void apiEndpointCannotRedirectCredentialsToAnArbitraryHttpsHost(){
        var settings=new KakaoPayProperties();settings.setApiOrigin("https://evil.example.invalid");assertThrows(IllegalArgumentException.class,()->new KakaoPayHttpClient(settings,JsonMapper.builder().build(),new MockEnvironment()));
        assertFalse(new KakaoPayProperties().isEnabled());assertFalse(new KakaoPayProperties().isLiveEnabled());
    }
}
