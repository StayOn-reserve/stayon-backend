package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.AdditionalService;
import com.stayon.stayon_backend.entity.Reservation;
import com.stayon.stayon_backend.entity.ReservationEventType;
import com.stayon.stayon_backend.entity.Site;
import com.stayon.stayon_backend.service.ServiceOrderPreparation.ItemSnapshot;
import com.stayon.stayon_backend.service.ServiceOrderPreparation.Selection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static com.stayon.stayon_backend.entity.AdditionalService.ServiceInputType.*;
import static com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.PENDING_PAYMENT;
import static org.junit.jupiter.api.Assertions.*;

class ServiceOrderPreparationTests {
    private final Site site = site(20L);

    @Test
    void calculatesItemSnapshotsAndTotalInSelectionOrder() {
        var first = service(1L, 20000);
        var second = service(2L, 3000);
        var result = ServiceOrderPreparation.prepare(reservation(),
                List.of(new Selection(first, 2), new Selection(second, 3)));

        assertEquals(10L, result.reservationId());
        assertEquals(PENDING_PAYMENT, result.initialStatus());
        assertEquals(List.of(new ItemSnapshot(1L, "상품 1", 20000, 2, 40000),
                new ItemSnapshot(2L, "상품 2", 3000, 3, 9000)), result.items());
        assertEquals(49000, result.totalAmount());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, Integer.MAX_VALUE})
    void acceptsZeroPriceAndMaximumRepresentableTotal(int price) {
        var result = ServiceOrderPreparation.prepare(reservation(), List.of(new Selection(service(1L, price), 1)));
        assertEquals(price, result.totalAmount());
        assertEquals(price, result.items().getFirst().totalPrice());
    }

    @Test
    void acceptsDifferentSiteEntityWithTheSamePersistentId() {
        var product = service(1L, 100);
        ReflectionTestUtils.setField(product, "site", site(20L));
        assertEquals(200, ServiceOrderPreparation.prepare(reservation(),
                List.of(new Selection(product, 2))).totalAmount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nullReservation", "missingId", "cancelled", "missingEventType", "missingSite", "missingSiteId"})
    void rejectsInvalidReservations(String scenario) {
        var reservation = reservation();
        switch (scenario) {
            case "nullReservation" -> reservation = null;
            case "missingId" -> reservation.setId(null);
            case "cancelled" -> reservation.setEventType(ReservationEventType.CANCELLED);
            case "missingEventType" -> reservation.setEventType(null);
            case "missingSite" -> reservation.setSite(null);
            case "missingSiteId" -> reservation.setSite(site(null));
            default -> throw new AssertionError(scenario);
        }
        var invalid = reservation;
        assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepare(invalid,
                List.of(new Selection(service(1L, 100), 1))));
    }

    @Test
    void rejectsNullAndEmptySelections() {
        assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepare(reservation(), null));
        assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepare(reservation(), List.of()));
    }

    @Test
    void rejectsNullSelectionAndNullService() {
        assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepare(reservation(),
                Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepare(reservation(),
                List.of(new Selection(null, 1))));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveOrMissingQuantity(Integer quantity) {
        assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepare(reservation(),
                List.of(new Selection(service(1L, 100), quantity))));
    }

    @Test
    void rejectsUnsavedProduct() {
        assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepare(reservation(),
                List.of(new Selection(service(null, 100), 1))));
    }

    @Test
    void rejectsDuplicateProductIdsEvenAcrossDifferentEntityInstances() {
        assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepare(reservation(),
                List.of(new Selection(service(1L, 100), 1), new Selection(service(1L, 200), 2))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingSite", "missingSiteId", "anotherSite"})
    void rejectsProductOutsideReservationSite(String scenario) {
        var product = service(1L, 100);
        ReflectionTestUtils.setField(product, "site", switch (scenario) {
            case "missingSite" -> null;
            case "missingSiteId" -> site(null);
            case "anotherSite" -> site(21L);
            default -> throw new AssertionError(scenario);
        });
        assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepare(reservation(),
                List.of(new Selection(product, 1))));
    }

    @Test
    void rejectsInactiveProduct() {
        var product = service(1L, 100);
        product.changeActive(false);
        var exception = assertThrows(IllegalStateException.class, () -> ServiceOrderPreparation.prepare(reservation(),
                List.of(new Selection(product, 1))));
        assertEquals("비활성화된 서비스는 주문할 수 없습니다.", exception.getMessage());
    }

    @Test
    void rejectsDeliveryTimeUntilSlotOccupationIsImplemented() {
        var product = new AdditionalService(site, "배달", 100, DELIVERY_TIME, true,
                LocalTime.of(9, 0), LocalTime.of(18, 0), 5);
        ReflectionTestUtils.setField(product, "id", 1L);
        var exception = assertThrows(IllegalStateException.class, () -> ServiceOrderPreparation.prepare(reservation(),
                List.of(new Selection(product, 1))));
        assertEquals("시간형 서비스는 5분 슬롯 점유 구현 전까지 주문할 수 없습니다.", exception.getMessage());
    }

    static Stream<Arguments> invalidProductInformation() {
        return Stream.of(Arguments.of("inputType", null), Arguments.of("price", -1),
                Arguments.of("name", null), Arguments.of("name", ""), Arguments.of("name", "   "));
    }

    @ParameterizedTest
    @MethodSource("invalidProductInformation")
    void rejectsInvalidPersistedProductInformation(String field, Object value) {
        var product = service(1L, 100);
        ReflectionTestUtils.setField(product, field, value);
        var exception = assertThrows(IllegalArgumentException.class, () -> ServiceOrderPreparation.prepare(reservation(),
                List.of(new Selection(product, 1))));
        assertEquals("상품 정보가 올바르지 않습니다.", exception.getMessage());
    }

    @Test
    void rejectsItemPriceMultiplicationOverflow() {
        assertAmountOverflow(List.of(new Selection(service(1L, Integer.MAX_VALUE), 2)));
    }

    @Test
    void rejectsOrderTotalAdditionOverflow() {
        assertAmountOverflow(List.of(new Selection(service(1L, Integer.MAX_VALUE), 1),
                new Selection(service(2L, 1), 1)));
    }

    @Test
    void snapshotsRemainUnchangedAfterProductAndSelectionChanges() {
        var product = service(1L, 100);
        var selections = new ArrayList<>(List.of(new Selection(product, 2)));
        var result = ServiceOrderPreparation.prepare(reservation(), selections);
        product.update("변경된 상품", 500, QUANTITY, false, null, null, null);
        selections.clear();

        assertEquals(List.of(new ItemSnapshot(1L, "상품 1", 100, 2, 200)), result.items());
        assertEquals(200, result.totalAmount());
        assertThrows(UnsupportedOperationException.class, () -> result.items().clear());
    }

    @Test
    void preparedOrderDefensivelyCopiesItsItemList() {
        var item = new ItemSnapshot(1L, "상품", 100, 2, 200);
        var items = new ArrayList<>(List.of(item));
        var result = new ServiceOrderPreparation.PreparedOrder(10L, PENDING_PAYMENT, items, 200);
        items.clear();
        assertEquals(List.of(item), result.items());
        assertThrows(UnsupportedOperationException.class, () -> result.items().add(item));
    }

    private void assertAmountOverflow(List<Selection> selections) {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> ServiceOrderPreparation.prepare(reservation(), selections));
        assertEquals("주문 금액이 허용 범위를 초과했습니다.", exception.getMessage());
        assertInstanceOf(ArithmeticException.class, exception.getCause());
    }

    private Reservation reservation() {
        var reservation = new Reservation();
        reservation.setId(10L);
        reservation.setEventType(ReservationEventType.RESERVED);
        reservation.setSite(site);
        return reservation;
    }

    private AdditionalService service(Long id, int price) {
        var product = new AdditionalService(site, "상품 " + id, price, QUANTITY, true, null, null, null);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private Site site(Long id) {
        var site = new Site("테스트 Site");
        ReflectionTestUtils.setField(site, "id", id);
        return site;
    }
}
