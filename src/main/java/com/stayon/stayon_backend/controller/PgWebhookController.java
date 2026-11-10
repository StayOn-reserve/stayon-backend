package com.stayon.stayon_backend.controller;

import com.stayon.stayon_backend.service.PgWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;

@RestController
@RequestMapping("/payments/webhooks")
@RequiredArgsConstructor
public class PgWebhookController {
    private final PgWebhookService service;
    @PostMapping("/{provider}")
    public ResponseEntity<Void> receive(@PathVariable String provider, @RequestHeader HttpHeaders headers, HttpServletRequest request) {
        byte[] body;
        try { body = request.getInputStream().readNBytes(PgWebhookService.MAX_BODY_BYTES + 1); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable webhook"); }
        service.receive(provider, body, headers);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
}
