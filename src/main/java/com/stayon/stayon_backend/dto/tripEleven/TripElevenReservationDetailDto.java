package com.stayon.stayon_backend.dto.tripEleven;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class TripElevenReservationDetailDto {

    private String reservationId;
    private String reservationStatus;
    private String reservationDateTime;

    private String customerName;
    private String phone;

    private String roomName;

    private int adultCount;
    private int childCount;
    private int infantCount;

    private List<String> stayDates;

    private int totalAmount;

    private String customerRequest;
    private String pensionMemo;
}