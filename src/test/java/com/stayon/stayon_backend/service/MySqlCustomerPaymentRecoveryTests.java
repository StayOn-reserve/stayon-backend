package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.PaymentAttempt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh datadir and random loopback port; no existing MySQL endpoint is used. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MySqlCustomerPaymentRecoveryTests.Lifecycle.class)
class MySqlCustomerPaymentRecoveryTests extends CustomerPaymentRecoveryTests {
    private static final OwnedMySql MYSQL = OwnedMySql.start();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", MYSQL::jdbcUrl); p.add("spring.datasource.username", () -> "root");
        p.add("spring.datasource.password", () -> ""); p.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        p.add("spring.datasource.hikari.maximum-pool-size", () -> "16"); p.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
    @TestConfiguration(proxyBeanMethods = false) static class Lifecycle {
        @Bean(destroyMethod = "close") OwnedMySql ownedMySql() { return MYSQL; }
        @Bean static BeanFactoryPostProcessor mysqlDependency() { return f -> f.getBeanDefinition("dataSource").setDependsOn("ownedMySql"); }
    }
    @Test void mysqlRecoveryLeaseTimeoutRollsBackWithoutCallingPg() throws Exception {
        var a = ready(); stages.claimApproval(authentication, a.id());
        try (var connection = MYSQL.connection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from payment_attempt where id=? for update")) {
                statement.setString(1, a.id()); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            expect(503, () -> stages.claimRecovery(a.id(), recoverySettings)); assertEquals(0, pg.lookups.get()); connection.rollback();
        }
        assertNotNull(stages.claimRecovery(a.id(), recoverySettings));
    }
    @Test void mysqlRecoveryLookupHoldsNoParentOrderOrAttemptRowLock() throws Exception {
        var a = ready(); pg.mode = FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT; payments.approve(authentication, a.id());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        pg.beforeLookup = command -> { entered.countDown(); await(release); };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var lookup = executor.submit(() -> recovery.drain(20));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                try (var connection = MYSQL.connection()) {
                    connection.setAutoCommit(false);
                    for (var tableAndKey : java.util.List.of(new Object[]{"reservation", reservationId}, new Object[]{"service_order", orderId}, new Object[]{"payment_attempt", a.id()})) {
                        try (var statement = connection.prepareStatement("select id from " + tableAndKey[0] + " where id=? for update")) {
                            statement.setObject(1, tableAndKey[1]); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
                        }
                    }
                    connection.rollback();
                }
            } finally { release.countDown(); }
            assertEquals(1, lookup.get(10, TimeUnit.SECONDS)); assertEquals(PaymentAttempt.Status.APPROVED, view(a.id()).status());
        }
    }
}
