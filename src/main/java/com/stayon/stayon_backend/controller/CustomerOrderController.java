package com.stayon.stayon_backend.controller;

import com.stayon.stayon_backend.dto.customer.CustomerOrderCreateRequest;
import com.stayon.stayon_backend.dto.customer.CustomerOrderResponse;
import com.stayon.stayon_backend.service.CustomerOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customer/orders")
@RequiredArgsConstructor
public class CustomerOrderController {
    private final CustomerOrderService service;

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<CustomerOrderResponse> cancel(Authentication authentication, @PathVariable Long orderId) {
        var result = service.cancel(authentication, orderId);
        return ResponseEntity.status(result.accepted() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(result.order());
    }

    @PostMapping
    public ResponseEntity<CustomerOrderResponse> create(Authentication authentication,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CustomerOrderCreateRequest request) {
        var result = service.create(authentication, key, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(result.order());
    }
}
