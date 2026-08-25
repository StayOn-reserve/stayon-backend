package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.client.BusinessApiClient;
import com.stayon.stayon_backend.config.JwtProperties;
import com.stayon.stayon_backend.dto.auth.LoginRequestDto;
import com.stayon.stayon_backend.dto.auth.LoginResponseDto;
import com.stayon.stayon_backend.dto.auth.TokenRefreshRequestDto;
import com.stayon.stayon_backend.dto.auth.TokenRefreshResponseDto;
import com.stayon.stayon_backend.dto.business.BusinessVerifyRequestDto;
import com.stayon.stayon_backend.dto.business.BusinessVerifyResponseDto;
import com.stayon.stayon_backend.entity.User;
import com.stayon.stayon_backend.repository.RefreshTokenRepository;
import com.stayon.stayon_backend.repository.UserRepository;
import com.stayon.stayon_backend.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    private final BusinessApiClient businessApiClient;
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;

    public String authenticateBusinessNumber(BusinessVerifyRequestDto dto) {
        BusinessVerifyResponseDto response = businessApiClient.verify(dto);
        log.info("Business verification result: {}", response.toString());
        return response.isVerified() ? "Authenticated" : "Not Authenticated";
    }

    public LoginResponseDto login(LoginRequestDto dto) {

        User user = userRepository.findByEmail(dto.getEmail())
                .orElseThrow(() ->
                        new IllegalArgumentException("존재하지 않는 사용자입니다.")
                );

        if (!user.checkPassword(dto.getPassword(), passwordEncoder)){
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        String accessToken = jwtTokenProvider.createAccessToken(
                user.getUserId(),
                user.getRole().name()
        );

        String refreshToken = jwtTokenProvider.createRefreshToken(
                user.getUserId()
        );
        refreshTokenRepository.save(user.getUserId(), refreshToken, jwtProperties.getRefreshTokenExpiration());
        return new LoginResponseDto(
                accessToken,
                refreshToken
        );
    }

    public TokenRefreshResponseDto refresh(
            TokenRefreshRequestDto dto
    ) {
        String refreshToken = dto.getRefreshToken();

        // Refresh Token 자체의 서명 및 만료시간 검증
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("유효하지 않은 Refresh Token입니다.");
        }

        // Refresh Token에서 userId 추출
        Long userId = jwtTokenProvider.getUserId(refreshToken);

        // Redis에 저장된 Refresh Token 조회
        String storedRefreshToken =
                refreshTokenRepository.findByUserId(userId);

        // Redis에 없거나 현재 전달받은 토큰과 다르면 인증 실패
        if (storedRefreshToken == null ||
                !storedRefreshToken.equals(refreshToken)) {

            throw new IllegalArgumentException(
                    "Refresh Token이 일치하지 않습니다."
            );
        }

        // 새로운 Access Token 발급
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "사용자를 찾을 수 없습니다."
                        )
                );

        String accessToken =
                jwtTokenProvider.createAccessToken(
                        user.getUserId(),
                        user.getRole().name()
                );

        return new TokenRefreshResponseDto(accessToken);
    }
    public void logout(Long userId) {
        refreshTokenRepository.delete(userId);
    }
}