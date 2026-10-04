package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.dto.accommodation.AccommodationCreateRequestDto;
import com.stayon.stayon_backend.dto.accommodation.AccommodationResponseDto;
import com.stayon.stayon_backend.dto.accommodation.AccommodationUpdateDto;
import com.stayon.stayon_backend.entity.Accommodation;
import com.stayon.stayon_backend.entity.User;
import com.stayon.stayon_backend.repository.AccommodationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccommodationService {
    private final AccommodationRepository accommodationRepository;
    private final UserService userService;
    public AccommodationResponseDto createAccommodation(Long userId, AccommodationCreateRequestDto dto){
        // Assuming the user is retrieved from the authentication context
        User user = userService.getCurrentUser(userId);
        Accommodation accommodation = Accommodation.builder()
                .name(dto.getAccommodationName())
                .reservationProvider(dto.getReservationProvider())
                .owner(user)
                .build();
        Accommodation savedAccomm = accommodationRepository.save(accommodation);
        return savedAccomm.entityToDto();
    }
    public AccommodationResponseDto deactiveAccommodation (Long userId, AccommodationUpdateDto dto){
        Accommodation accommodation = accommodationRepository.findById(dto.getAccommodationId()).orElseThrow();
        accommodation.changeAccommodationDeactive();
        accommodationRepository.save(accommodation);
        return accommodation.entityToDto();
    }
    public AccommodationResponseDto activeAccommodation(Long userId, AccommodationUpdateDto dto){
        Accommodation accommodation = accommodationRepository.findById(dto.getAccommodationId()).orElseThrow();
        accommodation.changeAccommodationActive();
        accommodationRepository.save(accommodation);
        return accommodation.entityToDto();
    }

}
