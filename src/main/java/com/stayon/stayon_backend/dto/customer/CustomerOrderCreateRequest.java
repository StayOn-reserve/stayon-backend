package com.stayon.stayon_backend.dto.customer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

public record CustomerOrderCreateRequest(List<Item> items) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("허용되지 않은 주문 필드입니다."); }

    public record Item(@JsonDeserialize(using = StrictId.class) Long productId,
                       @JsonDeserialize(using = StrictQuantity.class) Integer quantity) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("상품 ID와 수량만 허용합니다."); }
    }

    public static class StrictId extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return context.reportInputMismatch(Long.class, "상품 ID는 JSON 정수여야 합니다.");
            return parser.getLongValue();
        }
    }
    public static class StrictQuantity extends ValueDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return context.reportInputMismatch(Integer.class, "수량은 JSON 정수여야 합니다.");
            return parser.getIntValue();
        }
    }
}
