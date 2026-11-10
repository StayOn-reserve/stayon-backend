package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.PaymentAttempt;
import com.stayon.stayon_backend.security.CustomerPrincipal;
import com.stayon.stayon_backend.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MySqlPaymentFlowTests.Lifecycle.class)
class MySqlPaymentFlowTests extends PaymentFlowTests {
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
    @Test void pgCallDoesNotHoldReservationOrderOrAttemptRowLocks() throws Exception {
        var a = ready(); pg.block();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var approval = executor.submit(() -> payments.approve(authentication, a.id()));
            try {
                assertTrue(pg.entered.await(5, TimeUnit.SECONDS));
                try (var connection = MYSQL.connection()) {
                    connection.setAutoCommit(false);
                    lock(connection, "reservation", reservationId); lock(connection, "service_order", orderId); lock(connection, "payment_attempt", a.id());
                    connection.rollback();
                }
            } finally { pg.release.countDown(); }
            assertEquals(PaymentAttempt.Status.APPROVED, approval.get(10, TimeUnit.SECONDS).status()); assertEquals(PAID, state());
        }
    }
    @Test void actualAttemptLockTimeoutIs503WithNoApprovalAndCanRetry() throws Exception {
        var a = ready();
        try (var connection = MYSQL.connection()) {
            connection.setAutoCommit(false); lock(connection, "payment_attempt", a.id());
            expect(503, () -> payments.approve(authentication, a.id())); assertEquals(0, pg.approvals.get());
            assertEquals(PENDING_PAYMENT, state()); connection.rollback();
        }
        assertEquals(PaymentAttempt.Status.APPROVED, payments.approve(authentication, a.id()).status());
    }
    @Test void realDeadlockAfterPgApprovalKeepsReferenceForLookupRecovery() throws Exception {
        var a = ready(); pg.block();
        try (var setup = MYSQL.connection(); var statement = setup.createStatement()) {
            statement.execute("create table if not exists mysql_test_payment_weight(id int primary key, weight_value int not null) engine=InnoDB");
            for (int i = 0; i < 50; i++) statement.executeUpdate("insert into mysql_test_payment_weight values(" + i + ",0) on duplicate key update weight_value=0");
        }
        long before = deadlocks();
        try (var executor = Executors.newSingleThreadExecutor(); var writer = MYSQL.connection()) {
            var approval = executor.submit(() -> payments.approve(authentication, a.id()));
            assertTrue(pg.entered.await(5, TimeUnit.SECONDS));
            writer.setAutoCommit(false); lock(writer, "payment_attempt", a.id());
            try (var statement = writer.createStatement()) { statement.executeUpdate("update mysql_test_payment_weight set weight_value=weight_value+1"); }
            PaymentAttemptRepository.Route route = attempts.findRoute(a.id()).orElseThrow();
            var applying = new CountDownLatch(1); var connectionId = new AtomicLong();
            doAnswer(call -> {
                connectionId.set(((Number) em.createNativeQuery("select connection_id()").getSingleResult()).longValue());
                applying.countDown(); return Optional.of(route);
            }).when(attempts).findRoute(a.id());
            pg.release.countDown(); assertTrue(applying.await(5, TimeUnit.SECONDS)); awaitWait(connectionId.get(), "payment_attempt");
            lock(writer, "service_order", orderId); writer.rollback();
            var error = assertThrows(ExecutionException.class, () -> approval.get(10, TimeUnit.SECONDS));
            assertEquals(503, ((ResponseStatusException) error.getCause()).getStatusCode().value());
            assertEquals(before + 1, deadlocks()); reset(attempts);
            assertEquals(PaymentAttempt.Status.APPROVING, view(a.id()).status()); assertEquals(PAYMENT_PROCESSING, state());
            assertEquals(1, pg.approvals.get());
            assertEquals(PaymentAttempt.Status.APPROVED, new PaymentOrchestrator(stages, registry,pgSecrets).reconcileApproval(a.id()).status());
            assertEquals(1, pg.approvals.get()); assertEquals(PAID, state());
        } finally { pg.release.countDown(); }
    }
    @Test void mysqlEnforcesSlotChecksBeforeAnotherPgCall() throws Exception {
        var a = ready();
        try (var connection = MYSQL.connection(); var statement = connection.prepareStatement("update payment_attempt set active_order_id=? where id=?")) {
            statement.setLong(1, orderId + 999); statement.setString(2, a.id());
            assertEquals(3819, assertThrows(SQLException.class, statement::executeUpdate).getErrorCode());
        }
        try (var connection = MYSQL.connection(); var statement = connection.createStatement();
             var result = statement.executeQuery("select ENFORCED from information_schema.TABLE_CONSTRAINTS where CONSTRAINT_SCHEMA=database() and TABLE_NAME='payment_attempt' and CONSTRAINT_NAME='ck_attempt_slots'")) {
            assertTrue(result.next()); assertEquals("YES", result.getString(1));
        }
        assertEquals(PaymentAttempt.Status.READY, view(a.id()).status()); assertEquals(0, pg.approvals.get());
    }
    private void lock(Connection c, String table, Object id) throws Exception {
        try (var statement = c.prepareStatement("select id from " + table + " where id=? for update")) {
            statement.setObject(1, id); try (var result = statement.executeQuery()) { assertTrue(result.next()); }
        }
    }
    private void awaitWait(long id, String table) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        try (var connection = MYSQL.connection(); var statement = connection.prepareStatement("""
                select count(*) from performance_schema.data_lock_waits w
                join performance_schema.threads t on t.THREAD_ID=w.REQUESTING_THREAD_ID
                join performance_schema.data_locks l on l.ENGINE=w.ENGINE and l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                where t.PROCESSLIST_ID=? and l.OBJECT_SCHEMA=? and l.OBJECT_NAME=?
                """)) {
            statement.setLong(1, id); statement.setString(2, OwnedMySql.SCHEMA); statement.setString(3, table);
            while (System.nanoTime() < deadline) {
                try (var result = statement.executeQuery()) { result.next(); if (result.getInt(1) > 0) return; } Thread.sleep(20);
            }
        }
        fail("Expected actual InnoDB lock wait was not observed");
    }
    private long deadlocks() throws Exception {
        try (var c = MYSQL.connection(); var s = c.createStatement(); var r = s.executeQuery("select COUNT from information_schema.INNODB_METRICS where NAME='lock_deadlocks'")) {
            assertTrue(r.next()); return r.getLong(1);
        }
    }
}
