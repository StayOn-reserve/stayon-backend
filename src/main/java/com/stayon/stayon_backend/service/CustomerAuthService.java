package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.Platform;
import com.stayon.stayon_backend.entity.Reservation;
import com.stayon.stayon_backend.repository.CustomerAuthStore;
import com.stayon.stayon_backend.repository.CustomerAuthStore.Challenge;
import com.stayon.stayon_backend.repository.CustomerAuthStore.Session;
import com.stayon.stayon_backend.repository.ReservationRepository;
import com.stayon.stayon_backend.security.CustomerJwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.stayon.stayon_backend.service.CustomerSessionService.storage;

@Service
@RequiredArgsConstructor
public class CustomerAuthService {
    private final ReservationRepository reservations;
    private final CustomerAuthStore store;
    private final CustomerAuthSecrets secrets;
    private final SmsSender sms;
    private final CustomerJwtTokenProvider tokens;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public record ChallengeResponse(String challengeId, int expiresIn) { }
    public record TokenResponse(String accessToken, String tokenType, int expiresIn) { }

    public ChallengeResponse issue(Platform platform, String reservationNumber, String phoneNumber, String ip) {
        if (!storage(() -> store.allowIssue(secrets.hmac("ip", ip)))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        if (platform == null || reservationNumber == null || reservationNumber.isBlank()
                || reservationNumber.length() > 255 || phoneNumber == null || phoneNumber.length() > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "인증 요청 형식이 올바르지 않습니다.");
        }
        Reservation reservation;
        try {
            reservation = reservations.findByPlatformAndReservationId(platform, reservationNumber)
                    .filter(r -> reservationNumber.equals(r.getReservationId())).orElse(null);
        } catch (DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        String entered = secrets.normalizePhone(phoneNumber);
        String registered = reservation == null ? null : secrets.normalizePhone(reservation.getCustomerPhone());
        boolean matches = entered != null && registered != null && registered.equals(entered);
        String id = UUID.randomUUID().toString();
        String otp = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
        String phoneHash = secrets.hmac("phone", entered == null ? phoneNumber : entered);
        String identity = secrets.hmac("reservation", platform.name() + ":" + reservationNumber);
        var challenge = new Challenge(id, matches ? reservation.getId() : 0L, phoneHash,
                secrets.hmac("otp", id + ":" + otp), identity);
        if (storage(() -> store.begin(challenge))) {
            if (matches) {
                try {
                    sms.sendOtp(registered, otp, id);
                } catch (RuntimeException e) {
                    storage(() -> { store.invalidate(id); return true; });
                    return new ChallengeResponse(id, 180);
                }
                storage(() -> { store.sent(id); return true; });
            } else {
                storage(() -> { store.invalidate(id); return true; });
            }
        }
        // Unknown reservation, phone mismatch, subject rate limit and sender failure share the same response.
        return new ChallengeResponse(id, 180);
    }

    public TokenResponse verify(String challengeId, String otp, String ip) {
        if (!storage(() -> store.allowVerification(secrets.hmac("ip", ip)))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        if (!isUuid(challengeId) || otp == null || !otp.matches("[0-9]{8}")) throw unauthorized();
        var challenge = storage(() -> store.findChallenge(challengeId)).orElseThrow(CustomerAuthService::unauthorized);
        Reservation reservation;
        try {
            reservation = reservations.findById(challenge.reservationId()).orElse(null);
        } catch (DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        String registered = reservation == null ? null : secrets.normalizePhone(reservation.getCustomerPhone());
        if (registered == null || !secrets.matches(challenge.phoneHash(), secrets.hmac("phone", registered))) {
            storage(() -> { store.invalidate(challengeId); return true; });
            throw unauthorized();
        }
        var session = new Session(UUID.randomUUID().toString(), challenge.reservationId(), challenge.phoneHash(),
                Set.of("orders:read", "orders:create", "orders:cancel"), clock.instant().getEpochSecond() + 900);
        // Sign before consuming: a signing failure must not burn a successfully verified OTP.
        String accessToken = tokens.createAccessToken(session);
        if (!storage(() -> store.consume(challenge, secrets.hmac("otp", challengeId + ":" + otp), session))) {
            throw unauthorized();
        }
        return new TokenResponse(accessToken, "Bearer", 900);
    }

    private static boolean isUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException e) { return false; }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 정보를 확인할 수 없습니다.");
    }
}
