package com.mirador.order;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "OrderLine as returned by the API")
public record OrderLineDto(
        Long id,
        Long orderId,
        Long productId,
        Integer quantity,
        BigDecimal unitPriceAtOrder,
        OrderLineStatus status,
        Instant createdAt
) {

    public static OrderLineDto from(OrderLine ol) {
        return new OrderLineDto(
                ol.getId(),
                ol.getOrderId(),
                ol.getProductId(),
                ol.getQuantity(),
                ol.getUnitPriceAtOrder(),
                ol.getStatus(),
                ol.getCreatedAt()
        );
    }
}
