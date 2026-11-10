package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.dto.additionalservice.*;
import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdditionalServiceService {
    private final SiteRepository siteRepository;
    private final AdditionalServiceRepository additionalServiceRepository;
    private final UserRepository userRepository;

    @Transactional
    public AdditionalServiceResponseDto create(Long userId, Long siteId, AdditionalServiceRequestDto dto) {
        Site site = ownedSite(userId, siteId);
        validateRequired(dto);
        try {
            return AdditionalServiceResponseDto.from(additionalServiceRepository.save(new AdditionalService(
                    site, dto.name(), dto.price(), dto.inputType(), dto.active(),
                    dto.openTime(), dto.closeTime(), dto.intervalMinutes())));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    public List<AdditionalServiceResponseDto> list(Long userId, Long siteId) {
        ownedSite(userId, siteId);
        return additionalServiceRepository.findBySiteIdOrderByIdAsc(siteId).stream()
                .map(AdditionalServiceResponseDto::from).toList();
    }

    public AdditionalServiceResponseDto get(Long userId, Long siteId, Long serviceId) {
        ownedSite(userId, siteId);
        return AdditionalServiceResponseDto.from(findService(siteId, serviceId));
    }

    @Transactional
    public AdditionalServiceResponseDto update(Long userId, Long siteId, Long serviceId, AdditionalServiceRequestDto dto) {
        ownedSite(userId, siteId);
        AdditionalService service = findService(siteId, serviceId);
        validateRequired(dto);
        try {
            service.update(dto.name(), dto.price(), dto.inputType(), dto.active(),
                    dto.openTime(), dto.closeTime(), dto.intervalMinutes());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return AdditionalServiceResponseDto.from(service);
    }

    @Transactional
    public AdditionalServiceResponseDto changeActive(Long userId, Long siteId, Long serviceId, Boolean active) {
        ownedSite(userId, siteId);
        if (active == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "활성 상태는 필수입니다.");
        }
        AdditionalService service = findService(siteId, serviceId);
        service.changeActive(active);
        return AdditionalServiceResponseDto.from(service);
    }

    private Site ownedSite(Long userId, Long siteId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (user.getRole() != Role.MANAGER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site를 찾을 수 없습니다."));
        AccommodationItem item = site.getAccommodationItem();
        if (item == null || item.getAccommodation() == null || item.getAccommodation().getOwner() == null
                || !userId.equals(item.getAccommodation().getOwner().getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return site;
    }

    private AdditionalService findService(Long siteId, Long serviceId) {
        return additionalServiceRepository.findByIdAndSiteId(serviceId, siteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "추가 서비스를 찾을 수 없습니다."));
    }

    private void validateRequired(AdditionalServiceRequestDto dto) {
        if (dto == null || dto.price() == null || dto.active() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "가격과 활성 상태는 필수입니다.");
        }
    }
}
