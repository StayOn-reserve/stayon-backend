package com.stayon.stayon_backend.dto.customer;

import com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus;
import java.util.List;
import java.util.Comparator;
import com.stayon.stayon_backend.entity.ServiceOrder;
import com.stayon.stayon_backend.entity.ServiceOrderItem;

public record CustomerOrderResponse(Long id, ServiceOrderStatus status, int totalAmount, List<Item> items) {
    public CustomerOrderResponse { items = List.copyOf(items); }

    public record Item(Long id, String serviceName, int unitPrice, int quantity, int totalPrice) { }

    public static CustomerOrderResponse from(ServiceOrder order) {
        var items = order.getItems().stream().sorted(Comparator.comparing(ServiceOrderItem::getId))
                .map(item -> new Item(item.getId(), item.getServiceName(), item.getUnitPrice(),
                        item.getQuantity(), item.getTotalPrice())).toList();
        return new CustomerOrderResponse(order.getId(), order.getStatus(), order.getTotalAmount(), items);
    }
}
