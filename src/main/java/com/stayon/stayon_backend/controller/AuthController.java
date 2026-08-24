package com.stayon.stayon_backend.controller;

import com.stayon.stayon_backend.dto.business.BusinessVerifyRequestDto;
import com.stayon.stayon_backend.service.AuthService;
import com.stayon.stayon_backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Controller("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final UserService userService;

    @PostMapping("/business-number")
    public ResponseEntity<?> authenticateBusinessNumber(@RequestBody BusinessVerifyRequestDto dto) {
        boolean exists = userService.checkBusinessNumberExists(dto.getBusinesses().getFirst().getB_no());
        if(exists){
            return ResponseEntity.badRequest().body("Business number already exists");
        }
        String result = authService.authenticateBusinessNumber(dto);
        return ResponseEntity.ok(result);
    }
}
