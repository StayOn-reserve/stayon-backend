package com.stayon.stayon_backend.dto.customer;

import com.stayon.stayon_backend.entity.ReservationEventType;
import java.time.LocalDate;

public record CustomerReservationResponse(ReservationEventType status, LocalDate checkInDate,
                                          LocalDate checkOutDate, boolean orderable) { }
