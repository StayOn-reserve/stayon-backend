package com.stayon.stayon_backend.dto.business;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BusinessVerifyResponseDto {

    private int request_cnt;
    private String status_code;
    private List<BusinessData> data;

    @Getter
    @Setter
    public static class BusinessData {
        private String b_no;
        private String valid;
        private String valid_msg;
        private RequestParam request_param;
        public boolean isVerified(){
            return valid.equals("01");
        }
    }

    @Getter
    @Setter
    public static class RequestParam {
        private String b_no;
        private String start_dt;
        private String p_nm;
    }
    public boolean isVerified(){
        return data != null && !data.isEmpty() && data.getFirst().isVerified();
    }
}