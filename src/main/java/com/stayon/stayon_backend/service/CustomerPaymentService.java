package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.dto.customer.*;
import com.stayon.stayon_backend.repository.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.function.Supplier;
import static com.stayon.stayon_backend.service.ReservationAccessPolicy.Operation.READ_ORDER;

@Service
@RequiredArgsConstructor
public class CustomerPaymentService {
    private final PaymentTransactions transactions;
    private final PaymentOrchestrator payments;
    private final ReservationAccessPolicy policy;
    private final PaymentAttemptRepository attempts;
    public record Created(CustomerPaymentResponse payment, boolean created) { }
    public Created create(Authentication auth, Long orderId, CustomerPaymentCreateRequest request, String key) {
        var result = transactions.createAttemptResult(auth, orderId, request == null ? null : request.provider(), key);
        return new Created(CustomerPaymentResponse.from(result.view()), result.created());
    }
    public CustomerPaymentResponse approve(Authentication auth, String id, CustomerPaymentApproveRequest request) {
        // Authenticate and check ownership before interpreting provider-specific credentials.
        var initial = transactions.inspect(auth, id);
        if (initial.status() != com.stayon.stayon_backend.entity.PaymentAttempt.Status.READY) return CustomerPaymentResponse.from(initial);
        PgAdapter.BrowserAuthentication proof;
        try { proof = request == null ? PgAdapter.BrowserAuthentication.none() : request.proof(); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid provider authentication"); }
        return CustomerPaymentResponse.from(payments.approve(auth, id, proof));
    }
    @Transactional(readOnly = true)
    public CustomerPaymentResponse status(Authentication auth, String id) {
        var context = policy.authorizeCurrentReservation(auth, READ_ORDER).access();
        return database(() -> {
            var attempt = attempts.findByIdAndServiceOrder_Reservation_Id(id, context.reservationId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            policy.authorizeOrder(auth, attempt.getServiceOrder().getId(), READ_ORDER);
            return CustomerPaymentResponse.from(attempt);
        });
    }
    @Transactional(readOnly = true)
    public CustomerPaymentPageResponse history(Authentication auth, Long orderId, int page, int size) {
        var context = policy.authorizeOrder(auth, orderId, READ_ORDER);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        return database(() -> {
            var rows = attempts.findByServiceOrder_IdAndServiceOrder_Reservation_Id(orderId, context.reservationId(),
                    PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
            return new CustomerPaymentPageResponse(rows.stream().map(CustomerPaymentResponse::from).toList(), page, size, rows.getTotalElements(), rows.getTotalPages());
        });
    }
    private <T> T database(Supplier<T> work) {
        try { return work.get(); }
        catch (DataAccessException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment information is unavailable"); }
    }
}
