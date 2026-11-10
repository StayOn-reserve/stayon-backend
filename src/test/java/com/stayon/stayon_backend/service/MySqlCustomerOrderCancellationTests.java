package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.security.CustomerPrincipal;
import com.stayon.stayon_backend.repository.CustomerAuthStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MySqlCustomerOrderCancellationTests.Lifecycle.class)
class MySqlCustomerOrderCancellationTests extends CustomerOrderCancellationTests {
    private static final OwnedMySql MYSQL = OwnedMySql.start();

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::jdbcUrl);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        properties.add("spring.datasource.username", () -> "root");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "16");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Lifecycle {
        @Bean(destroyMethod = "close") OwnedMySql ownedMySql() { return MYSQL; }
        @Bean static BeanFactoryPostProcessor mysqlDependency() {
            return factory -> factory.getBeanDefinition("dataSource").setDependsOn("ownedMySql");
        }
    }

    @Override @Test void eightParallelCancellationsHaveOneTransitionAndIdenticalResults() throws Exception {
        long before = updates();
        super.eightParallelCancellationsHaveOneTransitionAndIdenticalResults();
        long actual = updates() - before;
        assertEquals(1, actual, "Actual InnoDB service_order row updates");
        System.out.println("Eight concurrent cancellations: actual service_order UPDATE count=" + actual);
    }

    @Test void cancellationLocksReservationAndOrderWithoutLockingCatalogOrSnapshots() throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { locked.countDown(); await(release); em.flush(); return null; }).when(orders).flush();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var cancel = executor.submit(() -> service.cancel(authentication, orderId));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var locks = locks();
                assertTrue(locks.stream().anyMatch(row -> row.table().equals("reservation") && row.index().equals("PRIMARY") && row.mode().startsWith("X")));
                assertTrue(locks.stream().anyMatch(row -> row.table().equals("service_order") && row.index().equals("PRIMARY") && row.mode().startsWith("X")));
                assertTrue(locks.stream().noneMatch(row -> row.table().equals("additional_service") || row.table().equals("service_order_item")));
                assertTrue(locks.stream().noneMatch(row -> row.mode().contains("GAP") && !row.mode().contains("REC_NOT_GAP")));
                System.out.println("Cancellation lock footprint: " + locks);
                assertEquals(PENDING_PAYMENT, state());
            } finally { release.countDown(); }
            assertTrue(cancel.get(10, TimeUnit.SECONDS).changed()); assertEquals(CANCELLED, state());
        }
    }

    @Test void creationWaitsForCancelsReservationLockAndCommitsItsOwnPendingOrder() throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { locked.countDown(); await(release); em.flush(); return null; }).when(orders).flush();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancel = executor.submit(() -> service.cancel(authentication, orderId));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var started = new CountDownLatch(1); var connectionId = captureConnection(started);
            var creation = executor.submit(() -> service.create(authentication, "wait-for-cancel", request()));
            try { assertTrue(started.await(5, TimeUnit.SECONDS)); awaitWait(connectionId.get(), "reservation"); }
            finally { release.countDown(); }
            assertTrue(cancel.get(10, TimeUnit.SECONDS).changed());
            var result = creation.get(10, TimeUnit.SECONDS); assertTrue(result.created());
            assertEquals(PENDING_PAYMENT, result.order().status()); assertEquals(CANCELLED, state()); assertEquals(2, count());
        } finally { release.countDown(); }
    }

    @Test void paymentConditionWinsFirstAndCancellationSeesPaidAfterWaiting() throws Exception {
        var started = new CountDownLatch(1); var connectionId = captureConnection(started);
        try (var payment = MYSQL.connection(); var executor = Executors.newSingleThreadExecutor()) {
            payment.setAutoCommit(false); assertEquals(1, approveIfPending(payment));
            var cancel = executor.submit(() -> service.cancel(authentication, orderId));
            assertTrue(started.await(5, TimeUnit.SECONDS)); awaitWait(connectionId.get(), "service_order");
            payment.commit();
            var error = assertThrows(ExecutionException.class, () -> cancel.get(10, TimeUnit.SECONDS));
            assertEquals(409, ((ResponseStatusException) error.getCause()).getStatusCode().value());
            assertEquals(PAID, state());
        }
    }

    @Test void cancellationWinsFirstAndLaterConditionalPaymentCannotOverwriteIt() throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { locked.countDown(); await(release); em.flush(); return null; }).when(orders).flush();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancel = executor.submit(() -> service.cancel(authentication, orderId));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var paymentId = new AtomicLong(); var started = new CountDownLatch(1);
            var payment = executor.submit(() -> {
                try (var connection = MYSQL.connection(); var statement = connection.createStatement()) {
                    connection.setAutoCommit(false);
                    try (var result = statement.executeQuery("select connection_id()")) { result.next(); paymentId.set(result.getLong(1)); }
                    started.countDown(); int changed = approveIfPending(connection); connection.commit(); return changed;
                }
            });
            try { assertTrue(started.await(5, TimeUnit.SECONDS)); awaitWait(paymentId.get(), "service_order"); }
            finally { release.countDown(); }
            assertTrue(cancel.get(10, TimeUnit.SECONDS).changed()); assertEquals(0, payment.get(10, TimeUnit.SECONDS));
            assertEquals(CANCELLED, state());
        } finally { release.countDown(); }
    }

    @Test void realMySqlOrderLockTimeoutRollsBackAndCancelRetrySucceeds() throws Exception {
        try (var blocker = MYSQL.connection()) {
            blocker.setAutoCommit(false); lock(blocker, "service_order", orderId);
            long start = System.nanoTime();
            expectStatus(503, () -> service.cancel(authentication, orderId));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed >= 1500 && elapsed < 9000, "Actual lock timeout duration: " + elapsed);
            assertEquals(PENDING_PAYMENT, state()); blocker.rollback();
        }
        assertTrue(service.cancel(authentication, orderId).changed()); assertEquals(CANCELLED, state());
    }

    @Test void reverseOrderWriterCausesActualDeadlockAndCancellationFailsClosed() throws Exception {
        try (var connection = MYSQL.connection(); var statement = connection.createStatement()) {
            statement.execute("create table if not exists mysql_test_cancel_weight(id int primary key, weight_value int not null) engine=InnoDB");
            for (int i = 0; i < 50; i++) statement.executeUpdate("insert into mysql_test_cancel_weight values(" + i + ",0) on duplicate key update weight_value=0");
        }
        long before = deadlocks();
        var started = new CountDownLatch(1); var connectionId = captureConnection(started);
        try (var writer = MYSQL.connection(); var executor = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false); lock(writer, "service_order", orderId);
            try (var statement = writer.createStatement()) { statement.executeUpdate("update mysql_test_cancel_weight set weight_value=weight_value+1"); }
            var cancel = executor.submit(() -> service.cancel(authentication, orderId));
            assertTrue(started.await(5, TimeUnit.SECONDS)); awaitWait(connectionId.get(), "service_order");
            lock(writer, "reservation", reservationId); writer.rollback();
            var error = assertThrows(ExecutionException.class, () -> cancel.get(10, TimeUnit.SECONDS));
            assertEquals(503, ((ResponseStatusException) error.getCause()).getStatusCode().value());
            assertEquals(before + 1, deadlocks()); assertEquals(PENDING_PAYMENT, state());
        }
        assertTrue(service.cancel(authentication, orderId).changed()); assertEquals(CANCELLED, state());
    }

    @Test void cancellationUsesRequiresNewReadCommittedAndSurvivesOuterRollback() {
        var inner = new AtomicLong(); var isolation = new AtomicReference<String>();
        doAnswer(call -> {
            inner.set(((Number) em.createNativeQuery("select connection_id()").getSingleResult()).longValue());
            isolation.set((String) em.createNativeQuery("select @@transaction_isolation").getSingleResult());
            em.flush(); return null;
        }).when(orders).flush();
        new TransactionTemplate(manager).execute(status -> {
            long outer = ((Number) em.createNativeQuery("select connection_id()").getSingleResult()).longValue();
            assertTrue(service.cancel(authentication, orderId).changed()); assertNotEquals(outer, inner.get());
            assertEquals(outer, ((Number) em.createNativeQuery("select connection_id()").getSingleResult()).longValue());
            assertEquals("REPEATABLE-READ", em.createNativeQuery("select @@transaction_isolation").getSingleResult());
            status.setRollbackOnly(); return null;
        });
        assertEquals("READ-COMMITTED", isolation.get()); assertEquals(CANCELLED, state());
    }

    private AtomicLong captureConnection(CountDownLatch started) {
        String id = ((CustomerPrincipal) authentication.getPrincipal()).sessionId();
        CustomerAuthStore.Session session = store.findSession(id).orElseThrow();
        var connection = new AtomicLong();
        doAnswer(call -> {
            connection.set(((Number) em.createNativeQuery("select connection_id()").getSingleResult()).longValue());
            started.countDown(); return Optional.of(session);
        }).when(store).findSession(id);
        return connection;
    }
    private int approveIfPending(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("update service_order set status='PAID' where id=? and status='PENDING_PAYMENT'")) {
            statement.setLong(1, orderId); return statement.executeUpdate();
        }
    }
    private void lock(Connection connection, String table, Long id) throws Exception {
        // Only fixed test-owned table names are passed by this class.
        try (var statement = connection.prepareStatement("select id from " + table + " where id=? for update")) {
            statement.setLong(1, id); try (var result = statement.executeQuery()) { assertTrue(result.next()); }
        }
    }
    private record LockRow(String table, String index, String mode) { }
    private List<LockRow> locks() throws Exception {
        try (var connection = MYSQL.connection(); var statement = connection.prepareStatement(
                "select OBJECT_NAME, INDEX_NAME, LOCK_MODE from performance_schema.data_locks where OBJECT_SCHEMA=? and LOCK_TYPE='RECORD'")) {
            statement.setString(1, OwnedMySql.SCHEMA); var rows = new ArrayList<LockRow>();
            try (var result = statement.executeQuery()) { while (result.next()) rows.add(new LockRow(result.getString(1), result.getString(2), result.getString(3))); }
            return rows;
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
                try (var result = statement.executeQuery()) { result.next(); if (result.getInt(1) > 0) return; }
                Thread.sleep(20);
            }
        }
        fail("Expected InnoDB lock wait was not observed: " + table);
    }
    private long updates() throws Exception {
        try (var connection = MYSQL.connection(); var statement = connection.prepareStatement(
                "select COUNT_UPDATE from performance_schema.table_io_waits_summary_by_table where OBJECT_SCHEMA=? and OBJECT_NAME='service_order'")) {
            statement.setString(1, OwnedMySql.SCHEMA);
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }
    private long deadlocks() throws Exception {
        try (var connection = MYSQL.connection(); var statement = connection.createStatement();
             var result = statement.executeQuery("select COUNT from information_schema.INNODB_METRICS where NAME='lock_deadlocks'")) {
            assertTrue(result.next()); return result.getLong(1);
        }
    }
}
