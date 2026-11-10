package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.dto.customer.CustomerOrderCreateRequest;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.MediaType;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PgMerchantRoutingTests extends PgWebhookInboxTests {
    @org.springframework.beans.factory.annotation.Autowired protected com.stayon.stayon_backend.repository.PgMerchantRepository merchantRows;
    protected record Tenant(Long owner,Long accommodation,Long item,Long site,String merchant,Long reservation,Long order,Authentication authentication){}
    private final List<Tenant> extra=new ArrayList<>();
    protected PgMerchant merchant(User owner,String provider,String external,Set<String> currencies){
        var m=new PgMerchant(owner,provider,PgMerchant.Environment.SANDBOX,external,currencies,"placeholder",clock.instant());
        ReflectionTestUtils.setField(m,"secretReference","test/"+owner.getUserId()+"/"+provider+"/"+m.getId());em.persist(m);return m;
    }
    protected Tenant tenant(boolean sameOwner){
        var tuple=tx(()->{
            var owner=sameOwner?em.find(User.class,ownerId):User.builder().email(UUID.randomUUID()+"@tenant.invalid").password("unused-test-only").name("Other owner").role(com.stayon.stayon_backend.entity.Role.MANAGER).build();
            if(!sameOwner)em.persist(owner);
            var accommodation=sameOwner?em.find(Accommodation.class,accommodationId):new Accommodation("Other accommodation",Platform.CAMFIT,owner);if(!sameOwner)em.persist(accommodation);
            var item=sameOwner?em.find(AccommodationItem.class,itemId):new AccommodationItem(UUID.randomUUID().toString(),accommodation,"Other item");if(!sameOwner)em.persist(item);
            var site=new Site("Other site");site.assignAccommodationItem(item);em.persist(site);
            var m=sameOwner?em.find(PgMerchant.class,merchantId):merchant(owner,"TEST_PG","mid-"+UUID.randomUUID(),Set.of("KRW"));
            var r=new Reservation();r.setPlatform(Platform.CAMFIT);r.setReservationId(UUID.randomUUID().toString());r.setEventType(ReservationEventType.RESERVED);r.setSyncStatus("SUCCESS");r.setSite(site);r.setCustomerPhone("01012345678");
            var today=LocalDate.now(ZoneId.of("Asia/Seoul"));r.setCheckInDate(today.minusDays(1));r.setCheckOutDate(today.plusDays(1));r.setNights(2);em.persist(r);
            var p=new AdditionalService(site,"Other product",1200,AdditionalService.ServiceInputType.QUANTITY,true,null,null,null);em.persist(p);
            return new Object[]{owner.getUserId(),accommodation.getAccommodationId(),item.getId(),site.getId(),m.getId(),r.getId(),p.getId()};
        });
        var auth=auth(session((Long)tuple[5],Set.of("orders:read","orders:create","orders:cancel")));
        Long order=orders.create(auth,"tenant-order",new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item((Long)tuple[6],2)))).order().id();
        var t=new Tenant((Long)tuple[0],(Long)tuple[1],(Long)tuple[2],(Long)tuple[3],(String)tuple[4],(Long)tuple[5],order,auth);extra.add(t);return t;
    }
    protected String replace(){return tx(()->{
        em.find(PgMerchant.class,merchantId).setActive(false,clock.instant());em.flush();
        return merchant(em.find(User.class,ownerId),"TEST_PG","replacement-"+UUID.randomUUID(),Set.of("KRW")).getId();
    });}
    @AfterEach void cleanupTenants(){
        tx(()->{
            var rootSite=em.find(Site.class,siteId);if(rootSite!=null)rootSite.assignAccommodationItem(em.find(AccommodationItem.class,itemId));
            for(var t:extra){
                em.createQuery("delete PaymentAttempt a where a.serviceOrder.reservation.id=:id").setParameter("id",t.reservation()).executeUpdate();
                em.createQuery("delete ServiceOrderItem i where i.serviceOrder.reservation.id=:id").setParameter("id",t.reservation()).executeUpdate();
                em.createQuery("delete ServiceOrder o where o.reservation.id=:id").setParameter("id",t.reservation()).executeUpdate();
                em.createQuery("delete AdditionalService p where p.site.id=:id").setParameter("id",t.site()).executeUpdate();
                em.remove(em.find(Reservation.class,t.reservation()));em.remove(em.find(Site.class,t.site()));
                if(!t.owner().equals(ownerId)){
                    em.createNativeQuery("delete from pg_merchant_currency where merchant_id in (select id from pg_merchant where owner_id=:id)").setParameter("id",t.owner()).executeUpdate();
                    em.createQuery("delete PgMerchant m where m.owner.userId=:id").setParameter("id",t.owner()).executeUpdate();
                    em.remove(em.find(AccommodationItem.class,t.item()));em.remove(em.find(Accommodation.class,t.accommodation()));em.remove(em.find(User.class,t.owner()));
                }
            }return null;
        });
    }
    @Test void twoOwnersUsingTheSameProviderNeverShareMerchantCredentials(){
        var other=tenant(false);var first=ready();var second=payments.createAttempt(other.authentication(),other.order(),"TEST_PG","other-attempt");
        assertEquals(ownerId,first.merchant().ownerId());assertEquals(other.owner(),second.merchant().ownerId());assertNotEquals(first.merchant().id(),second.merchant().id());
        payments.approve(authentication,first.id());payments.approve(other.authentication(),second.id());
        assertEquals(merchantId,pg.commands.get(0).merchant().id());assertEquals(other.merchant(),pg.commands.get(1).merchant().id());
    }
    @Test void oneOwnerCanRouteMultipleSitesThroughTheSameMerchant(){
        var other=tenant(true);var first=ready();var second=payments.createAttempt(other.authentication(),other.order(),"TEST_PG","other-site");
        assertEquals(first.merchant(),second.merchant());payments.approve(other.authentication(),second.id());assertEquals(merchantId,pg.commands.getFirst().merchant().id());
    }
    @Test void clientCannotSelectAnotherOwnersMerchant()throws Exception{
        var other=tenant(false);
        mvc.perform(post("/customer/orders/"+orderId+"/payment-attempts").header("Authorization",bearer).header("Idempotency-Key","tamper-merchant")
                .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"TEST_PG\",\"merchantId\":\""+other.merchant()+"\"}"))
                .andExpect(status().isBadRequest());assertEquals(0,attempts.count());
    }
    @Test void missingOwnershipOrMerchantRejectsNewAttempts(){
        tx(()->{ReflectionTestUtils.setField(em.find(Site.class,siteId),"accommodationItem",null);return null;});expect(409,this::ready);
        tx(()->{em.find(Site.class,siteId).assignAccommodationItem(em.find(AccommodationItem.class,itemId));em.find(PgMerchant.class,merchantId).setActive(false,clock.instant());return null;});
        expect(409,this::ready);assertEquals(0,attempts.count());
    }
    @Test void inactiveReadyCannotStartAChargeButInFlightApprovalCanStillReconcile(){
        var a=ready();tx(()->{em.find(PgMerchant.class,merchantId).setActive(false,clock.instant());return null;});expect(409,()->payments.approve(authentication,a.id()));assertEquals(0,pg.approvals.get());
        tx(()->{em.find(PgMerchant.class,merchantId).setActive(true,clock.instant());return null;});pg.mode=FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT;payments.approve(authentication,a.id());
        replace();payments.reconcileApproval(a.id());assertEquals(PAID,state());assertEquals(merchantId,pg.commands.getLast().merchant().id());
    }
    @Test void replacementNeverReroutesHistoricalApprovalCancellationOrWebhook(){
        var a=chargedUnknown();orders.cancel(authentication,orderId);String replacement=replace();
        var e=event();receive(e,a.id(),"APPROVED");webhookWorker.drain(20);assertEquals(CANCEL_PENDING,state());recovery.drain(20);assertEquals(CANCELLED,state());
        assertTrue(pg.commands.stream().allMatch(c->c.merchant().id().equals(merchantId)));
        Long another=orders.create(authentication,"new-merchant-order",request()).order().id();
        var next=payments.createAttempt(authentication,another,"TEST_PG","new-merchant-attempt");assertEquals(replacement,next.merchant().id());assertEquals(merchantId,view(a.id()).merchant().id());
    }
    @Test void ownershipTransferCannotChargeAPreviouslyPinnedReadyAttempt(){
        var a=ready();var other=tenant(false);tx(()->{em.find(Site.class,siteId).assignAccommodationItem(em.find(AccommodationItem.class,other.item()));return null;});
        expect(409,()->payments.approve(authentication,a.id()));assertEquals(0,pg.approvals.get());assertEquals(merchantId,view(a.id()).merchant().id());
    }
    @Test void databasePreventsDuplicateActiveMerchantsAndCrossOwnerReuseOfIdentity(){
        assertThrows(DataIntegrityViolationException.class,()->tx(()->{merchant(em.find(User.class,ownerId),"TEST_PG","duplicate-active",Set.of("KRW"));merchantRows.flush();return null;}));
        var other=tenant(false);String original=pgSecrets.context(merchantId).externalId();
        assertThrows(DataIntegrityViolationException.class,()->tx(()->{em.find(PgMerchant.class,other.merchant()).setActive(false,clock.instant());em.flush();merchant(em.find(User.class,other.owner()),"TEST_PG",original,Set.of("KRW"));merchantRows.flush();return null;}));
        assertEquals(merchantId,ready().merchant().id());
    }
    @Test void unavailableCurrencyAndEnvironmentCannotRoute(){
        tx(()->{em.find(PgMerchant.class,merchantId).setActive(false,clock.instant());em.flush();merchant(em.find(User.class,ownerId),"TEST_PG","usd-only",Set.of("USD"));return null;});expect(409,this::ready);
    }
    @Test void storedPinCannotBeReassigned(){
        var a=ready();var other=tenant(false);
        assertThrows(IllegalStateException.class,()->tx(()->{em.find(PaymentAttempt.class,a.id()).pinMerchant(em.find(PgMerchant.class,other.merchant()));return null;}));
        assertEquals(merchantId,view(a.id()).merchant().id());
    }
    @Test void forgedSecretOwnerProviderReferenceAndEnvironmentAreRejected(){
        var scope=pgSecrets.context(merchantId);var other=tenant(false);
        expect(503,()->pgSecrets.resolve(new PgMerchantContext(scope.id(),other.owner(),scope.provider(),scope.environment(),scope.externalId(),scope.secretReference())));
        expect(503,()->pgSecrets.resolve(new PgMerchantContext(scope.id(),scope.ownerId(),"OTHER_PG",scope.environment(),scope.externalId(),scope.secretReference())));
        expect(503,()->pgSecrets.resolve(new PgMerchantContext(scope.id(),scope.ownerId(),scope.provider(),PgMerchant.Environment.LIVE,scope.externalId(),scope.secretReference())));
        expect(503,()->pgSecrets.resolve(new PgMerchantContext(scope.id(),scope.ownerId(),scope.provider(),scope.environment(),scope.externalId(),"unauthorized/reference")));
        var secret=pgSecrets.resolve(scope);assertEquals("PgSecret[REDACTED]",secret.toString());secret.close();assertThrows(IllegalStateException.class,secret::copy);
    }
    @Test void resolverFailureIsSanitizedAndCannotConsumeAFinancialClaim(){
        var settings=new com.stayon.stayon_backend.config.PgMerchantProperties();settings.setEnvironment(PgMerchant.Environment.SANDBOX);
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("resolver",new PgSecretResolver(){
            @Override public boolean testOnly(){return true;}
            @Override public PgSecret resolve(PgMerchantContext context){throw new IllegalStateException("test-only-sensitive-upstream-message");}
        });
        var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles("test");
        var broken=new PgSecrets(merchantRows,settings,beans.getBeanProvider(PgSecretResolver.class),new PgRuntimeGuard(env),manager);
        var a=ready();expect(503,()->new PaymentOrchestrator(stages,registry,broken).approve(authentication,a.id()));
        assertEquals(PENDING_PAYMENT,state());assertEquals(PaymentAttempt.Status.READY,view(a.id()).status());assertEquals(0,pg.approvals.get());
    }
    @Test void otherMerchantsAuthenticatedWebhookCannotTargetThisAttempt(){
        var a=chargedUnknown();var other=tenant(false);var e=event();var bytes=(e+"|"+a.id()+"|APPROVED|"+other.merchant()+"|tx-"+a.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        expect(401,()->receiver.receive("TEST_PG",bytes,headers(bytes)));assertEquals(PAYMENT_PROCESSING,state());assertEquals(0,pg.lookups.get());
    }
    @Test void webhookTransactionMismatchIsRejectedAndLookupCannotAssertAnotherMerchant(){
        var a=chargedUnknown();pg.ledger.put(a.id(),new PgAdapter.ApprovalResult(a.id(),a.provider(),PgAdapter.ApprovalOutcome.APPROVED,"tx-"+a.id(),a.amount(),a.currency(),"another-mid"));
        expect(409,()->payments.reconcileApproval(a.id()));assertEquals(PAYMENT_PROCESSING,state());
        pg.ledger.put(a.id(),new PgAdapter.ApprovalResult(a.id(),a.provider(),PgAdapter.ApprovalOutcome.APPROVED,"tx-"+a.id(),a.amount(),a.currency(),a.merchant().externalId()));payments.reconcileApproval(a.id());
        var bytes=(event()+"|"+a.id()+"|APPROVED|"+merchantId+"|wrong-tx").getBytes(java.nio.charset.StandardCharsets.UTF_8);expect(401,()->receiver.receive("TEST_PG",bytes,headers(bytes)));
    }
    @Test void sameTransactionIdCanBelongToDifferentMerchantsButNotTwoOrdersOfOneMerchant(){
        var first=ready();var other=tenant(false);var second=payments.createAttempt(other.authentication(),other.order(),"TEST_PG","second");
        for(var a:List.of(first,second)){
            var auth=a.id().equals(first.id())?authentication:other.authentication();var claim=stages.claimApproval(auth,a.id()).view();
            stages.applyApproval(a.id(),claim.approvalClaim(),new PgAdapter.ApprovalResult(a.id(),a.provider(),PgAdapter.ApprovalOutcome.APPROVED,"merchant-local-tx",a.amount(),a.currency(),a.merchant().externalId()));
        }
        assertEquals(PAID,state());
    }
    @Test void identicalWebhookEventIdsAreIndependentAcrossMerchants(){
        var first=chargedUnknown();var other=tenant(false);var second=payments.createAttempt(other.authentication(),other.order(),"TEST_PG","second-webhook");
        payments.approve(other.authentication(),second.id());var e=event();receive(e,first.id(),"APPROVED");
        var bytes=(e+"|"+second.id()+"|APPROVED|"+other.merchant()+"|tx-"+second.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(receiver.receive("TEST_PG",bytes,headers(bytes)).duplicate());
        assertEquals(2L,tx(()->em.createQuery("select count(i) from PgWebhookInbox i where i.eventKeyHash=:key",Long.class).setParameter("key",PaymentAttempt.digest(e)).getSingleResult()));
        assertEquals(2,webhookWorker.drain(20));assertEquals(PAID,state());assertEquals(PaymentAttempt.Status.APPROVED,view(second.id()).status());
    }
    @Test void concurrentMerchantRegistrationHasOneDatabaseWinner()throws Exception{
        tx(()->{em.find(PgMerchant.class,merchantId).setActive(false,clock.instant());return null;});var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)futures.add(executor.submit(()->{await(start);try{tx(()->{merchant(em.find(User.class,ownerId),"TEST_PG","concurrent-"+UUID.randomUUID(),Set.of("KRW"));merchantRows.flush();return null;});return true;}catch(DataIntegrityViolationException failure){return false;}}));
            start.countDown();int successes=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))successes++;assertEquals(1,successes);assertNotNull(ready().merchant());
        }
    }
}
