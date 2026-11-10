package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.Reservation;
import com.stayon.stayon_backend.entity.ServiceOrder;
import com.stayon.stayon_backend.dto.customer.CustomerOrderCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.SQLException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Re-runs all 55 existing creation scenarios against a new, test-owned MySQL 8 instance. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MySqlCustomerOrderIntegrationTests.Lifecycle.class)
class MySqlCustomerOrderIntegrationTests extends CustomerOrderCreationTests {
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

    @Test void mysqlEnvironmentUsesOwnedServerAndInnoDb() throws Exception {
        try (var connection = MYSQL.connection(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("select version(), @@default_storage_engine, @@transaction_isolation, @@innodb_lock_wait_timeout")) {
                assertTrue(result.next()); assertTrue(result.getString(1).startsWith("8."));
                assertEquals("InnoDB", result.getString(2));
                assertEquals("REPEATABLE-READ", result.getString(3));
                assertEquals(2, result.getInt(4));
                System.out.println("Owned MySQL: version=" + result.getString(1) + ", engine=" + result.getString(2)
                        + ", server isolation=" + result.getString(3) + ", lock wait timeout=" + result.getInt(4));
            }
            try (var result = statement.executeQuery("select table_name, engine from information_schema.tables where table_schema = database() and table_name in ('reservation','site','additional_service','service_order','service_order_item')")) {
                int tables = 0;
                while (result.next()) { assertEquals("InnoDB", result.getString(2)); tables++; }
                assertEquals(5, tables);
            }
            try (var result = statement.executeQuery("show index from service_order where Key_name = 'uk_order_reservation_idempotency'")) {
                var columns = new ArrayList<String>();
                while (result.next()) { assertEquals(0, result.getInt("Non_unique"));
                    assertNull(result.getObject("Sub_part")); columns.add(result.getString("Column_name")); }
                assertEquals(List.of("reservation_id", "idempotency_key_hash"), columns);
            }
        }
    }

    @Test void requiresNewCommitsIndependentlyAndUsesReadCommittedOnAnotherConnection() {
        var innerId = new AtomicLong(); var isolation = new AtomicReference<String>();
        doAnswer(call -> {
            innerId.set(((Number) em.createNativeQuery("select connection_id()").getSingleResult()).longValue());
            isolation.set((String) em.createNativeQuery("select @@transaction_isolation").getSingleResult());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return Optional.empty();
        }).when(orders).findByReservation_IdAndIdempotencyKeyHash(eq(reservationId), anyString());
        new TransactionTemplate(manager).execute(status -> {
            long outerId = ((Number) em.createNativeQuery("select connection_id()").getSingleResult()).longValue();
            assertEquals("REPEATABLE-READ", em.createNativeQuery("select @@transaction_isolation").getSingleResult());
            assertTrue(service.create(authentication, "requires-new", request(1)).created());
            assertNotEquals(outerId, innerId.get());
            assertEquals(outerId, ((Number) em.createNativeQuery("select connection_id()").getSingleResult()).longValue());
            assertEquals("REPEATABLE-READ", em.createNativeQuery("select @@transaction_isolation").getSingleResult());
            status.setRollbackOnly(); return null;
        });
        assertEquals("READ-COMMITTED", isolation.get()); assertEquals(1, count());
    }

    @Test void observesReservationLockFootprintAtReadCommitted() throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { locked.countDown(); await(release); return Optional.empty(); }).when(orders)
                .findByReservation_IdAndIdempotencyKeyHash(eq(reservationId), anyString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var creation = executor.submit(() -> service.create(authentication, "parent-locks", request(1)));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var locks = recordLocks();
                assertTrue(locks.stream().anyMatch(row -> row.table().equals("reservation") && row.index().equals("PRIMARY") && row.mode().startsWith("X")));
                assertTrue(locks.stream().noneMatch(row -> row.mode().contains("GAP") && !row.mode().contains("REC_NOT_GAP")));
                System.out.println("Reservation lock footprint: " + locks);
                try (var connection = MYSQL.connection(); var statement = connection.prepareStatement("update reservation set nights = nights + 1 where id = ?")) {
                    statement.setLong(1, otherReservationId); assertEquals(1, statement.executeUpdate());
                }
            } finally { release.countDown(); }
            assertTrue(creation.get(10, TimeUnit.SECONDS).created());
        }
    }

    @Test void productPrimaryKeyLockDoesNotBlockAnotherProductsPriceUpdate() throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { locked.countDown(); await(release);
            ServiceOrder order = call.getArgument(0); em.persist(order); em.flush(); return order;
        }).when(orders).saveAndFlush(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var creation = executor.submit(() -> service.create(authentication, "product-locks", request(1)));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var locks = recordLocks();
                assertTrue(locks.stream().anyMatch(row -> row.table().equals("additional_service") && row.index().equals("PRIMARY") && row.mode().startsWith("X")));
                System.out.println("Product lock footprint: " + locks);
                try (var connection = MYSQL.connection(); var statement = connection.prepareStatement("update additional_service set price = price + 1 where id = ?")) {
                    statement.setLong(1, secondId); assertEquals(1, statement.executeUpdate());
                }
            } finally { release.countDown(); }
            assertEquals(1000, creation.get(10, TimeUnit.SECONDS).order().totalAmount());
        }
    }

    @Test void parallelDifferentKeysEachCommitIndependentOrders() throws Exception {
        var start = new CountDownLatch(1); var waiting = new CountDownLatch(4);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<Long>>();
            for (int i = 0; i < 4; i++) {
                String key = "independent-" + i;
                futures.add(executor.submit(() -> { waiting.countDown(); await(start);
                    var result = service.create(authentication, key, request(1)); assertTrue(result.created()); return result.order().id(); }));
            }
            try { assertTrue(waiting.await(5, TimeUnit.SECONDS)); } finally { start.countDown(); }
            var ids = new ArrayList<Long>();
            for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(4, ids.stream().distinct().count()); assertEquals(4, count());
        }
    }

    @Test void failureImmediatelyBeforeInsertRollsBackAndDoesNotReserveKey() {
        doThrow(new DataAccessResourceFailureException("injected pre-insert failure")).when(orders).saveAndFlush(any());
        expectStatus(503, () -> service.create(authentication, "before-insert", request(1))); assertEquals(0, count());
        reset(orders);
        assertTrue(service.create(authentication, "before-insert", request(1)).created()); assertEquals(1, count());
    }

    @Test void lostPostCommitResponseRecoversOriginalOrderWithoutAnotherInsert() {
        assertThrows(IOException.class, () -> {
            service.create(authentication, "lost-response", request(2));
            throw new IOException("injected response delivery failure after commit");
        });
        assertEquals(1, count());
        var replay = service.create(authentication, "lost-response", request(2));
        assertFalse(replay.created()); assertEquals(2000, replay.order().totalAmount()); assertEquals(1, count());
    }

    @Test void uniqueIndexWaitsForConcurrentInsertThenRejectsDuplicateAndServiceRecovers() throws Exception {
        String key = "unique-race";
        String keyHash = digest(key); String requestHash = digest("customer-order-v1\n" + productId + ":1");
        var inserted = new CountDownLatch(1); var release = new CountDownLatch(1); var contenderId = new AtomicLong();
        var contenderStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var winner = executor.submit(() -> tx(() -> {
                var order = indexRaceOrder(keyHash, requestHash); orders.saveAndFlush(order);
                inserted.countDown(); await(release); return order.getId();
            }));
            assertTrue(inserted.await(5, TimeUnit.SECONDS));
            var contender = executor.submit(() -> tx(() -> {
                contenderId.set(((Number) em.createNativeQuery("select connection_id()").getSingleResult()).longValue());
                contenderStarted.countDown(); return orders.saveAndFlush(indexRaceOrder(keyHash, requestHash)).getId();
            }));
            try {
                assertTrue(contenderStarted.await(5, TimeUnit.SECONDS));
                var indexes = awaitWait(contenderId.get(), "service_order");
                assertTrue(indexes.contains("uk_order_reservation_idempotency"), "Observed wait indexes: " + indexes);
            } finally { release.countDown(); }
            long id = winner.get(10, TimeUnit.SECONDS);
            var error = assertThrows(ExecutionException.class, () -> contender.get(10, TimeUnit.SECONDS));
            assertTrue(hasSqlCode(error, 1062), error::toString); assertEquals(1, count());
            var recovered = service.create(authentication, key, request(1));
            assertFalse(recovered.created()); assertEquals(id, recovered.order().id()); assertEquals(1, count());
        } finally { release.countDown(); }
    }

    @Test void uniqueViolationCanBeRolledBackAndSamePhysicalConnectionReused() throws Exception {
        String requestHash = digest("customer-order-v1\n" + productId + ":1");
        service.create(authentication, "unique-connection", request(1));
        try (var connection = MYSQL.connection()) {
            connection.setAutoCommit(false);
            var error = assertThrows(SQLException.class, () -> insertMetadataOrder(connection, digest("unique-connection"), requestHash));
            assertEquals(1062, error.getErrorCode()); connection.rollback();
            insertMetadataOrder(connection, digest("clean-connection-key"), requestHash); connection.commit();
            try (var statement = connection.createStatement(); var result = statement.executeQuery("select 1")) { assertTrue(result.next()); }
        }
        assertEquals(2, count());
    }

    @Test void actualInnoDbLockTimeoutReturns503AndSameKeyCanBeRetried() throws Exception {
        try (var blocker = MYSQL.connection()) {
            blocker.setAutoCommit(false); lockProduct(blocker, productId);
            long start = System.nanoTime();
            expectStatus(503, () -> service.create(authentication, "real-timeout", request(1)));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed >= 1500 && elapsed < 9000, "Lock timeout duration: " + elapsed);
            try (var contender = MYSQL.connection()) {
                contender.setAutoCommit(false);
                var error = assertThrows(SQLException.class, () -> lockProduct(contender, productId));
                assertEquals(1205, error.getErrorCode()); contender.rollback();
            }
            assertEquals(0, count()); blocker.rollback();
        }
        assertTrue(service.create(authentication, "real-timeout", request(1)).created()); assertEquals(1, count());
    }

    @Test void actualDeadlockVictimReturns503RollsBackAndCanRetrySameKey() throws Exception {
        // Give the opposing writer more modified rows, making the new-order transaction the smaller victim.
        try (var setup = MYSQL.connection(); var statement = setup.createStatement()) {
            statement.execute("create table if not exists mysql_test_deadlock_weight (id int primary key, value int not null) engine=InnoDB");
            for (int i = 0; i < 50; i++) statement.executeUpdate("insert into mysql_test_deadlock_weight values (" + i + ",0) on duplicate key update value=0");
        }
        var serviceConnection = new AtomicLong(); var reachedLookup = new CountDownLatch(1);
        doAnswer(call -> {
            serviceConnection.set(((Number) em.createNativeQuery("select connection_id()").getSingleResult()).longValue());
            reachedLookup.countDown(); return Optional.empty();
        }).when(orders).findByReservation_IdAndIdempotencyKeyHash(eq(reservationId), anyString());
        long before = deadlocks();
        try (var writer = MYSQL.connection(); var executor = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false); lockProduct(writer, secondId);
            try (var statement = writer.createStatement()) { statement.executeUpdate("update mysql_test_deadlock_weight set value=value+1"); }
            var request = new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item(secondId, 1),
                    new CustomerOrderCreateRequest.Item(productId, 1)));
            var creation = executor.submit(() -> service.create(authentication, "real-deadlock", request));
            assertTrue(reachedLookup.await(5, TimeUnit.SECONDS));
            awaitWait(serviceConnection.get(), "additional_service");
            // Service holds productId and waits on secondId. Writer holds secondId and now requests productId.
            lockProduct(writer, productId); writer.rollback();
            var error = assertThrows(ExecutionException.class, () -> creation.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ResponseStatusException.class, error.getCause());
            assertEquals(503, ((ResponseStatusException) error.getCause()).getStatusCode().value());
            assertEquals(before + 1, deadlocks()); assertEquals(0, count());
        }
        reset(orders);
        assertTrue(service.create(authentication, "real-deadlock", request(1)).created()); assertEquals(1, count());
    }

    private ServiceOrder indexRaceOrder(String keyHash, String requestHash) {
        var order = new ServiceOrder(em.find(Reservation.class, reservationId));
        order.addItem(em.find(com.stayon.stayon_backend.entity.AdditionalService.class, productId), "First snapshot", 1000, 1, 1000);
        order.identifyCreation(keyHash, requestHash); return order;
    }
    private void insertMetadataOrder(Connection connection, String keyHash, String requestHash) throws SQLException {
        try (var statement = connection.prepareStatement("insert into service_order(reservation_id,status,total_amount,idempotency_key_hash,request_hash) values (?,'PENDING_PAYMENT',0,?,?)")) {
            statement.setLong(1, reservationId); statement.setString(2, keyHash); statement.setString(3, requestHash); statement.executeUpdate();
        }
    }
    private void lockProduct(Connection connection, Long id) throws SQLException {
        try (var statement = connection.prepareStatement("select id from additional_service where id=? for update")) {
            statement.setLong(1, id); try (var result = statement.executeQuery()) { assertTrue(result.next()); }
        }
    }
    private record LockRow(String table, String index, String mode) { }
    private List<LockRow> recordLocks() throws Exception {
        try (var connection = MYSQL.connection(); var statement = connection.prepareStatement(
                "select OBJECT_NAME, INDEX_NAME, LOCK_MODE from performance_schema.data_locks where OBJECT_SCHEMA=? and LOCK_TYPE='RECORD'")) {
            statement.setString(1, OwnedMySql.SCHEMA); var rows = new ArrayList<LockRow>();
            try (var result = statement.executeQuery()) { while (result.next()) rows.add(new LockRow(result.getString(1), result.getString(2), result.getString(3))); }
            return rows;
        }
    }
    private List<String> awaitWait(long connectionId, String table) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        try (var connection = MYSQL.connection(); var statement = connection.prepareStatement("""
                select distinct l.INDEX_NAME from performance_schema.data_lock_waits w
                join performance_schema.threads t on t.THREAD_ID=w.REQUESTING_THREAD_ID
                join performance_schema.data_locks l on l.ENGINE=w.ENGINE and l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                where t.PROCESSLIST_ID=? and l.OBJECT_SCHEMA=? and l.OBJECT_NAME=?
                """)) {
            statement.setLong(1, connectionId); statement.setString(2, OwnedMySql.SCHEMA); statement.setString(3, table);
            while (System.nanoTime() < deadline) {
                var indexes = new ArrayList<String>();
                try (var result = statement.executeQuery()) { while (result.next()) indexes.add(result.getString(1)); }
                if (!indexes.isEmpty()) return indexes;
                Thread.sleep(20);
            }
        }
        throw new AssertionError("Expected actual InnoDB lock wait was not observed for " + table);
    }
    private long deadlocks() throws Exception {
        try (var connection = MYSQL.connection(); var statement = connection.prepareStatement(
                "select COUNT from information_schema.INNODB_METRICS where NAME='lock_deadlocks'")) {
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }
    private boolean hasSqlCode(Throwable error, int code) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == code) return true;
        }
        return false;
    }
    private String digest(String input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
