package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.PaymentAttempt;
import com.stayon.stayon_backend.entity.PgMerchant;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Test classpath only. No Spring component, no default credential, no network operation. */
public final class KakaoPaySandboxSupport {
    private KakaoPaySandboxSupport(){}
    public static final String ACK="I_APPROVE_KAKAOPAY_DEVELOPMENT_MOCK_CALLS";
    public static final class Settings {
        public final String callback,origin;
        private Settings(String callback,String origin){this.callback=callback;this.origin=origin;}
        public static Settings read(Map<String,String> env){
            if(!"true".equals(env.get("KAKAOPAY_SANDBOX_ENABLED"))||!ACK.equals(env.get("KAKAOPAY_SANDBOX_APPROVAL"))
                    ||!"DEV".equals(env.get("KAKAOPAY_SANDBOX_SECRET_KIND"))||!"confirmed".equals(env.get("KAKAOPAY_SANDBOX_DOMAIN_REGISTRATION")))throw invalid();
            if(!"TC0ONETIME".equals(env.get("KAKAOPAY_SANDBOX_CID"))||!"SANDBOX".equals(env.get("KAKAOPAY_SANDBOX_ENVIRONMENT"))
                    ||!"https://open-api.kakaopay.com".equals(env.get("KAKAOPAY_SANDBOX_API_ORIGIN")))throw invalid();
            String callback=env.get("KAKAOPAY_SANDBOX_CALLBACK"),origin=env.get("KAKAOPAY_SANDBOX_REGISTERED_ORIGIN");
            try{
                URI uri=URI.create(callback);URI registered=URI.create(origin);
                if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443
                        ||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!("https://"+uri.getHost()).equals(origin)
                        ||registered.getRawQuery()!=null||registered.getRawFragment()!=null)throw invalid();
                // Production URL helper checks final callback length including attempt and state.
                var properties=new com.stayon.stayon_backend.config.KakaoPayProperties();properties.setCallbackOrigins(Set.of(origin));
                new KakaoPayUrls(properties).callback(callback,UUID.randomUUID().toString(),"s".repeat(43));
            }catch(RuntimeException rejected){throw invalid();}
            return new Settings(callback,origin);
        }
        @Override public String toString(){return "SandboxSettings[REDACTED]";}
    }
    /** Bound once to the fresh test merchant; cannot resolve another owner/provider/environment/reference. */
    public static final class Resolver implements PgSecretResolver {
        private final Supplier<char[]> credential;private PgMerchantContext scope;
        public Resolver(Supplier<char[]> credential){this.credential=credential;}
        public synchronized void bind(PgMerchantContext scope){
            if(this.scope!=null||scope==null||scope.ownerId()==null||!"KAKAOPAY".equals(scope.provider())||scope.environment()!=PgMerchant.Environment.SANDBOX
                    ||!"TC0ONETIME".equals(scope.externalId())||!scope.secretReference().equals("sandbox/"+scope.ownerId()+"/"+scope.id()))throw invalid();
            this.scope=scope;
        }
        @Override public boolean testOnly(){return true;}
        @Override public synchronized PgSecret resolve(PgMerchantContext requested){
            if(scope==null||!scope.equals(requested))throw invalid();char[] key=credential.get();
            try{if(key==null||key.length==0)throw invalid();return new PgSecret(key);}finally{if(key!=null)Arrays.fill(key,'\0');}
        }
    }
    public static PgAdapter.BrowserAuthentication callback(Settings settings,String raw,String attempt,String expectedStateHash){
        try{
            if(raw==null||raw.length()>8192)throw invalid();URI uri=URI.create(raw),configured=URI.create(settings.callback);
            if(!Objects.equals(uri.getScheme(),configured.getScheme())||!Objects.equals(uri.getRawAuthority(),configured.getRawAuthority())
                    ||!Objects.equals(uri.getRawPath(),configured.getRawPath())||uri.getRawFragment()!=null||uri.getRawQuery()==null)throw invalid();
            var fields=new HashMap<String,String>();
            for(String pair:uri.getRawQuery().split("&",-1)){
                String[] parts=pair.split("=",2);if(parts.length!=2)throw invalid();
                String name=URLDecoder.decode(parts[0],StandardCharsets.UTF_8),value=URLDecoder.decode(parts[1],StandardCharsets.UTF_8);
                if(fields.putIfAbsent(name,value)!=null)throw invalid();
            }
            if(!fields.keySet().equals(Set.of("attempt","state","pg_token"))||!attempt.equals(fields.get("attempt"))
                    ||!PaymentAttempt.digest(fields.get("state")).equals(expectedStateHash))throw invalid();
            return new PgAdapter.BrowserAuthentication("KAKAOPAY_V1",Map.of("state",fields.get("state"),"pg_token",fields.get("pg_token")));
        }catch(RuntimeException rejected){throw invalid();}
    }
    public static PgAdapter compensatingAdapter(PgAdapter delegate,String attempt,PgMerchantContext scope,Runnable cancellationIntent){
        return new PgAdapter(){
            public String provider(){return "KAKAOPAY";}public boolean testOnly(){return true;}
            private void check(Command c){if(!attempt.equals(c.reference())||!scope.equals(c.merchant())||c.amount()!=2400||!"KRW".equals(c.currency()))throw invalid();}
            public void validateAuthentication(BrowserAuthentication proof){delegate.validateAuthentication(proof);}
            public ApprovalResult approve(Command c,BrowserAuthentication proof,PgSecret secret){
                check(c);var result=delegate.approve(c,proof,secret);
                if(result.outcome()==ApprovalOutcome.APPROVED)cancellationIntent.run();return result;
            }
            public CancelResult cancel(Command c,PgSecret secret){check(c);return delegate.cancel(c,secret);}
            public ApprovalResult lookupApproval(Command c,PgSecret secret){check(c);return delegate.lookupApproval(c,secret);}
            public CancelResult lookupCancellation(Command c,PgSecret secret){check(c);return delegate.lookupCancellation(c,secret);}
            public ApprovalResult approve(Command c){throw invalid();}public CancelResult cancel(Command c){throw invalid();}
        };
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Sandbox preflight or scope rejected");}
}
