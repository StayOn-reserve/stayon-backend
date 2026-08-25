package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.dto.user.EmailCheckResponseDto;
import com.stayon.stayon_backend.dto.user.GetMeResponseDto;
import com.stayon.stayon_backend.dto.user.SignupRequestDto;
import com.stayon.stayon_backend.dto.user.SignupResponseDto;
import com.stayon.stayon_backend.entity.Role;
import com.stayon.stayon_backend.entity.User;
import com.stayon.stayon_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public SignupResponseDto signup(SignupRequestDto dto){
        User user = User.builder()
                .email(dto.getEmail())
                .password(passwordEncoder.encode(dto.getPassword()))
                .name(dto.getName())
                .businessNumber(dto.getBusinessNumber())
                .role(Role.getRole(dto.getRole()))
                .build();
        userRepository.save(user);
        return SignupResponseDto.builder()
                .email(user.getEmail())
                .build();
    }
    public boolean checkBusinessNumberExists(String businessNumber){
        return userRepository.existsByBusinessNumber(businessNumber);
    }
    public EmailCheckResponseDto checkUsernameExists(String email) {
        boolean exists = userRepository.existsByEmail(email);
        return new EmailCheckResponseDto(exists);
    }
    public GetMeResponseDto getMe(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return GetMeResponseDto.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .name(user.getName())
                .build();
    }
}
