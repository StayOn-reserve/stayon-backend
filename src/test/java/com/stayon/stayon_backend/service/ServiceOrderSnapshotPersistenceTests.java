package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.repository.ServiceOrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.stayon.stayon_backend.entity.AdditionalService.ServiceInputType.QUANTITY;
import static com.stayon.stayon_backend.service.ServiceOrderPreparation.Selection;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@CustomerJwtTestProperties
@ActiveProfiles("test")
@Transactional
class ServiceOrderSnapshotPersistenceTests {
    @Autowired private EntityManager em;
    @Autowired private ServiceOrderRepository orders;

    private Reservation reservation;
    private AdditionalService product;

    @BeforeEach
    void setUp() {
        var site = new Site("스냅샷 테스트 Site");
        em.persist(site);
        reservation = new Reservation();
        reservation.setPlatform(Platform.CAMFIT);
        reservation.setEventType(ReservationEventType.RESERVED);
        reservation.setReservationId(UUID.randomUUID().toString());
        reservation.setCheckInDate(LocalDate.of(2026, 12, 1));
        reservation.setCheckOutDate(LocalDate.of(2026, 12, 2));
        reservation.setNights(1);
        reservation.setSite(site);
        em.persist(reservation);
        product = new AdditionalService(site, "주문 당시 상품명", 20000, QUANTITY, true, null, null, null);
        em.persist(product);
    }

    @Test
    void savesAndReloadsOrderAndAllItemSnapshotsThroughRepository() {
        var second = new AdditionalService(reservation.getSite(), "두 번째 상품", 3000, QUANTITY,
                true, null, null, null);
        em.persist(second);
        var order = ServiceOrderPreparation.prepareEntity(reservation,
                List.of(new Selection(product, 2), new Selection(second, 3)));
        assertNull(order.getId());
        assertEquals(0L, orders.count());
        orders.saveAndFlush(order);
        Long id = order.getId();
        em.clear();

        var saved = orders.findById(id).orElseThrow();
        assertEquals(reservation.getId(), saved.getReservation().getId());
        assertEquals(ServiceOrder.ServiceOrderStatus.PENDING_PAYMENT, saved.getStatus());
        assertEquals(49000, saved.getTotalAmount());
        assertEquals(2, saved.getItems().size());
        var first = saved.getItems().stream().filter(item -> item.getAdditionalService().getId().equals(product.getId()))
                .findFirst().orElseThrow();
        assertNotNull(first.getId());
        assertEquals(id, first.getServiceOrder().getId());
        assertSnapshot(first);
        var other = saved.getItems().stream().filter(item -> item != first).findFirst().orElseThrow();
        assertEquals("두 번째 상품", other.getServiceName());
        assertEquals(3000, other.getUnitPrice());
        assertEquals(3, other.getQuantity());
        assertEquals(9000, other.getTotalPrice());
        assertEquals(ServiceOrderItem.FulfillmentStatus.NOT_APPLICABLE, first.getFulfillmentStatus());
    }

    @Test
    void productChangesBeforeAndAfterSaveNeverReplaceSnapshot() {
        var order = prepared();
        product.update("저장 전 변경", 50000, QUANTITY, true, null, null, null);
        orders.saveAndFlush(order);
        product.update("저장 후 변경", 70000, QUANTITY, false, null, null, null);
        em.flush();
        em.clear();

        var saved = orders.findById(order.getId()).orElseThrow();
        assertSnapshot(saved.getItems().getFirst());
        assertEquals(40000, saved.getTotalAmount());
        var current = saved.getItems().getFirst().getAdditionalService();
        assertEquals("저장 후 변경", current.getName());
        assertEquals(70000, current.getPrice());
        assertFalse(current.isActive());
    }

    @Test
    void legacyRowsWithoutProductNameRemainReadableWithoutCurrentNameFallback() {
        var order = orders.saveAndFlush(prepared());
        // Simulate a historical row after the nullable column has been added.
        em.createNativeQuery("UPDATE service_order_item SET service_name = NULL WHERE service_order_id = :id")
                .setParameter("id", order.getId()).executeUpdate();
        em.clear();
        var saved = orders.findById(order.getId()).orElseThrow();
        var item = saved.getItems().getFirst();
        assertNull(item.getServiceName());
        assertEquals(20000, item.getUnitPrice());
        assertEquals(2, item.getQuantity());
        assertEquals(40000, item.getTotalPrice());
        assertEquals(40000, saved.getTotalAmount());
        assertNotNull(item.getAdditionalService().getName());
    }

    @Test
    void jpaUpdatesDoNotOverwriteSnapshotColumns() {
        var order = orders.saveAndFlush(prepared());
        var item = order.getItems().getFirst();
        ReflectionTestUtils.setField(item, "serviceName", "덮어쓰기 시도");
        ReflectionTestUtils.setField(item, "unitPrice", 1);
        ReflectionTestUtils.setField(item, "quantity", 1);
        ReflectionTestUtils.setField(item, "totalPrice", 1);
        ReflectionTestUtils.setField(item, "fulfillmentStatus", ServiceOrderItem.FulfillmentStatus.CANCELLED);
        em.flush();
        em.clear();
        var saved = orders.findById(order.getId()).orElseThrow().getItems().getFirst();
        assertSnapshot(saved);
        assertEquals(ServiceOrderItem.FulfillmentStatus.CANCELLED, saved.getFulfillmentStatus());
    }

    @Test
    void persistedOrderCannotAppendItemsAndCollectionIsReadOnly() {
        var order = orders.saveAndFlush(prepared());
        assertThrows(IllegalStateException.class,
                () -> order.addItem(product, "추가 상품", 100, 1, 100));
        assertThrows(UnsupportedOperationException.class, () -> order.getItems().clear());
        em.clear();
        assertEquals(1, orders.findById(order.getId()).orElseThrow().getItems().size());
    }

    @Test
    void entityMappingRetainsPreparationValidationWithoutSavingInvalidOrders() {
        product.changeActive(false);
        assertThrows(IllegalStateException.class, this::prepared);
        assertEquals(0L, orders.count());
        product.changeActive(true);
        assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepareEntity(reservation,
                List.of(new Selection(product, 0))));
        assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepareEntity(reservation, null));
        assertEquals(0L, orders.count());
    }

    private ServiceOrder prepared() {
        return ServiceOrderPreparation.prepareEntity(reservation, List.of(new Selection(product, 2)));
    }

    private void assertSnapshot(ServiceOrderItem item) {
        assertEquals("주문 당시 상품명", item.getServiceName());
        assertEquals(20000, item.getUnitPrice());
        assertEquals(2, item.getQuantity());
        assertEquals(40000, item.getTotalPrice());
    }
}
