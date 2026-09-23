package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.client.TripElevenClient;
import com.stayon.stayon_backend.dto.tripEleven.TripElevenReservationDto;
import lombok.RequiredArgsConstructor;
import org.jsoup.nodes.Document;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TripElevenService {

    private final TripElevenClient tripElevenClient;
    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "trip-eleven:session:";

    public void save(Long accommodationId, String sessionId) {
        redisTemplate.opsForValue().set(
                KEY_PREFIX + accommodationId,
                sessionId
        );
    }

    public String get(Long accommodationId) {
        return redisTemplate.opsForValue().get(
                KEY_PREFIX + accommodationId
        );
    }

    public void delete(Long accommodationId) {
        redisTemplate.delete(
                KEY_PREFIX + accommodationId
        );
    }

    public void sync(String date) {
        // Daily
        Document dailyPage =
                tripElevenClient.getDailyReservations(date);

        List<TripElevenReservationDto> reservations =
                tripElevenClient.parseDailyReservations(dailyPage);

        for (TripElevenReservationDto reservation : reservations) {

            Document detailPage =
                    tripElevenClient.getReservationDetail(
                            reservation.getReservationId()
                    );

            // 상세정보 파싱
            // StayOn Reservation 저장/업데이트
        }
    }

    //    public List<TripElevenReservationDto> syncToday() {
//
//        Document dailyPage =
//                tripElevenClient.getTodayReservation();
//
//        return tripElevenClient.parseDailyReservations(dailyPage);
//    }
    public Document syncToday() {
        return tripElevenClient.getTodayReservation();
    }
}