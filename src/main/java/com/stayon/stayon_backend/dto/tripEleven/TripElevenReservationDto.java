package com.stayon.stayon_backend.dto.tripEleven;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TripElevenReservationDto {

    private String roomName;
    private String status;
    private String reservationId;
    private String customerName;
    private String stayInfo;
    private String amount;
    private int adultCount;
    private int childCount;
    private int infantCount;
}