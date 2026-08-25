package com.stayon.stayon_backend.dto.user;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequestDto {

    private String email;
    private String password;
    private String name;
    private String role;
    private String businessNumber;
}
