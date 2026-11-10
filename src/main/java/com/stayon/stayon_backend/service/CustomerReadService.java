package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.dto.customer.*;
import com.stayon.stayon_backend.entity.AdditionalService.ServiceInputType;
import com.stayon.stayon_backend.entity.ServiceOrder;
import com.stayon.stayon_backend.repository.AdditionalServiceRepository;
import com.stayon.stayon_backend.repository.ServiceOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.stayon.stayon_backend.service.ReservationAccessPolicy.Operation.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerReadService {
    private final ReservationAccessPolicy policy;
    private final AdditionalServiceRepository additionalServices;
    private final ServiceOrderRepository orders;

    public CustomerReservationResponse reservation(Authentication authentication) {
        var context = policy.authorizeCurrentReservation(authentication, READ_RESERVATION);
        return new CustomerReservationResponse(context.status(), context.checkInDate(), context.checkOutDate(),
                !context.access().readOnly());
    }

    public List<CustomerAdditionalServiceResponse> additionalServices(Authentication authentication) {
        var context = policy.authorizeCurrentReservation(authentication, CREATE_ORDER).access();
        return database(() -> additionalServices.findBySiteIdAndActiveTrueAndInputTypeOrderByIdAsc(
                        context.siteId(), ServiceInputType.QUANTITY).stream()
                .filter(ServiceOrderPreparation::isCustomerOrderable)
                .map(product -> new CustomerAdditionalServiceResponse(product.getId(), product.getName(), product.getPrice()))
                .toList());
    }

    public CustomerOrderPageResponse orders(Authentication authentication, int page, int size) {
        var context = policy.authorizeCurrentReservation(authentication, READ_ORDER).access();
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지는 0 이상, 크기는 1~100이어야 합니다.");
        }
        return database(() -> {
            var result = orders.findByReservation_Id(context.reservationId(), PageRequest.of(page, size, Sort.by("id").descending()));
            if (result.isEmpty()) return new CustomerOrderPageResponse(List.of(), page, size,
                    result.getTotalElements(), result.getTotalPages());
            var ids = result.getContent().stream().map(ServiceOrder::getId).toList();
            var withItems = orders.findCustomerOrdersWithItems(context.reservationId(), ids).stream()
                    .collect(Collectors.toMap(ServiceOrder::getId, order -> order));
            var content = ids.stream().map(id -> {
                var order = withItems.get(id);
                if (order == null) throw unavailable();
                return CustomerOrderResponse.from(order);
            }).toList();
            return new CustomerOrderPageResponse(content, page, size, result.getTotalElements(), result.getTotalPages());
        });
    }

    private static <T> T database(Supplier<T> query) {
        try { return query.get(); }
        catch (DataAccessException e) { throw unavailable(); }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "고객 조회 정보를 확인할 수 없습니다.");
    }
}
