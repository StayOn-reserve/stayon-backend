package com.stayon.stayon_backend.controller;

import com.stayon.stayon_backend.dto.additionalservice.*;
import com.stayon.stayon_backend.service.AdditionalServiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/sites/{siteId}/additional-services")
@RequiredArgsConstructor
public class AdditionalServiceController {
    private final AdditionalServiceService service;

    @PostMapping
    public ResponseEntity<AdditionalServiceResponseDto> create(@AuthenticationPrincipal Long userId,
            @PathVariable Long siteId, @RequestBody AdditionalServiceRequestDto dto) {
        var response = service.create(userId, siteId, dto);
        return ResponseEntity.created(URI.create("/sites/" + siteId + "/additional-services/" + response.id()))
                .body(response);
    }

    @GetMapping
    public ResponseEntity<List<AdditionalServiceResponseDto>> list(@AuthenticationPrincipal Long userId,
            @PathVariable Long siteId) {
        return ResponseEntity.ok(service.list(userId, siteId));
    }

    @GetMapping("/{serviceId}")
    public ResponseEntity<AdditionalServiceResponseDto> get(@AuthenticationPrincipal Long userId,
            @PathVariable Long siteId, @PathVariable Long serviceId) {
        return ResponseEntity.ok(service.get(userId, siteId, serviceId));
    }

    @PutMapping("/{serviceId}")
    public ResponseEntity<AdditionalServiceResponseDto> update(@AuthenticationPrincipal Long userId,
            @PathVariable Long siteId, @PathVariable Long serviceId, @RequestBody AdditionalServiceRequestDto dto) {
        return ResponseEntity.ok(service.update(userId, siteId, serviceId, dto));
    }

    @PatchMapping("/{serviceId}/active")
    public ResponseEntity<AdditionalServiceResponseDto> changeActive(@AuthenticationPrincipal Long userId,
            @PathVariable Long siteId, @PathVariable Long serviceId, @RequestBody AdditionalServiceActiveRequestDto dto) {
        return ResponseEntity.ok(service.changeActive(userId, siteId, serviceId, dto.active()));
    }

    @DeleteMapping("/{serviceId}")
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal Long userId,
            @PathVariable Long siteId, @PathVariable Long serviceId) {
        service.changeActive(userId, siteId, serviceId, false);
        return ResponseEntity.noContent().build();
    }
}
