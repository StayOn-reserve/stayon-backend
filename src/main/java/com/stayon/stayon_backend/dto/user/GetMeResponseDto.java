package com.stayon.stayon_backend.dto.user;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class GetMeResponseDto {
    private Long userId;
    private String email;
    private String name;
}
