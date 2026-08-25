package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.client.BusinessApiClient;
import com.stayon.stayon_backend.dto.auth.LoginRequestDto;
import com.stayon.stayon_backend.dto.auth.LoginResponseDto;
import com.stayon.stayon_backend.dto.business.BusinessVerifyRequestDto;
import com.stayon.stayon_backend.dto.business.BusinessVerifyResponseDto;
import com.stayon.stayon_backend.entity.User;
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

        return new LoginResponseDto(
                accessToken,
                refreshToken
        );
    }
}