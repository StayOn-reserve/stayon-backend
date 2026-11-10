package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.dto.customer.CustomerOrderCreateRequest;
import com.stayon.stayon_backend.dto.customer.CustomerOrderResponse;
import com.stayon.stayon_backend.repository.AdditionalServiceRepository;
import com.stayon.stayon_backend.repository.ServiceOrderRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

@Service
public class CustomerOrderService {
    private final ReservationAccessPolicy policy;
    private final AdditionalServiceRepository products;
    private final ServiceOrderRepository orders;
    private final TransactionTemplate transaction;
    private final PaymentTransactions payments;

    public CustomerOrderService(ReservationAccessPolicy policy, AdditionalServiceRepository products,
            ServiceOrderRepository orders, PlatformTransactionManager transactionManager, PaymentTransactions payments) {
        this.policy = policy; this.products = products; this.orders = orders;
        this.payments = payments;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public record CreationResult(CustomerOrderResponse order, boolean created) { }

    public record CancellationResult(CustomerOrderResponse order, boolean changed, boolean accepted) { }

    public CancellationResult cancel(Authentication authentication, Long orderId) {
        try {
            return transaction.execute(status -> {
                var order = policy.lockOrderCancellation(authentication, orderId);
                // Recheck scope, contact and date after any wait on the order lock.
                policy.authorizeOrder(authentication, orderId, ReservationAccessPolicy.Operation.CANCEL_ORDER);
                PaymentTransactions.Cancellation result;
                try { result = payments.requestCancellationLocked(order); }
                catch (IllegalStateException e) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "미결제 주문만 취소할 수 있습니다.");
                }
                if (result.changed()) orders.flush();
                return new CancellationResult(CustomerOrderResponse.from(order), result.changed(), result.accepted());
            });
        } catch (DataAccessException | TransactionException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "주문을 취소할 수 없습니다. 같은 주문으로 다시 확인해 주세요.");
        }
    }

    public CreationResult create(Authentication authentication, String key, CustomerOrderCreateRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw badRequest();
        var items = canonicalItems(request);
        String keyHash = hash(key);
        String requestHash = hash("customer-order-v1\n" + items.stream()
                .map(item -> item.productId() + ":" + item.quantity()).collect(java.util.stream.Collectors.joining(";")));
        // Catch transaction/commit failures outside the transaction. Never retry a possibly committed request automatically.
        try {
            return transaction.execute(status -> {
                var reservation = policy.lockOrderCreationScope(authentication);
                var existing = orders.findByReservation_IdAndIdempotencyKeyHash(reservation.getId(), keyHash);
                if (existing.isPresent()) {
                    if (!requestHash.equals(existing.get().getRequestHash()))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "멱등성 키의 요청이 다릅니다.");
                    return new CreationResult(CustomerOrderResponse.from(existing.get()), false);
                }
                policy.authorizeCurrentReservation(authentication, ReservationAccessPolicy.Operation.CREATE_ORDER);
                var selections = new ArrayList<ServiceOrderPreparation.Selection>();
                for (var item : items) {
                    var product = products.findForCustomerOrderForUpdate(item.productId(), reservation.getSite().getId())
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                    if (!ServiceOrderPreparation.isCustomerOrderable(product))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 주문할 수 없는 상품입니다.");
                    selections.add(new ServiceOrderPreparation.Selection(product, item.quantity()));
                }
                // Recheck date/session after waiting for product locks, before any INSERT.
                policy.authorizeCurrentReservation(authentication, ReservationAccessPolicy.Operation.CREATE_ORDER);
                com.stayon.stayon_backend.entity.ServiceOrder order;
                try { order = ServiceOrderPreparation.prepareEntity(reservation, selections); }
                catch (IllegalArgumentException | IllegalStateException e) { throw badRequest(); }
                order.identifyCreation(keyHash, requestHash);
                return new CreationResult(CustomerOrderResponse.from(orders.saveAndFlush(order)), true);
            });
        } catch (DataAccessException | TransactionException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "주문을 저장할 수 없습니다. 같은 키로 다시 확인해 주세요.");
        }
    }

    private List<CustomerOrderCreateRequest.Item> canonicalItems(CustomerOrderCreateRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty() || request.items().size() > 100) throw badRequest();
        var snapshot = new ArrayList<>(request.items());
        var ids = new HashSet<Long>();
        for (var item : snapshot) {
            if (item == null || item.productId() == null || item.productId() <= 0 || item.quantity() == null
                    || item.quantity() <= 0 || !ids.add(item.productId())) throw badRequest();
        }
        return snapshot.stream().sorted(Comparator.comparing(CustomerOrderCreateRequest.Item::productId)).toList();
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "상품 ID와 양의 정수 수량, 유효한 멱등성 키가 필요합니다.");
    }
}
