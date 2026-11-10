package com.stayon.stayon_backend.controller;

import com.stayon.stayon_backend.dto.customer.*;
import com.stayon.stayon_backend.service.CustomerReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/customer")
@RequiredArgsConstructor
public class CustomerReadController {
    private final CustomerReadService service;

    @GetMapping("/reservation")
    public ResponseEntity<CustomerReservationResponse> reservation(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reservation(authentication));
    }

    @GetMapping("/reservation/additional-services")
    public ResponseEntity<List<CustomerAdditionalServiceResponse>> additionalServices(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.additionalServices(authentication));
    }

    @GetMapping("/orders")
    public ResponseEntity<CustomerOrderPageResponse> orders(Authentication authentication,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.orders(authentication, page, size));
    }
}
