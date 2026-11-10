package com.stayon.stayon_backend.dto.customer;

import com.stayon.stayon_backend.entity.PaymentAttempt.PreparationStatus;
import java.time.Instant;

public record CustomerPaymentReadyResponse(String id,PreparationStatus preparationStatus,String pcUrl,String mobileUrl,Instant expiresAt){}
