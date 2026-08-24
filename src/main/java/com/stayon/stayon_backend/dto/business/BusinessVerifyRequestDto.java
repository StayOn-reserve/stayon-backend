package com.stayon.stayon_backend.dto.business;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BusinessVerifyRequestDto {

    private List<BusinessInfo> businesses;

    @Getter
    @Setter
    public static class BusinessInfo {
        private String b_no;
        private String start_dt;
        private String p_nm;
    }
}