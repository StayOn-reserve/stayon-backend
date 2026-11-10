package com.stayon.stayon_backend.dto.customer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public record CustomerPaymentReadyRequest(){
    @JsonAnySetter public void reject(String name,Object value){throw new IllegalArgumentException("Unsupported ready field");}
}
