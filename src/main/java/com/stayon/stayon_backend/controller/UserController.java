package com.stayon.stayon_backend.controller;

import com.stayon.stayon_backend.dto.user.*;
import com.stayon.stayon_backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Controller
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping("/check-email")
    public ResponseEntity<EmailCheckResponseDto> checkEmailExists(@RequestBody EmailCheckRequestDto dto) {
        EmailCheckResponseDto response = userService.checkUsernameExists(dto.getEmail());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponseDto> signup(@RequestBody SignupRequestDto dto){
        SignupResponseDto response = userService.signup(dto);
        return ResponseEntity.ok(response);
    }
    @GetMapping("/me")
    public ResponseEntity<GetMeResponseDto> getMe(Authentication authentication){
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(userService.getMe(userId));
    }
}
