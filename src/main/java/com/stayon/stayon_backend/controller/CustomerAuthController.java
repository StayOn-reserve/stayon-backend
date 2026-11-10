package com.stayon.stayon_backend.controller;

import com.stayon.stayon_backend.entity.Platform;
import com.stayon.stayon_backend.security.CustomerPrincipal;
import com.stayon.stayon_backend.service.CustomerAuthService;
import com.stayon.stayon_backend.service.CustomerSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customer/auth")
@RequiredArgsConstructor
public class CustomerAuthController {
    private final CustomerAuthService auth;
    private final CustomerSessionService sessions;

    public record ChallengeRequest(Platform platform, String reservationNumber, String phoneNumber) { }
    public record VerificationRequest(String challengeId, String otp) { }

    @PostMapping("/otp/challenges")
    public ResponseEntity<CustomerAuthService.ChallengeResponse> challenge(@RequestBody ChallengeRequest body,
                                                                         HttpServletRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(auth.issue(body.platform(), body.reservationNumber(), body.phoneNumber(), request.getRemoteAddr()));
    }

    @PostMapping("/otp/verifications")
    public ResponseEntity<CustomerAuthService.TokenResponse> verify(@RequestBody VerificationRequest body,
                                                                   HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(auth.verify(body.challengeId(), body.otp(), request.getRemoteAddr()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal CustomerPrincipal principal) {
        sessions.logout(principal);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
