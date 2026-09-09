package com.stayon.stayon_backend.controller;

import com.stayon.stayon_backend.dto.accommodation.AccommodationCreateRequestDto;
import com.stayon.stayon_backend.dto.accommodation.AccommodationResponseDto;
import com.stayon.stayon_backend.entity.User;
import com.stayon.stayon_backend.service.AccommodationService;
import com.stayon.stayon_backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/accommodation")
@RequiredArgsConstructor
public class AccommodationController {
    private final AccommodationService accommodationService;
    @PostMapping("/create")
    public ResponseEntity<AccommodationResponseDto> createAccommodation(@AuthenticationPrincipal Long userId, @RequestBody AccommodationCreateRequestDto dto) {
        AccommodationResponseDto response = accommodationService.createAccommodation(userId, dto);
        return ResponseEntity.ok(response);
    }
}
