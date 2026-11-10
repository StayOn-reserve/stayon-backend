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

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MySqlPgMerchantRoutingTests.Lifecycle.class)
class MySqlPgMerchantRoutingTests extends PgMerchantRoutingTests {
    private static final OwnedMySql MYSQL=OwnedMySql.start();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",MYSQL::jdbcUrl);p.add("spring.datasource.username",()->"root");p.add("spring.datasource.password",()->"");
        p.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");p.add("spring.datasource.hikari.maximum-pool-size",()->"16");p.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
    }
    @TestConfiguration(proxyBeanMethods=false)static class Lifecycle{
        @Bean(destroyMethod="close")OwnedMySql ownedMySql(){return MYSQL;}
        @Bean static BeanFactoryPostProcessor dependency(){return f->f.getBeanDefinition("dataSource").setDependsOn("ownedMySql");}
    }
    @Test void mysqlConcurrentDeactivationCannotSlipThroughRouteRowLock()throws Exception{
        try(var connection=MYSQL.connection();var executor=Executors.newSingleThreadExecutor()){
            connection.setAutoCommit(false);try(var update=connection.prepareStatement("update pg_merchant set active=false,active_owner_id=null where id=?")){update.setString(1,merchantId);update.executeUpdate();}
            var creation=executor.submit(()->{expect(409,this::ready);return true;});awaitMerchantWait();connection.commit();assertTrue(creation.get(10,TimeUnit.SECONDS));assertEquals(0,attempts.count());
        }
    }
    private void awaitMerchantWait()throws Exception{
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);
        try(var connection=MYSQL.connection();var query=connection.prepareStatement("select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.ENGINE=w.ENGINE and l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID where l.OBJECT_SCHEMA=? and l.OBJECT_NAME='pg_merchant'")){
            query.setString(1,OwnedMySql.SCHEMA);
            while(System.nanoTime()<deadline){try(var rows=query.executeQuery()){rows.next();if(rows.getInt(1)>0)return;}Thread.sleep(10);}
        }fail("Expected an actual InnoDB merchant row wait");
    }
}
