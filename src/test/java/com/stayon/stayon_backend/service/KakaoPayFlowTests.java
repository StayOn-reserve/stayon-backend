package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.KakaoPayProperties;
import com.stayon.stayon_backend.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties={"payment.kakaopay.enabled=true","payment.kakaopay.mock-transport=true","payment.kakaopay.tax-mode=ALL_TAXABLE",
        "payment.kakaopay.approval-url=https://frontend.example.invalid/kakao/success","payment.kakaopay.cancel-url=https://frontend.example.invalid/kakao/cancel",
        "payment.kakaopay.fail-url=https://frontend.example.invalid/kakao/fail","payment.kakaopay.callback-origins[0]=https://frontend.example.invalid",
        "payment.kakaopay.request-timeout=3s","payment.providers.rules.KAKAOPAY.enabled=true","payment.providers.rules.KAKAOPAY.allow-all-sites=true",
        "payment.recovery.initial-grace=0s"})
class KakaoPayFlowTests extends PgMerchantRoutingTests {
    protected static final MockKakaoPayServer HTTP=new MockKakaoPayServer();
    @DynamicPropertySource static void endpoint(DynamicPropertyRegistry p){p.add("payment.kakaopay.api-origin",HTTP::origin);}
    @Autowired protected KakaoPayReadyService readyService;
    @Autowired protected PaymentRecoveryWorker recovery;
    @Autowired protected KakaoPayProperties kakaoSettings;
    @Autowired private KakaoPayHttpClient kakaoHttp;
    @Autowired protected Clock clock;
    protected String kakaoMerchant;
    @BeforeEach void kakaoFixture(){
        HTTP.reset();
        kakaoMerchant=tx(()->{var m=new PgMerchant(em.find(User.class,ownerId),"KAKAOPAY",PgMerchant.Environment.SANDBOX,"TC0ONETIME",Set.of("KRW"),"placeholder",clock.instant());
            ReflectionTestUtils.setField(m,"secretReference","test/"+ownerId+"/KAKAOPAY/"+m.getId());em.persist(m);return m.getId();});
    }
    @AfterEach void releaseHttp(){HTTP.release.countDown();kakaoSettings.setTaxMode(KakaoPayProperties.TaxMode.ALL_TAXABLE);kakaoSettings.setApprovalUrl("https://frontend.example.invalid/kakao/success");}
    protected PaymentTransactions.View kakao(){return payments.createAttempt(authentication,orderId,"KAKAOPAY","kakao-key");}
    protected String stateNonce(){
        return Arrays.stream(URI.create(HTTP.lastReady.path("approval_url").asString()).getRawQuery().split("&"))
                .filter(p->p.startsWith("state=")).map(p->URLDecoder.decode(p.substring(6),StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }
    protected PgAdapter.BrowserAuthentication proof(){return new PgAdapter.BrowserAuthentication("KAKAOPAY_V1",Map.of("state",stateNonce(),"pg_token","ephemeral-test-token"));}
    /** Real HTTP approval result arrives after a durable customer cancellation intent. */
    protected PaymentTransactions.View kakaoCancellation(){
        var a=kakao();readyService.ready(authentication,a.id());var proof=proof();
        var claim=stages.claimApproval(authentication,a.id(),proof).view();
        try(var secret=pgSecrets.resolve(claim.merchant())){
            var result=registry.require("KAKAOPAY").approve(claim.command(false),proof,secret);
            assertTrue(orders.cancel(authentication,orderId).accepted());
            return stages.applyApproval(a.id(),claim.approvalClaim(),result);
        }
    }
    @Test void fullCancellationUsesPinnedTidCidAndServerAmountAndIsIdempotent()throws Exception{
        var a=kakaoCancellation();assertEquals(1,worker.drain(20));
        assertEquals(PaymentAttempt.Status.CANCELED,view(a.id()).status());assertEquals(CANCELLED,state());
        assertEquals(a.transactionId(),HTTP.lastCancel.path("tid").asString());assertEquals("TC0ONETIME",HTTP.lastCancel.path("cid").asString());
        assertEquals(2400,HTTP.lastCancel.path("cancel_amount").longValue());assertEquals(0,HTTP.lastCancel.path("cancel_tax_free_amount").longValue());
        assertEquals(4,HTTP.lastCancel.size());assertEquals("SECRET_KEY "+FakePgSecretResolver.value(kakaoMerchant),HTTP.lastAuthorization);
        assertEquals(a.transactionId(),view(a.id()).transactionId());
        assertEquals(PaymentAttempt.Status.CANCELED,payments.processCancellation(a.id()).status());assertEquals(0,worker.drain(20));
        mvc.perform(post("/customer/orders/"+orderId+"/cancel").header("Authorization",bearer)).andExpect(status().isOk());
        assertEquals(1,HTTP.cancelCalls.get());
    }
    @Test void fullTaxFreeCancellationUsesTheReadySnapshotNotCurrentConfiguration(){
        kakaoSettings.setTaxMode(KakaoPayProperties.TaxMode.ALL_TAX_FREE);var a=kakaoCancellation();
        kakaoSettings.setTaxMode(KakaoPayProperties.TaxMode.ALL_TAXABLE);
        assertEquals(PaymentAttempt.Status.CANCELED,payments.processCancellation(a.id()).status());assertEquals(2400,HTTP.lastCancel.path("cancel_tax_free_amount").longValue());
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,429,500,503})
    void cancelHttpErrorsPreserveIntentAndNeverReplayTheFinancialRequest(int code){
        var a=kakaoCancellation();HTTP.cancelStatus=code;
        assertEquals(PaymentAttempt.Status.CANCEL_PENDING,payments.processCancellation(a.id()).status());assertTrue(view(a.id()).cancelUnknown());assertTrue(view(a.id()).cancelRequested());
        HTTP.cancelStatus=200;payments.processCancellation(a.id());payments.reconcileCancellation(a.id());
        assertEquals(CANCEL_PENDING,state());assertEquals(1,HTTP.cancelCalls.get());assertEquals(1,HTTP.orderCalls.get());
    }
    @Test void cancelResponseTimeoutIsRecoveredByOrderWithoutAnotherCancel(){
        var a=kakaoCancellation();HTTP.delayedOperation="cancel";
        assertEquals(PaymentAttempt.Status.CANCEL_PENDING,payments.processCancellation(a.id()).status());assertTrue(view(a.id()).cancelUnknown());
        HTTP.delayedOperation=null;assertEquals(1,recovery.drain(20));assertEquals(CANCELLED,state());assertEquals(1,HTTP.cancelCalls.get());assertEquals(1,HTTP.orderCalls.get());
    }
    @ParameterizedTest @ValueSource(strings={"cid","tid","amount","user","json","tax","remaining","current","aid","date","partial"})
    void uncertainOrMismatchedCancellationResponsesCannotFinalizeTheOrder(String field){
        var a=kakaoCancellation();HTTP.badCid=field.equals("cid");HTTP.badTid=field.equals("tid");HTTP.badAmount=field.equals("amount");HTTP.badUser=field.equals("user");HTTP.invalidJson=field.equals("json");
        HTTP.badCancelTax=field.equals("tax");HTTP.badCancelRemaining=field.equals("remaining");HTTP.badCancelCurrent=field.equals("current");HTTP.missingCancelAid=field.equals("aid");HTTP.missingCanceledAt=field.equals("date");HTTP.partialCancel=field.equals("partial");
        assertEquals(PaymentAttempt.Status.CANCEL_PENDING,payments.processCancellation(a.id()).status());assertEquals(CANCEL_PENDING,state());
        assertTrue(view(a.id()).cancelRequested());assertTrue(view(a.id()).cancelUnknown());payments.processCancellation(a.id());assertEquals(1,HTTP.cancelCalls.get());
    }
    @ParameterizedTest @ValueSource(strings={"SUCCESS_PAYMENT","PART_CANCEL_PAYMENT","AUTH_PASSWORD","FUTURE_STATUS"})
    void cancellationLookupDoesNotMistakeAnUnconfirmedOrPartialVoidForFullCancellation(String status){
        var a=kakaoCancellation();HTTP.cancelStatus=500;payments.processCancellation(a.id());HTTP.lookupStatus=status;
        assertEquals(PaymentAttempt.Status.CANCEL_PENDING,payments.reconcileCancellation(a.id()).status());assertEquals(CANCEL_PENDING,state());assertEquals(1,HTTP.cancelCalls.get());
    }
    @Test void cancelClaimRollbackDoesNotSendMoneyRequest(){
        var a=kakaoCancellation();doAnswer(call->{em.flush();throw new DataAccessResourceFailureException("claim failure");}).when(attempts).flush();
        expect(503,()->payments.processCancellation(a.id()));reset(attempts);assertNull(view(a.id()).cancelClaim());assertEquals(0,HTTP.cancelCalls.get());
        assertEquals(1,worker.drain(20));assertEquals(CANCELLED,state());
    }
    @Test void cancelSuccessDatabaseRollbackIsRecoveredAfterRestartByQueryOnly(){
        var a=kakaoCancellation();var flushes=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{em.flush();if(flushes.incrementAndGet()==2)throw new DataAccessResourceFailureException("cancel result rollback");return null;}).when(attempts).flush();
        expect(503,()->payments.processCancellation(a.id()));reset(attempts);
        assertEquals(PaymentAttempt.Status.CANCEL_PENDING,view(a.id()).status());assertEquals(CANCEL_PENDING,state());assertNotNull(view(a.id()).cancelClaim());
        new PaymentOrchestrator(stages,registry,pgSecrets).reconcileCancellation(a.id());
        assertEquals(CANCELLED,state());assertEquals(PaymentAttempt.Status.CANCELED,view(a.id()).status());assertEquals(1,HTTP.cancelCalls.get());assertEquals(1,HTTP.orderCalls.get());
    }
    @Test void eightCancellationWorkersCannotDispatchAnotherVoid()throws Exception{
        var a=kakaoCancellation();HTTP.block();
        try(var executor=Executors.newFixedThreadPool(8)){
            var first=executor.submit(()->worker.drain(20));
            try{
                assertTrue(HTTP.entered.await(5,TimeUnit.SECONDS));var followers=new ArrayList<Future<Integer>>();
                for(int i=0;i<7;i++)followers.add(executor.submit(()->worker.drain(20)));
                for(var f:followers)assertEquals(0,f.get(10,TimeUnit.SECONDS));
                assertNotNull(view(a.id()).cancelClaim());assertEquals(1,HTTP.cancelCalls.get());
            }finally{HTTP.release.countDown();}
            assertEquals(1,first.get(10,TimeUnit.SECONDS));assertEquals(1,HTTP.cancelCalls.get());assertEquals(CANCELLED,state());
        }
    }
    @Test void parallelRecoveryWorkersAndLegacyCancellationWorkerDispatchOneCancel()throws Exception{
        var a=kakaoCancellation();HTTP.block();
        try(var executor=Executors.newFixedThreadPool(8)){
            var first=executor.submit(()->recovery.drain(20));
            try{
                assertTrue(HTTP.entered.await(5,TimeUnit.SECONDS));var followers=new ArrayList<Future<Integer>>();
                for(int i=0;i<6;i++)followers.add(executor.submit(()->recovery.drain(20)));
                followers.add(executor.submit(()->worker.drain(20)));
                for(var f:followers)assertEquals(0,f.get(10,TimeUnit.SECONDS));assertEquals(1,HTTP.cancelCalls.get());
            }finally{HTTP.release.countDown();}
            assertEquals(1,first.get(10,TimeUnit.SECONDS));assertEquals(CANCELLED,state());assertEquals(1,HTTP.cancelCalls.get());
        }
    }
    @Test void exhaustedCancellationLookupsRecordManualActionWithoutLosingIntent(){
        var a=kakaoCancellation();HTTP.cancelStatus=500;payments.processCancellation(a.id());
        var settings=new com.stayon.stayon_backend.config.PaymentRecoveryProperties();settings.setInitialGrace(Duration.ZERO);settings.setMaxAttempts(1);
        var restart=new PaymentRecoveryWorker(attempts,stages,payments,settings,clock);assertEquals(1,restart.drain(20));assertEquals(0,restart.drain(20));
        tx(()->{var saved=em.find(PaymentAttempt.class,a.id());assertEquals("EXHAUSTED_RESULT_UNKNOWN",saved.getRecoveryFailure());assertNotNull(saved.getRecoveryFailedAt());assertNotNull(saved.getCancelRequestedAt());return null;});
        assertEquals(CANCEL_PENDING,state());assertEquals(1,HTTP.cancelCalls.get());
    }
    @Test void ordinaryPaidOrderStillCannotUseCustomerCancellationAsRefund(){
        var a=kakao();readyService.ready(authentication,a.id());payments.approve(authentication,a.id(),proof());
        expect(409,()->orders.cancel(authentication,orderId));assertEquals(0,worker.drain(20));assertEquals(PAID,state());assertEquals(0,HTTP.cancelCalls.get());
    }
    @Test void expiredCancellationLeaseRejectsOldResultAndResumesAsLookup(){
        var a=kakaoCancellation();var settings=new com.stayon.stayon_backend.config.PaymentRecoveryProperties();settings.setInitialGrace(Duration.ZERO);
        var old=stages.claimRecovery(a.id(),settings);assertNotNull(old);var claim=stages.claimCancellation(a.id(),old.token()).view();
        PgAdapter.CancelResult result;try(var secret=pgSecrets.resolve(claim.merchant())){result=registry.require("KAKAOPAY").cancel(claim.command(true),secret);}
        tx(()->{ReflectionTestUtils.setField(em.find(PaymentAttempt.class,a.id()),"recoveryLeaseUntil",clock.instant().minusSeconds(1));return null;});
        var newer=stages.claimRecovery(a.id(),settings);assertNotNull(newer);assertEquals(PaymentAttempt.RecoveryKind.CANCEL_LOOKUP,newer.kind());
        expect(409,()->stages.applyCancellation(a.id(),claim.cancelClaim(),result,old.token()));assertEquals(CANCEL_PENDING,state());
        assertFalse(stages.finishRecovery(old,Duration.ofSeconds(1),"STALE"));
        payments.reconcileCancellation(a.id(),newer.token());stages.finishRecovery(newer,Duration.ofSeconds(1),"RESULT_UNKNOWN");
        assertEquals(CANCELLED,state());assertEquals(1,HTTP.cancelCalls.get());assertEquals(1,HTTP.orderCalls.get());
    }
    @Test void inactiveOriginalMerchantRemainsUsableForCancellationAndReconciliation(){
        var a=kakaoCancellation();tx(()->{em.find(PgMerchant.class,kakaoMerchant).setActive(false,clock.instant());return null;});
        HTTP.delayedOperation="cancel";payments.processCancellation(a.id());HTTP.delayedOperation=null;
        assertEquals(PaymentAttempt.Status.CANCELED,payments.reconcileCancellation(a.id()).status());assertEquals(kakaoMerchant,view(a.id()).merchant().id());assertEquals("TC0ONETIME",HTTP.lastCancel.path("cid").asString());
    }
    @Test void unapprovedUnknownTransactionNeverSendsCancel(){
        var a=kakao();readyService.ready(authentication,a.id());HTTP.approveStatus=500;payments.approve(authentication,a.id(),proof());
        orders.cancel(authentication,orderId);assertEquals(0,worker.drain(20));assertEquals(PaymentAttempt.Status.UNKNOWN,payments.processCancellation(a.id()).status());assertEquals(0,HTTP.cancelCalls.get());
    }
    @Test void mismatchedApprovedTidAndEnvironmentAreRejectedBeforeHttp(){
        var a=kakaoCancellation();var c=a.command(true);var adapter=registry.require("KAKAOPAY");
        try(var secret=pgSecrets.resolve(a.merchant())){
            var wrongTid=new PgAdapter.Command(c.reference(),c.provider(),c.orderId(),c.amount(),c.currency(),c.operationKey(),"T9999999999999999999",c.merchant(),c.preparation());
            expect(409,()->adapter.cancel(wrongTid,secret));
            var m=c.merchant();var live=new PgMerchantContext(m.id(),m.ownerId(),m.provider(),PgMerchant.Environment.LIVE,m.externalId(),m.secretReference());
            expect(409,()->adapter.cancel(new PgAdapter.Command(c.reference(),c.provider(),c.orderId(),c.amount(),c.currency(),c.operationKey(),c.transactionId(),live,c.preparation()),secret));
        }
        assertEquals(0,HTTP.cancelCalls.get());assertEquals(CANCEL_PENDING,state());
    }
    @Test void realTransportCidEnvironmentGuardRunsBeforeCancelHttp(){
        var a=kakaoCancellation();var c=a.command(true);var real=new KakaoPayProperties();
        var environments=new com.stayon.stayon_backend.config.PgMerchantProperties();var m=c.merchant();
        var adapter=new KakaoPayAdapter(kakaoHttp,real,environments,new KakaoPayUrls(real));
        try(var secret=pgSecrets.resolve(m)){
            environments.setEnvironment(PgMerchant.Environment.LIVE);
            var live=new PgMerchantContext(m.id(),m.ownerId(),m.provider(),PgMerchant.Environment.LIVE,"TC0ONETIME",m.secretReference());
            expect(409,()->adapter.cancel(new PgAdapter.Command(c.reference(),c.provider(),c.orderId(),c.amount(),c.currency(),c.operationKey(),c.transactionId(),live,c.preparation()),secret));
            environments.setEnvironment(PgMerchant.Environment.SANDBOX);
            var sandbox=new PgMerchantContext(m.id(),m.ownerId(),m.provider(),PgMerchant.Environment.SANDBOX,"MOCKCID999",m.secretReference());
            expect(409,()->adapter.cancel(new PgAdapter.Command(c.reference(),c.provider(),c.orderId(),c.amount(),c.currency(),c.operationKey(),c.transactionId(),sandbox,c.preparation()),secret));
        }assertEquals(0,HTTP.cancelCalls.get());
    }
    @Test void missingCancelSecretLeavesFinancialClaimAvailableWithoutHttp(){
        var a=kakaoCancellation();var settings=new com.stayon.stayon_backend.config.PgMerchantProperties();settings.setEnvironment(PgMerchant.Environment.SANDBOX);
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles("test");
        var noSecrets=new PgSecrets(merchantRows,settings,factory.getBeanProvider(PgSecretResolver.class),new PgRuntimeGuard(env),manager);
        expect(503,()->new PaymentOrchestrator(stages,registry,noSecrets).processCancellation(a.id()));
        assertNull(view(a.id()).cancelClaim());assertTrue(view(a.id()).cancelRequested());assertEquals(0,HTTP.cancelCalls.get());assertEquals(1,worker.drain(20));
    }
    @Test void readyPersistsTidReturnsUrlsAndApprovesThroughExistingStateMachine()throws Exception{
        var a=kakao();
        mvc.perform(post("/customer/payment-attempts/"+a.id()+"/ready").header("Authorization",bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("preparationStatus").value("PREPARED")).andExpect(jsonPath("pcUrl").value("https://online-pay.kakao.com/mock/pc/1"))
                .andExpect(jsonPath("tid").doesNotExist()).andExpect(jsonPath("merchant").doesNotExist());
        var saved=view(a.id());assertEquals("T0000000000000000001",saved.preparation().tid());assertEquals(kakaoMerchant,saved.merchant().id());
        assertEquals(2400,HTTP.lastReady.path("total_amount").longValue());assertEquals(0,HTTP.lastReady.path("tax_free_amount").longValue());assertEquals("TC0ONETIME",HTTP.lastReady.path("cid").asString());
        assertEquals("SECRET_KEY "+FakePgSecretResolver.value(kakaoMerchant),HTTP.lastAuthorization);
        assertNotEquals(reservationId.toString(),HTTP.lastReady.path("partner_user_id").asString());
        String body="{\"authentication\":{\"version\":\"KAKAOPAY_V1\",\"payload\":{\"state\":\""+stateNonce()+"\",\"pg_token\":\"ephemeral-test-token\"}}}";
        mvc.perform(post("/customer/payment-attempts/"+a.id()+"/approve").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("APPROVED")).andExpect(jsonPath("transactionId").doesNotExist());
        assertEquals(PAID,state());assertEquals(saved.preparation().tid(),view(a.id()).transactionId());assertEquals(1,HTTP.approveCalls.get());
        assertEquals(2400,HTTP.lastApprove.path("total_amount").longValue());
    }
    @Test void readyReplayAndDuplicateApprovalNeverResendFinancialRequests(){
        var a=kakao();var first=readyService.ready(authentication,a.id());var repeated=readyService.ready(authentication,a.id());assertEquals(first,repeated);assertEquals(1,HTTP.readyCalls.get());
        payments.approve(authentication,a.id(),proof());payments.approve(authentication,a.id(),proof());assertEquals(1,HTTP.approveCalls.get());
    }
    @Test void parallelReadyRequestsHaveOneWinnerAndOneStoredTid()throws Exception{
        var a=kakao();HTTP.block();
        try(var executor=Executors.newFixedThreadPool(8)){
            var first=executor.submit(()->readyService.ready(authentication,a.id()));
            try{assertTrue(HTTP.entered.await(5,TimeUnit.SECONDS));var followers=new ArrayList<Future<com.stayon.stayon_backend.dto.customer.CustomerPaymentReadyResponse>>();
                for(int i=0;i<7;i++)followers.add(executor.submit(()->readyService.ready(authentication,a.id())));
                for(var f:followers)assertEquals(PaymentAttempt.PreparationStatus.REQUESTING,f.get(10,TimeUnit.SECONDS).preparationStatus());
            }finally{HTTP.release.countDown();}
            assertEquals(PaymentAttempt.PreparationStatus.PREPARED,first.get(10,TimeUnit.SECONDS).preparationStatus());assertEquals(1,HTTP.readyCalls.get());
        }
    }
    @Test void parallelApprovalHasOneConsumedAuthenticationContext()throws Exception{
        var a=kakao();readyService.ready(authentication,a.id());HTTP.block();var auth=proof();
        try(var executor=Executors.newFixedThreadPool(8)){
            var first=executor.submit(()->payments.approve(authentication,a.id(),auth));
            try{assertTrue(HTTP.entered.await(5,TimeUnit.SECONDS));var followers=new ArrayList<Future<PaymentTransactions.View>>();
                for(int i=0;i<7;i++)followers.add(executor.submit(()->payments.approve(authentication,a.id(),auth)));
                for(var f:followers)assertEquals(PaymentAttempt.Status.APPROVING,f.get(10,TimeUnit.SECONDS).status());
            }finally{HTTP.release.countDown();}
            assertEquals(PaymentAttempt.Status.APPROVED,first.get(10,TimeUnit.SECONDS).status());assertEquals(1,HTTP.approveCalls.get());
        }
    }
    @Test void callbackCannotBeForgedReboundToAnotherSessionOrUsedAfterExpiry(){
        var a=kakao();readyService.ready(authentication,a.id());
        expect(409,()->payments.approve(authentication,a.id(),new PgAdapter.BrowserAuthentication("KAKAOPAY_V1",Map.of("state","x".repeat(43),"pg_token","test"))));
        var other=auth(session(reservationId,Set.of("orders:create")));expect(409,()->payments.approve(other,a.id(),proof()));
        tx(()->{ReflectionTestUtils.setField(em.find(PaymentAttempt.class,a.id()),"callbackExpiresAt",clock.instant().minusSeconds(1));return null;});
        expect(409,()->payments.approve(authentication,a.id(),proof()));assertEquals(0,HTTP.approveCalls.get());assertEquals(PENDING_PAYMENT,state());
    }
    @Test void authenticationAndBodyTamperingAreRejectedBeforeReadyHttp()throws Exception{
        var a=kakao();mvc.perform(post("/customer/payment-attempts/"+a.id()+"/ready")).andExpect(status().isUnauthorized());
        expect(404,()->readyService.ready(foreignAuthentication,a.id()));var limited=auth(session(reservationId,Set.of("orders:read")));expect(403,()->readyService.ready(limited,a.id()));
        mvc.perform(post("/customer/payment-attempts/"+a.id()+"/ready").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content("{\"tid\":\"chosen\",\"amount\":1}"))
                .andExpect(status().isBadRequest());assertEquals(0,HTTP.readyCalls.get());
    }
    @ParameterizedTest @ValueSource(ints={400,401,429,500,503})
    void readyHttpErrorsBecomeUncertainAndAreNotBlindlyResent(int code){
        var a=kakao();HTTP.readyStatus=code;assertEquals(PaymentAttempt.PreparationStatus.UNKNOWN,readyService.ready(authentication,a.id()).preparationStatus());
        readyService.ready(authentication,a.id());assertEquals(1,HTTP.readyCalls.get());assertEquals(PENDING_PAYMENT,state());
    }
    @Test void readyTimeoutCannotBeRecoveredByInventingATidOrReplayingReady(){
        var a=kakao();HTTP.delayedOperation="ready";assertEquals(PaymentAttempt.PreparationStatus.UNKNOWN,readyService.ready(authentication,a.id()).preparationStatus());
        assertNull(view(a.id()).preparation().tid());readyService.ready(authentication,a.id());assertEquals(1,HTTP.readyCalls.get());assertEquals(0,recovery.drain(20));
    }
    @Test void malformedReadyAndUntrustedRedirectDoNotPersistTid(){
        var a=kakao();HTTP.badUrl=true;assertEquals(PaymentAttempt.PreparationStatus.UNKNOWN,readyService.ready(authentication,a.id()).preparationStatus());assertNull(view(a.id()).preparation().tid());
    }
    @Test void successAtPgWithReadyDatabaseRollbackDoesNotCreateAnotherPreparation(){
        var a=kakao();var flushes=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{em.flush();int n=flushes.incrementAndGet();if(n==2||n==3)throw new DataAccessResourceFailureException("injected ready save failure");return null;}).when(attempts).flush();
        expect(503,()->readyService.ready(authentication,a.id()));reset(attempts);
        assertNull(view(a.id()).preparation().tid());assertEquals(PaymentAttempt.PreparationStatus.UNKNOWN,view(a.id()).preparation().status());
        readyService.ready(authentication,a.id());assertEquals(1,HTTP.readyCalls.get());
    }
    @Test void knownReadyResultRetriesOnlyTheDatabaseOnce(){
        var a=kakao();var flushes=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{em.flush();if(flushes.incrementAndGet()==2)throw new DataAccessResourceFailureException("one transient DB failure");return null;}).when(attempts).flush();
        assertEquals(PaymentAttempt.PreparationStatus.PREPARED,readyService.ready(authentication,a.id()).preparationStatus());reset(attempts);
        assertEquals(1,HTTP.readyCalls.get());assertNotNull(view(a.id()).preparation().tid());
    }
    @Test void readyClaimRollbackNeverSendsAnHttpRequest(){
        var a=kakao();doAnswer(call->{em.flush();throw new DataAccessResourceFailureException("claim failed");}).when(attempts).flush();
        expect(503,()->readyService.ready(authentication,a.id()));reset(attempts);
        assertEquals(PaymentAttempt.PreparationStatus.NONE,view(a.id()).preparation().status());assertEquals(0,HTTP.readyCalls.get());
        assertEquals(PaymentAttempt.PreparationStatus.PREPARED,readyService.ready(authentication,a.id()).preparationStatus());
    }
    @Test void approvalTimeoutIsReconciledByOrderLookupWithoutAnotherApprove(){
        var a=kakao();readyService.ready(authentication,a.id());HTTP.delayedOperation="approve";
        assertEquals(PaymentAttempt.Status.UNKNOWN,payments.approve(authentication,a.id(),proof()).status());assertEquals(PAYMENT_PROCESSING,state());
        HTTP.delayedOperation=null;recovery.drain(20);assertEquals(PAID,state());assertEquals(1,HTTP.approveCalls.get());assertEquals(1,HTTP.orderCalls.get());
    }
    @Test void approvalSuccessWithDatabaseRollbackCanBeRecoveredAfterRestart(){
        var a=kakao();readyService.ready(authentication,a.id());var flushes=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{em.flush();if(flushes.incrementAndGet()==2)throw new DataAccessResourceFailureException("injected approve save failure");return null;}).when(attempts).flush();
        expect(503,()->payments.approve(authentication,a.id(),proof()));reset(attempts);assertEquals(PaymentAttempt.Status.APPROVING,view(a.id()).status());
        new PaymentOrchestrator(stages,registry,pgSecrets).reconcileApproval(a.id());assertEquals(PAID,state());assertEquals(1,HTTP.approveCalls.get());
    }
    @ParameterizedTest @ValueSource(strings={"cid","tid","amount","user","json"})
    void invalidApprovalResponsesNeverMarkTheOrderPaid(String field){
        var a=kakao();readyService.ready(authentication,a.id());HTTP.badCid=field.equals("cid");HTTP.badTid=field.equals("tid");HTTP.badAmount=field.equals("amount");HTTP.badUser=field.equals("user");HTTP.invalidJson=field.equals("json");
        assertEquals(PaymentAttempt.Status.UNKNOWN,payments.approve(authentication,a.id(),proof()).status());assertEquals(PAYMENT_PROCESSING,state());assertNull(view(a.id()).transactionId());
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,429,500,503})
    void approvalHttpFailureIsNotFinalAndCannotTriggerAnotherApprove(int code){
        var a=kakao();readyService.ready(authentication,a.id());HTTP.approveStatus=code;
        assertEquals(PaymentAttempt.Status.UNKNOWN,payments.approve(authentication,a.id(),proof()).status());payments.approve(authentication,a.id(),proof());
        assertEquals(1,HTTP.approveCalls.get());assertEquals(PAYMENT_PROCESSING,state());
    }
    @Test void tokenNonceAndSecretAreNotPersistedInPaymentAttempt(){
        var a=kakao();readyService.ready(authentication,a.id());String nonce=stateNonce();payments.approve(authentication,a.id(),proof());
        Object[] columns=tx(()->(Object[])em.createNativeQuery("select * from payment_attempt where id=:id").setParameter("id",a.id()).getSingleResult());
        for(Object value:columns)if(value instanceof String text){assertFalse(text.contains("ephemeral-test-token"));assertFalse(text.contains(nonce));assertFalse(text.contains(FakePgSecretResolver.value(kakaoMerchant)));}
        assertEquals(PaymentAttempt.digest(nonce),tx(()->em.find(PaymentAttempt.class,a.id()).getCallbackStateHash()));
    }
    @ParameterizedTest @ValueSource(strings={"READY","AUTH_PASSWORD","FAIL_PAYMENT","QUIT_PAYMENT","PART_CANCEL_PAYMENT","CANCEL_PAYMENT","FUTURE_STATUS"})
    void nonFinalOrUnsupportedOrderStatusesRemainUnknown(String status){
        var a=kakao();readyService.ready(authentication,a.id());HTTP.approveStatus=500;payments.approve(authentication,a.id(),proof());HTTP.lookupStatus=status;
        assertEquals(PaymentAttempt.Status.UNKNOWN,payments.reconcileApproval(a.id()).status());assertEquals(PAYMENT_PROCESSING,state());assertEquals(1,HTTP.approveCalls.get());
    }
    @Test void cancelDuringApprovalPersistsIntentAndVoidsTheApprovedTransaction()throws Exception{
        var a=kakao();readyService.ready(authentication,a.id());HTTP.block();var auth=proof();
        try(var executor=Executors.newSingleThreadExecutor()){
            var approval=executor.submit(()->payments.approve(authentication,a.id(),auth));
            try{assertTrue(HTTP.entered.await(5,TimeUnit.SECONDS));assertTrue(orders.cancel(authentication,orderId).accepted());}finally{HTTP.release.countDown();}
            assertEquals(PaymentAttempt.Status.CANCEL_PENDING,approval.get(10,TimeUnit.SECONDS).status());assertEquals(CANCEL_PENDING,state());
            assertEquals(PaymentAttempt.Status.CANCELED,payments.processCancellation(a.id()).status());assertNotNull(view(a.id()).cancelClaim());assertEquals(1,HTTP.approveCalls.get());assertEquals(1,HTTP.cancelCalls.get());assertEquals(CANCELLED,state());
        }
    }
    @Test void inactiveMerchantCannotStartApprovalButCanRecoverExistingTransaction(){
        var a=kakao();readyService.ready(authentication,a.id());tx(()->{em.find(PgMerchant.class,kakaoMerchant).setActive(false,clock.instant());return null;});
        expect(409,()->payments.approve(authentication,a.id(),proof()));assertEquals(0,HTTP.approveCalls.get());
        tx(()->{em.find(PgMerchant.class,kakaoMerchant).setActive(true,clock.instant());return null;});HTTP.approveStatus=500;payments.approve(authentication,a.id(),proof());
        tx(()->{em.find(PgMerchant.class,kakaoMerchant).setActive(false,clock.instant());return null;});payments.reconcileApproval(a.id());assertEquals(PAID,state());
    }
    @Test void callbackAndTaxConfigurationMustBeExplicitAndHttps(){
        var a=kakao();kakaoSettings.setTaxMode(KakaoPayProperties.TaxMode.UNCONFIGURED);expect(503,()->readyService.ready(authentication,a.id()));
        kakaoSettings.setTaxMode(KakaoPayProperties.TaxMode.ALL_TAXABLE);kakaoSettings.setApprovalUrl("http://frontend.example.invalid/callback");expect(503,()->readyService.ready(authentication,a.id()));
        kakaoSettings.setApprovalUrl("https://evil.example.invalid/callback");expect(503,()->readyService.ready(authentication,a.id()));assertEquals(0,HTTP.readyCalls.get());
    }
    @Test void callbackStateCannotMoveToAnotherAttemptAndTidCannotBeRebound(){
        var a=kakao();readyService.ready(authentication,a.id());var original=proof();var saved=view(a.id());
        Long secondOrder=orders.create(authentication,"another-kakao-order",request()).order().id();var second=payments.createAttempt(authentication,secondOrder,"KAKAOPAY","another-kakao-attempt");
        readyService.ready(authentication,second.id());expect(409,()->payments.approve(authentication,second.id(),original));
        expect(409,()->stages.applyPreparation(a.id(),saved.preparation().claim(),"T9999999999999999999",saved.preparation().pcUrl(),saved.preparation().mobileUrl()));
        assertEquals(saved.preparation().tid(),view(a.id()).preparation().tid());assertEquals(0,HTTP.approveCalls.get());
    }
    @Test void anotherBusinessCannotSelectThisCidOrUseItsCallback(){
        var a=kakao();readyService.ready(authentication,a.id());var original=proof();var other=tenant(false);
        tx(()->{merchant(em.find(User.class,other.owner()),"KAKAOPAY","MOCKCID002",Set.of("KRW"));return null;});
        var second=payments.createAttempt(other.authentication(),other.order(),"KAKAOPAY","other-owner-kakao");readyService.ready(other.authentication(),second.id());
        assertEquals("MOCKCID002",HTTP.lastReady.path("cid").asString());expect(404,()->payments.approve(other.authentication(),a.id(),original));
        expect(409,()->payments.approve(other.authentication(),second.id(),original));assertEquals(0,HTTP.approveCalls.get());
    }
    @Test void readyCancellationAndMerchantDeactivationCannotLeadToACharge()throws Exception{
        var a=kakao();HTTP.block();
        try(var executor=Executors.newSingleThreadExecutor()){
            var request=executor.submit(()->{expect(409,()->readyService.ready(authentication,a.id()));return true;});
            try{assertTrue(HTTP.entered.await(5,TimeUnit.SECONDS));assertFalse(orders.cancel(authentication,orderId).accepted());}finally{HTTP.release.countDown();}
            assertTrue(request.get(10,TimeUnit.SECONDS));assertEquals(CANCELLED,state());assertEquals(PaymentAttempt.PreparationStatus.PREPARED,view(a.id()).preparation().status());assertEquals(0,HTTP.approveCalls.get());
        }
    }
    @Test void kakaoBrowserRedirectIsNotAnInventedWebhook()throws Exception{
        mvc.perform(post("/payments/webhooks/KAKAOPAY").content("pg_token=test")).andExpect(status().isNotFound());
    }
    @Test void missingSecretResolverFailsBeforeReadyClaim(){
        var settings=new com.stayon.stayon_backend.config.PgMerchantProperties();settings.setEnvironment(PgMerchant.Environment.SANDBOX);
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles("test");
        var noSecrets=new PgSecrets(merchantRows,settings,factory.getBeanProvider(PgSecretResolver.class),new PgRuntimeGuard(env),manager);
        factory.addBean("adapter",registry.require("KAKAOPAY"));
        var service=new KakaoPayReadyService(stages,noSecrets,factory.getBeanProvider(KakaoPayAdapter.class),kakaoSettings,new KakaoPayUrls(kakaoSettings),clock);
        var a=kakao();expect(503,()->service.ready(authentication,a.id()));assertEquals(PaymentAttempt.PreparationStatus.NONE,view(a.id()).preparation().status());assertEquals(0,HTTP.readyCalls.get());
    }
}
