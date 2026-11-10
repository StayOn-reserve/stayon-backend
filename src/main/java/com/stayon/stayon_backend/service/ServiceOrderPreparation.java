package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.AdditionalService;
import com.stayon.stayon_backend.entity.Reservation;
import com.stayon.stayon_backend.entity.ReservationEventType;
import com.stayon.stayon_backend.entity.ServiceOrder;
import com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 인증된 예약과 DB에서 조회한 상품을 받는 내부 계산이다.
 * 인가와 상품 잠금을 확보한 서비스에서 사용한다. 시간형 상품은 슬롯 구현 전까지 거부한다.
 * 결과는 저장된 주문이나 결제 허가를 의미하지 않는다.
 */
public final class ServiceOrderPreparation {
    private ServiceOrderPreparation() {
    }

    public record Selection(AdditionalService service, Integer quantity) {
    }

    public record ItemSnapshot(Long additionalServiceId, String serviceName, int unitPrice,
                               int quantity, int totalPrice) {
    }

    public record PreparedOrder(Long reservationId, ServiceOrderStatus initialStatus,
                                List<ItemSnapshot> items, int totalAmount) {
        public PreparedOrder {
            items = List.copyOf(items);
        }
    }

    /** Shared catalog/preparation eligibility; quantity, ownership and totals still need validation at creation. */
    public static boolean isCustomerOrderable(AdditionalService service) {
        return service != null && service.getId() != null && service.isActive()
                && service.getInputType() == AdditionalService.ServiceInputType.QUANTITY
                && service.getPrice() >= 0 && service.getName() != null && !service.getName().isBlank();
    }

    /** Maps validated snapshots to an unsaved aggregate; does not authorize or persist an order. */
    public static ServiceOrder prepareEntity(Reservation reservation, List<Selection> selections) {
        var selected = selections == null ? null : new ArrayList<>(selections);
        var prepared = prepare(reservation, selected);
        var order = new ServiceOrder(reservation);
        for (int index = 0; index < prepared.items().size(); index++) {
            var snapshot = prepared.items().get(index);
            order.addItem(selected.get(index).service(), snapshot.serviceName(), snapshot.unitPrice(),
                    snapshot.quantity(), snapshot.totalPrice());
        }
        return order;
    }

    /** DB entities must be loaded by the future authorized application service, never bound from JSON. */
    public static PreparedOrder prepare(Reservation reservation, List<Selection> selections) {
        if (reservation == null || reservation.getId() == null
                || reservation.getEventType() != ReservationEventType.RESERVED) {
            throw new IllegalArgumentException("유효한 RESERVED 예약이 필요합니다.");
        }
        if (reservation.getSite() == null || reservation.getSite().getId() == null) {
            throw new IllegalArgumentException("Site가 연결되지 않은 예약입니다.");
        }
        if (selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException("주문 상품은 하나 이상 필요합니다.");
        }
        var items = new ArrayList<ItemSnapshot>();
        var selectedIds = new HashSet<Long>();
        int totalAmount = 0;
        for (Selection selection : selections) {
            if (selection == null || selection.service() == null || selection.quantity() == null
                    || selection.quantity() <= 0) {
                throw new IllegalArgumentException("상품과 양수 수량이 필요합니다.");
            }
            AdditionalService service = selection.service();
            if (service.getId() == null || !selectedIds.add(service.getId())) {
                throw new IllegalArgumentException("존재하는 상품을 중복 없이 선택해야 합니다.");
            }
            if (service.getSite() == null || !reservation.getSite().getId().equals(service.getSite().getId())) {
                throw new IllegalArgumentException("예약 Site의 상품만 주문할 수 있습니다.");
            }
            service.requireOrderable();
            if (service.getInputType() == AdditionalService.ServiceInputType.DELIVERY_TIME) {
                throw new IllegalStateException("시간형 서비스는 5분 슬롯 점유 구현 전까지 주문할 수 없습니다.");
            }
            if (!isCustomerOrderable(service)) {
                throw new IllegalArgumentException("상품 정보가 올바르지 않습니다.");
            }
            try {
                int itemTotal = Math.multiplyExact(service.getPrice(), selection.quantity());
                totalAmount = Math.addExact(totalAmount, itemTotal);
                items.add(new ItemSnapshot(service.getId(), service.getName(), service.getPrice(),
                        selection.quantity(), itemTotal));
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("주문 금액이 허용 범위를 초과했습니다.", e);
            }
        }
        return new PreparedOrder(reservation.getId(), ServiceOrderStatus.PENDING_PAYMENT, items, totalAmount);
    }
}
