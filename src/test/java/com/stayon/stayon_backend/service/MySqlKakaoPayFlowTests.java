package com.stayon.stayon_backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MySqlKakaoPayFlowTests.Lifecycle.class)
class MySqlKakaoPayFlowTests extends KakaoPayFlowTests {
    private static final OwnedMySql MYSQL=OwnedMySql.start();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",MYSQL::jdbcUrl);p.add("spring.datasource.username",()->"root");p.add("spring.datasource.password",()->"");
        p.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");p.add("spring.datasource.hikari.maximum-pool-size",()->"16");p.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
    }
    @TestConfiguration(proxyBeanMethods=false)static class Lifecycle{
        @Bean(destroyMethod="close")OwnedMySql ownedMySql(){return MYSQL;}
        @Bean static BeanFactoryPostProcessor dependency(){return f->f.getBeanDefinition("dataSource").setDependsOn("ownedMySql");}
    }
    @Test void mysqlReadyHttpHoldsNoReservationOrderMerchantOrAttemptLocks()throws Exception{
        var a=kakao();HTTP.block();
        try(var executor=Executors.newSingleThreadExecutor()){
            var ready=executor.submit(()->readyService.ready(authentication,a.id()));
            try{
                assertTrue(HTTP.entered.await(5,TimeUnit.SECONDS));
                try(var connection=MYSQL.connection()){
                    connection.setAutoCommit(false);
                    for(var pair:java.util.List.of(new Object[]{"reservation",reservationId},new Object[]{"service_order",orderId},new Object[]{"pg_merchant",kakaoMerchant},new Object[]{"payment_attempt",a.id()})){
                        try(var statement=connection.prepareStatement("select id from "+pair[0]+" where id=? for update")){statement.setObject(1,pair[1]);try(var result=statement.executeQuery()){assertTrue(result.next());}}
                    }connection.rollback();
                }
            }finally{HTTP.release.countDown();}
            assertEquals(com.stayon.stayon_backend.entity.PaymentAttempt.PreparationStatus.PREPARED,ready.get(10,TimeUnit.SECONDS).preparationStatus());
        }
    }
    @Test void mysqlInactiveMerchantRaceRejectsPreparationBeforeHttp()throws Exception{
        var a=kakao();
        try(var connection=MYSQL.connection();var executor=Executors.newSingleThreadExecutor()){
            connection.setAutoCommit(false);try(var update=connection.prepareStatement("update pg_merchant set active=false,active_owner_id=null where id=?")){update.setString(1,kakaoMerchant);update.executeUpdate();}
            var ready=executor.submit(()->{expect(409,()->readyService.ready(authentication,a.id()));return true;});
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);boolean waited=false;
            try(var monitor=MYSQL.connection();var query=monitor.prepareStatement("select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.ENGINE=w.ENGINE and l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID where l.OBJECT_SCHEMA=? and l.OBJECT_NAME='pg_merchant'")){
                query.setString(1,OwnedMySql.SCHEMA);while(System.nanoTime()<deadline){try(var result=query.executeQuery()){result.next();if(result.getInt(1)>0){waited=true;break;}}Thread.sleep(10);}
            }
            assertTrue(waited);connection.commit();assertTrue(ready.get(10,TimeUnit.SECONDS));assertEquals(0,HTTP.readyCalls.get());
        }
    }
    @Test void mysqlCancelHttpHoldsNoReservationOrderMerchantOrAttemptLocks()throws Exception{
        var a=kakaoCancellation();HTTP.block();
        try(var executor=Executors.newSingleThreadExecutor()){
            var cancel=executor.submit(()->payments.processCancellation(a.id()));
            try{
                assertTrue(HTTP.entered.await(5,TimeUnit.SECONDS));
                try(var connection=MYSQL.connection()){
                    connection.setAutoCommit(false);
                    try(var statement=connection.createStatement()){statement.execute("set session innodb_lock_wait_timeout=1");}
                    for(var pair:java.util.List.of(new Object[]{"reservation",reservationId},new Object[]{"service_order",orderId},new Object[]{"pg_merchant",kakaoMerchant},new Object[]{"payment_attempt",a.id()})){
                        try(var statement=connection.prepareStatement("select id from "+pair[0]+" where id=? for update")){statement.setObject(1,pair[1]);try(var result=statement.executeQuery()){assertTrue(result.next());}}
                    }connection.rollback();
                }
            }finally{HTTP.release.countDown();}
            assertEquals(com.stayon.stayon_backend.entity.PaymentAttempt.Status.CANCELED,cancel.get(10,TimeUnit.SECONDS).status());assertEquals(1,HTTP.cancelCalls.get());
        }
    }
    @Test void mysqlCancelClaimLockTimeoutRollsBackWithoutSendingCancel()throws Exception{
        var a=kakaoCancellation();
        try(var connection=MYSQL.connection()){
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("select id from payment_attempt where id=? for update")){
                statement.setString(1,a.id());try(var result=statement.executeQuery()){assertTrue(result.next());}
            }
            expect(503,()->payments.processCancellation(a.id()));assertEquals(0,HTTP.cancelCalls.get());connection.rollback();
        }
        assertNull(view(a.id()).cancelClaim());assertEquals(com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.CANCEL_PENDING,state());
        assertEquals(1,worker.drain(20));assertEquals(1,HTTP.cancelCalls.get());
    }
    @Test void mysqlDeadlockAfterRemoteCancelRollsBackBothStatesAndRecoversByLookup()throws Exception{
        var a=kakaoCancellation();HTTP.block();
        try(var setup=MYSQL.connection();var statement=setup.createStatement()){
            statement.execute("create table if not exists mysql_test_cancel_weight(id int primary key, weight_value int not null) engine=InnoDB");
            for(int i=0;i<50;i++)statement.executeUpdate("insert into mysql_test_cancel_weight values("+i+",0) on duplicate key update weight_value=0");
        }
        long before=deadlocks();
        try(var executor=Executors.newSingleThreadExecutor();var writer=MYSQL.connection()){
            var cancel=executor.submit(()->payments.processCancellation(a.id()));assertTrue(HTTP.entered.await(5,TimeUnit.SECONDS));
            writer.setAutoCommit(false);lock(writer,"payment_attempt",a.id());
            try(var statement=writer.createStatement()){statement.executeUpdate("update mysql_test_cancel_weight set weight_value=weight_value+1");}
            var route=attempts.findRoute(a.id()).orElseThrow();var applying=new CountDownLatch(1);var connectionId=new java.util.concurrent.atomic.AtomicLong();
            doAnswer(call->{connectionId.set(((Number)em.createNativeQuery("select connection_id()").getSingleResult()).longValue());applying.countDown();return java.util.Optional.of(route);}).when(attempts).findRoute(a.id());
            HTTP.release.countDown();assertTrue(applying.await(5,TimeUnit.SECONDS));awaitWait(connectionId.get());
            lock(writer,"service_order",orderId);writer.rollback();
            var error=assertThrows(ExecutionException.class,()->cancel.get(10,TimeUnit.SECONDS));
            assertEquals(503,((org.springframework.web.server.ResponseStatusException)error.getCause()).getStatusCode().value());assertEquals(before+1,deadlocks());reset(attempts);
            assertEquals(com.stayon.stayon_backend.entity.PaymentAttempt.Status.CANCEL_PENDING,view(a.id()).status());
            assertEquals(com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.CANCEL_PENDING,state());assertNotNull(view(a.id()).cancelClaim());
            payments.reconcileCancellation(a.id());assertEquals(com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.CANCELLED,state());assertEquals(1,HTTP.cancelCalls.get());
        }finally{HTTP.release.countDown();reset(attempts);}
    }
    private void lock(java.sql.Connection connection,String table,Object id)throws Exception{
        try(var statement=connection.prepareStatement("select id from "+table+" where id=? for update")){
            statement.setObject(1,id);try(var result=statement.executeQuery()){assertTrue(result.next());}
        }
    }
    private void awaitWait(long id)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);
        try(var connection=MYSQL.connection();var statement=connection.prepareStatement("select count(*) from performance_schema.data_lock_waits w join performance_schema.threads t on t.THREAD_ID=w.REQUESTING_THREAD_ID join performance_schema.data_locks l on l.ENGINE=w.ENGINE and l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID where t.PROCESSLIST_ID=? and l.OBJECT_SCHEMA=? and l.OBJECT_NAME='payment_attempt'")){
            statement.setLong(1,id);statement.setString(2,OwnedMySql.SCHEMA);
            while(System.nanoTime()<deadline){try(var result=statement.executeQuery()){result.next();if(result.getInt(1)>0)return;}Thread.sleep(10);}
        }fail("Expected real InnoDB cancellation lock wait");
    }
    private long deadlocks()throws Exception{
        try(var c=MYSQL.connection();var s=c.createStatement();var r=s.executeQuery("select COUNT from information_schema.INNODB_METRICS where NAME='lock_deadlocks'")){
            assertTrue(r.next());return r.getLong(1);
        }
    }
}
