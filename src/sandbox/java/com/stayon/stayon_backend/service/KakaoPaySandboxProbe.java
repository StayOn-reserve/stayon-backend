package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Standalone, test-only, interactive probe. Not a JUnit test and not included in bootJar. */
@Import(KakaoPaySandboxProbe.Credentials.class)
@TestPropertySource(properties={"payment.kakaopay.enabled=true","payment.kakaopay.mock-transport=false","payment.kakaopay.live-enabled=false",
        "payment.kakaopay.tax-mode=ALL_TAXABLE","payment.providers.rules.KAKAOPAY.enabled=true","payment.providers.rules.KAKAOPAY.allow-all-sites=true",
        "payment.merchant.environment=SANDBOX","payment.cancellation-worker.enabled=false","payment.recovery.enabled=false","payment.webhook.enabled=false",
        "logging.level.org.springframework.boot=ERROR"})
public class KakaoPaySandboxProbe extends PaymentFlowTests {
    private static KakaoPaySandboxSupport.Settings settings;
    private static Path directory;
    private static MockKakaoPayServer local;
    @Autowired KakaoPayReadyService ready;
    @Autowired KakaoPaySandboxSupport.Resolver resolver;
    @Autowired Environment environment;
    @TestConfiguration(proxyBeanMethods=false) static class Credentials {
        @Bean @Primary KakaoPaySandboxSupport.Resolver developmentCredentials(){
            return new KakaoPaySandboxSupport.Resolver(()->{
                String key=local!=null?"local-fixture-only":System.getenv("KAKAOPAY_SANDBOX_DEV_SECRET");return key==null?null:key.toCharArray();
            });
        }
    }
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties){
        if(settings==null||directory==null)throw new IllegalStateException("Standalone preflight required");
        properties.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("state").toString().replace('\\','/')+";NON_KEYWORDS=USER");
        properties.add("spring.datasource.driver-class-name",()->"org.h2.Driver");properties.add("spring.datasource.username",()->"sa");properties.add("spring.datasource.password",()->"");
        properties.add("spring.jpa.hibernate.ddl-auto",()->"update");properties.add("spring.jpa.show-sql",()->"false");
        properties.add("payment.kakaopay.api-origin",()->local!=null?local.origin():"https://open-api.kakaopay.com");
        properties.add("payment.kakaopay.mock-transport",()->local!=null?"true":"false");
        for(String name:List.of("approval-url","cancel-url","fail-url"))properties.add("payment.kakaopay."+name,()->settings.callback);
        properties.add("payment.kakaopay.callback-origins[0]",()->settings.origin);
    }
    public static void main(String[] args)throws Exception{
        if(Arrays.equals(args,new String[]{"--preflight"})){
            KakaoPaySandboxSupport.Settings.read(System.getenv());
            String secret=System.getenv("KAKAOPAY_SANDBOX_DEV_SECRET");
            if(secret==null||secret.isBlank()||!secret.equals(secret.strip())||secret.chars().anyMatch(Character::isISOControl))
                throw new IllegalStateException("Sandbox credential preflight rejected");
            String profiles=System.getenv("SPRING_PROFILES_ACTIVE");
            if(profiles!=null&&!"test".equals(profiles))throw new IllegalStateException("Test-only profile required");
            System.out.println("Sandbox preflight passed; no database or HTTP calls performed.");return;
        }
        // No context, database or HTTP client is created until explicit opt-in and a hidden console are available.
        if(Arrays.equals(args,new String[]{"--local-smoke"})){
            local=new MockKakaoPayServer();var env=new HashMap<String,String>();
            env.put("KAKAOPAY_SANDBOX_ENABLED","true");env.put("KAKAOPAY_SANDBOX_APPROVAL",KakaoPaySandboxSupport.ACK);env.put("KAKAOPAY_SANDBOX_SECRET_KIND","DEV");
            env.put("KAKAOPAY_SANDBOX_DOMAIN_REGISTRATION","confirmed");env.put("KAKAOPAY_SANDBOX_CID","TC0ONETIME");env.put("KAKAOPAY_SANDBOX_ENVIRONMENT","SANDBOX");
            env.put("KAKAOPAY_SANDBOX_API_ORIGIN","https://open-api.kakaopay.com");env.put("KAKAOPAY_SANDBOX_CALLBACK","https://fixture.example.invalid/callback");env.put("KAKAOPAY_SANDBOX_REGISTERED_ORIGIN","https://fixture.example.invalid");
            settings=KakaoPaySandboxSupport.Settings.read(env);
        }else{
            settings=KakaoPaySandboxSupport.Settings.read(System.getenv());
            if(args.length!=0||System.console()==null||System.getenv("KAKAOPAY_SANDBOX_DEV_SECRET")==null)throw new IllegalStateException("Isolated interactive test console required");
        }
        if(System.getenv("SPRING_PROFILES_ACTIVE")!=null&&!"test".equals(System.getenv("SPRING_PROFILES_ACTIVE")))throw new IllegalStateException("Test-only profile required");
        Path root=Path.of("build","kakaopay-sandbox").toAbsolutePath().normalize();Files.createDirectories(root);
        directory=Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
        var probe=new KakaoPaySandboxProbe();var context=new TestContextManager(KakaoPaySandboxProbe.class);
        ConfigurableApplicationContext application=null;
        try{
            context.prepareTestInstance(probe);
            application=(ConfigurableApplicationContext)context.getTestContext().getApplicationContext();
            if(!Set.copyOf(Arrays.asList(probe.environment.getActiveProfiles())).equals(Set.of("test")))throw new IllegalStateException("Test-only profile required");
            probe.setup();probe.journey();System.out.println("Verified: order CANCELLED / payment CANCELED.");
        }catch(Throwable failure){
            System.err.println("Verification incomplete. Do not replay financial requests. Preserve the isolated database for lookup recovery.");
            // Do not dump callback/credential-containing exception chains.
            throw new IllegalStateException("Sandbox verification incomplete");
        }finally{
            System.out.println("Isolated journal: "+directory);
            if(application!=null)application.close();
            if(local!=null)local.close();
        }
    }
    private void journey(){
        String merchantId=tx(()->{
            var owner=em.find(User.class,ownerId);var merchant=new PgMerchant(owner,"KAKAOPAY",PgMerchant.Environment.SANDBOX,"TC0ONETIME",Set.of("KRW"),"placeholder",Instant.now());
            ReflectionTestUtils.setField(merchant,"secretReference","sandbox/"+ownerId+"/"+merchant.getId());em.persist(merchant);return merchant.getId();
        });
        resolver.bind(pgSecrets.context(merchantId));
        var attempt=payments.createAttempt(authentication,orderId,"KAKAOPAY","sandbox-"+UUID.randomUUID());
        if(attempt.amount()!=2400||!"KRW".equals(attempt.currency())||!"KAKAOPAY".equals(attempt.provider()))throw new IllegalStateException("Sandbox amount or provider rejected before Ready");
        var prepared=ready.ready(authentication,attempt.id());
        if(prepared.preparationStatus()!=PaymentAttempt.PreparationStatus.PREPARED)throw new IllegalStateException("Ready result uncertain");
        if(local==null)System.console().printf("Open only this development payment URL:%n%s%n",prepared.pcUrl());
        char[] input=local!=null?(local.lastReady.path("approval_url").asString()+"&pg_token=ephemeral-test-token").toCharArray()
                :System.console().readPassword("Paste the complete approved HTTPS callback URL (hidden): ");
        PgAdapter.BrowserAuthentication proof;
        try{proof=KakaoPaySandboxSupport.callback(settings,new String(input),attempt.id(),tx(()->em.find(PaymentAttempt.class,attempt.id()).getCallbackStateHash()));}
        finally{Arrays.fill(input,'\0');}
        var delegate=registry.require("KAKAOPAY");
        var scoped=KakaoPaySandboxSupport.compensatingAdapter(delegate,attempt.id(),attempt.merchant(),()->{
            if(!orders.cancel(authentication,orderId).accepted())throw new IllegalStateException("Compensation intent not accepted");
        });
        var factory=new StaticListableBeanFactory();factory.addBean("scopedKakao",scoped);
        var orchestrator=new PaymentOrchestrator(stages,new PgAdapters(factory.getBeanProvider(PgAdapter.class),new PgRuntimeGuard(environment)),pgSecrets);
        var approved=orchestrator.approve(authentication,attempt.id(),proof);
        if(approved.status()!=PaymentAttempt.Status.CANCEL_PENDING)throw new IllegalStateException("Approval unconfirmed");
        try(var secret=pgSecrets.resolve(approved.merchant())){
            if(delegate.lookupApproval(approved.command(false),secret).outcome()!=PgAdapter.ApprovalOutcome.APPROVED)throw new IllegalStateException("Order query not confirmed");
        }
        if(orchestrator.processCancellation(attempt.id()).status()!=PaymentAttempt.Status.CANCELED)throw new IllegalStateException("Cancel unconfirmed");
        try(var secret=pgSecrets.resolve(approved.merchant())){
            if(delegate.lookupCancellation(approved.command(true),secret).outcome()!=PgAdapter.CancelOutcome.CANCELED)throw new IllegalStateException("Cancel query not confirmed");
        }
        if(state()!=ServiceOrder.ServiceOrderStatus.CANCELLED||view(attempt.id()).status()!=PaymentAttempt.Status.CANCELED)throw new IllegalStateException("Final local state not confirmed");
    }
}
