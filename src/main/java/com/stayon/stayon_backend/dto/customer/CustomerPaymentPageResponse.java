package com.stayon.stayon_backend.dto.customer;

import java.util.List;

public record CustomerPaymentPageResponse(List<CustomerPaymentResponse> content, int page, int size, long totalElements, int totalPages) {
    public CustomerPaymentPageResponse { content = List.copyOf(content); }
}
