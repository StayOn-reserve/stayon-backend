package com.stayon.stayon_backend.dto.user;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupResponseDto {
    private String email;
    @Builder
    private SignupResponseDto(String email){
        this.email = email;
    }
}
