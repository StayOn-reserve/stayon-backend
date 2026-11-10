package com.stayon.stayon_backend.dto.customer;

import java.util.List;

public record CustomerOrderPageResponse(List<CustomerOrderResponse> content, int page, int size,
                                        long totalElements, int totalPages) {
    public CustomerOrderPageResponse { content = List.copyOf(content); }
}
