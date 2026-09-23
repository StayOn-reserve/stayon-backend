package com.stayon.stayon_backend.controller;

import com.stayon.stayon_backend.service.TripElevenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trip-eleven")
@RequiredArgsConstructor
public class TripElevenController {
    private final TripElevenService tripElevenService;

    @GetMapping("/sync")
    public ResponseEntity<Void> sync(
            @RequestParam String date
    ) {
        tripElevenService.sync(date);

        return ResponseEntity.ok().build();
    }

    //    @GetMapping("/sync/today")
//    public ResponseEntity<List<TripElevenReservationDto>> syncToday() {
//
//        List<TripElevenReservationDto> reservations =
//                tripElevenScrapingService.syncToday();
//
//        return ResponseEntity.ok(reservations);
//    }
    @GetMapping("/sync/today")
    public ResponseEntity<String> syncToday() {
        return ResponseEntity.ok(
                tripElevenService.syncToday().html()
        );
    }
}
