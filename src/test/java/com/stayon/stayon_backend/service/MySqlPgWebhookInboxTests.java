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

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MySqlPgWebhookInboxTests.Lifecycle.class)
class MySqlPgWebhookInboxTests extends PgWebhookInboxTests {
    private static final OwnedMySql MYSQL=OwnedMySql.start();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",MYSQL::jdbcUrl);p.add("spring.datasource.username",()->"root");p.add("spring.datasource.password",()->"");
        p.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");p.add("spring.datasource.hikari.maximum-pool-size",()->"16");
        p.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
    }
    @TestConfiguration(proxyBeanMethods=false) static class Lifecycle{
        @Bean(destroyMethod="close") OwnedMySql ownedMySql(){return MYSQL;}
        @Bean static BeanFactoryPostProcessor mysqlDependency(){return f->f.getBeanDefinition("dataSource").setDependsOn("ownedMySql");}
    }
    @Test void mysqlInboxLeaseTimeoutIs503AndDoesNotReachPg()throws Exception{
        var a=chargedUnknown();var e=event();receive(e,a.id(),"APPROVED");
        try(var connection=MYSQL.connection()){
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("select id from pg_webhook_inbox where id=? for update")){
                statement.setString(1,row(e).getId());try(var rows=statement.executeQuery()){assertTrue(rows.next());}
            }
            expect(503,()->webhookTransactions.claim(row(e).getId()));assertEquals(0,pg.lookups.get());connection.rollback();
        }
        assertEquals(1,webhookWorker.drain(20));
    }
    @Test void mysqlInboxAndPaymentRowsAreUnlockedDuringPgLookup()throws Exception{
        var a=chargedUnknown();var e=event();receive(e,a.id(),"APPROVED");var inboxId=row(e).getId();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);pg.beforeLookup=command->{entered.countDown();await(release);};
        try(var executor=Executors.newSingleThreadExecutor()){
            var result=executor.submit(()->webhookWorker.drain(20));
            try{
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                try(var connection=MYSQL.connection()){
                    connection.setAutoCommit(false);
                    for(var pair:java.util.List.of(new Object[]{"reservation",reservationId},new Object[]{"service_order",orderId},new Object[]{"payment_attempt",a.id()},new Object[]{"pg_webhook_inbox",inboxId})){
                        try(var statement=connection.prepareStatement("select id from "+pair[0]+" where id=? for update")){
                            statement.setObject(1,pair[1]);try(var rows=statement.executeQuery()){assertTrue(rows.next());}
                        }
                    }
                    connection.rollback();
                }
            }finally{release.countDown();}
            assertEquals(1,result.get(10,TimeUnit.SECONDS));
        }
    }
}
