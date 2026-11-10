package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.KakaoPayProperties;
import com.stayon.stayon_backend.config.PgMerchantProperties;
import com.stayon.stayon_backend.entity.PgMerchant;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.function.Function;

/** Explicit development scope only. Credentials are read directly from process environment. */
@Component
@Profile("dev & !prod & !production & !live")
public final class DevelopmentPgSecretResolver implements PgSecretResolver {
    private final Environment environment;
    private final PgMerchantProperties merchants;
    private final KakaoPayProperties kakao;
    private final Function<String,String> variables;

    @org.springframework.beans.factory.annotation.Autowired
    public DevelopmentPgSecretResolver(Environment environment,PgMerchantProperties merchants,KakaoPayProperties kakao){
        this(environment,merchants,kakao,System::getenv);
    }
    DevelopmentPgSecretResolver(Environment environment,PgMerchantProperties merchants,KakaoPayProperties kakao,Function<String,String> variables){
        this.environment=environment;this.merchants=merchants;this.kakao=kakao;this.variables=variables;
    }
    @Override public PgSecret resolve(PgMerchantContext scope){
        if(!Set.copyOf(Arrays.asList(environment.getActiveProfiles())).equals(Set.of("dev"))
                ||merchants.getEnvironment()!=PgMerchant.Environment.SANDBOX||kakao.isLiveEnabled()
                ||scope==null||scope.environment()!=PgMerchant.Environment.SANDBOX||!"KAKAOPAY".equals(scope.provider())
                ||!"TC0ONETIME".equals(scope.externalId())||scope.ownerId()==null||scope.id()==null
                ||!scope.ownerId().toString().equals(variables.apply("KAKAOPAY_SANDBOX_OWNER_ID"))
                ||!scope.id().equals(variables.apply("KAKAOPAY_SANDBOX_MERCHANT_ID"))
                ||!"DEV".equals(variables.apply("KAKAOPAY_SANDBOX_SECRET_KIND"))
                ||!("dev/kakaopay/sandbox/"+scope.ownerId()+"/"+scope.id()).equals(scope.secretReference()))throw unavailable();
        String value=variables.apply("KAKAOPAY_SANDBOX_DEV_SECRET");
        if(value==null||value.isBlank()||!value.equals(value.strip())||value.chars().anyMatch(Character::isISOControl))throw unavailable();
        char[] copy=value.toCharArray();
        try{return new PgSecret(copy);}finally{Arrays.fill(copy,'\0');}
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Merchant credentials are unavailable");}
    @Override public String toString(){return "DevelopmentPgSecretResolver[REDACTED]";}
}
