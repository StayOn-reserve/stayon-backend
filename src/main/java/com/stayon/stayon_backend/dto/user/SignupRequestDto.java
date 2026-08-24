package com.stayon.stayon_backend.dto.user;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequestDto {

    private String loginId;
    private String password;
    private String name;
    private String email;
    private String role;
    private String businessNumber;
}
