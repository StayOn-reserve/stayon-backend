package com.stayon.stayon_backend.controller;

import com.stayon.stayon_backend.dto.customer.*;
import com.stayon.stayon_backend.service.CustomerPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customer")
@RequiredArgsConstructor
public class CustomerPaymentController {
    private final CustomerPaymentService service;
    private final com.stayon.stayon_backend.service.KakaoPayReadyService ready;
    @PostMapping("/payment-attempts/{id}/ready")
    public ResponseEntity<CustomerPaymentReadyResponse> ready(Authentication auth,@PathVariable String id,@RequestBody(required=false)CustomerPaymentReadyRequest request){
        var result=ready.ready(auth,id);
        return response(result.preparationStatus()==com.stayon.stayon_backend.entity.PaymentAttempt.PreparationStatus.PREPARED?HttpStatus.OK:HttpStatus.ACCEPTED,result);
    }
    @PostMapping("/orders/{orderId}/payment-attempts")
    public ResponseEntity<CustomerPaymentResponse> create(Authentication auth, @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CustomerPaymentCreateRequest request) {
        var result = service.create(auth, orderId, request, key);
        return response(result.created() ? HttpStatus.CREATED : HttpStatus.OK, result.payment());
    }
    @PostMapping("/payment-attempts/{id}/approve")
    public ResponseEntity<CustomerPaymentResponse> approve(Authentication auth, @PathVariable String id,
            @RequestBody(required = false) CustomerPaymentApproveRequest request) {
        var result = service.approve(auth, id, request);
        var status = switch (result.status()) {
            case APPROVING, UNKNOWN, CANCEL_PENDING -> HttpStatus.ACCEPTED;
            case FAILED, CANCELED -> HttpStatus.CONFLICT;
            default -> HttpStatus.OK;
        };
        return response(status, result);
    }
    @GetMapping("/payment-attempts/{id}")
    public ResponseEntity<CustomerPaymentResponse> status(Authentication auth, @PathVariable String id) {
        return response(HttpStatus.OK, service.status(auth, id));
    }
    @GetMapping("/orders/{orderId}/payment-attempts")
    public ResponseEntity<CustomerPaymentPageResponse> history(Authentication auth, @PathVariable Long orderId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return response(HttpStatus.OK, service.history(auth, orderId, page, size));
    }
    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
