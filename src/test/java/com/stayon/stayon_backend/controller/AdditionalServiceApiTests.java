package com.stayon.stayon_backend.controller;

import com.stayon.stayon_backend.config.JwtProperties;
import com.stayon.stayon_backend.dto.additionalservice.AdditionalServiceRequestDto;
import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.repository.AdditionalServiceRepository;
import com.stayon.stayon_backend.security.JwtTokenProvider;
import com.stayon.stayon_backend.service.CustomerJwtTestProperties;
import com.stayon.stayon_backend.service.AdditionalServiceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static com.stayon.stayon_backend.entity.AdditionalService.ServiceInputType.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@CustomerJwtTestProperties
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdditionalServiceApiTests {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired JwtTokenProvider tokens;
    @Autowired JwtProperties jwtProperties;
    @Autowired AdditionalServiceRepository repository;
    @Autowired AdditionalServiceService service;

    private User owner;
    private Site site;
    private AdditionalService existing;
    private String base;
    private String authorization;

    @BeforeEach
    void setUp() {
        owner = user(Role.MANAGER);
        var accommodation = Accommodation.builder().name("캠핑장").platform(Platform.CAMFIT).owner(owner).build();
        em.persist(accommodation);
        var item = AccommodationItem.builder().itemId(UUID.randomUUID().toString()).itemName("구역")
                .accommodation(accommodation).build();
        em.persist(item);
        site = new Site("A1");
        site.assignAccommodationItem(item);
        em.persist(site);
        existing = new AdditionalService(site, "숯불", 20000, QUANTITY, true, null, null, null);
        em.persist(existing);
        em.flush();
        base = "/sites/" + site.getId() + "/additional-services";
        authorization = bearer(owner, "MANAGER");
    }

    @Test
    void createAndReadPersistedService() throws Exception {
        mvc.perform(post(base).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"불멍","price":0,"inputType":"DELIVERY_TIME","active":true,
                                 "openTime":"17:00:00","closeTime":"22:00:00","intervalMinutes":30}
                                """))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("불멍")).andExpect(jsonPath("$.siteId").value(site.getId()))
                .andExpect(jsonPath("$.price").value(0));
        em.flush();
        em.clear();
        mvc.perform(get(base).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(base + "/" + existing.getId()).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("숯불"));
    }

    @Test
    void updatePersistsAllFields() throws Exception {
        mvc.perform(put(base + "/" + existing.getId()).header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"불멍 변경","price":35000,"inputType":"DELIVERY_TIME","active":false,
                                 "openTime":"18:00:00","closeTime":"23:00:00","intervalMinutes":15}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(35000))
                .andExpect(jsonPath("$.inputType").value("DELIVERY_TIME"))
                .andExpect(jsonPath("$.active").value(false));
        em.flush();
        em.clear();
        var saved = repository.findById(existing.getId()).orElseThrow();
        assertEquals("불멍 변경", saved.getName());
        assertEquals(LocalTime.of(18, 0), saved.getOpenTime());
        assertEquals(LocalTime.of(23, 0), saved.getCloseTime());
        assertEquals(15, saved.getIntervalMinutes());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"x\",\"price\":-1,\"inputType\":\"QUANTITY\",\"active\":true}",
            "{\"name\":\"x\",\"inputType\":\"QUANTITY\",\"active\":true}",
            "{\"name\":\"x\",\"price\":0,\"inputType\":\"QUANTITY\"}",
            "{\"price\":0,\"inputType\":\"QUANTITY\",\"active\":true}",
            "{\"name\":\"  \",\"price\":0,\"inputType\":\"QUANTITY\",\"active\":true}",
            "{\"name\":\"x\",\"price\":0,\"active\":true}",
            "{\"name\":\"x\",\"price\":0,\"inputType\":\"UNKNOWN\",\"active\":true}",
            "{\"name\":\"x\",\"price\":0,\"inputType\":\"DELIVERY_TIME\",\"active\":true}",
            "{\"name\":\"x\",\"price\":0,\"inputType\":\"DELIVERY_TIME\",\"active\":true,\"openTime\":\"20:00:00\",\"closeTime\":\"18:00:00\",\"intervalMinutes\":30}",
            "{\"name\":\"x\",\"price\":0,\"inputType\":\"DELIVERY_TIME\",\"active\":true,\"openTime\":\"18:00:00\",\"closeTime\":\"20:00:00\",\"intervalMinutes\":0}",
            "{\"name\":\"x\",\"price\":0,\"inputType\":\"QUANTITY\",\"active\":true,\"openTime\":\"18:00:00\"}"
    })
    void invalidCreateAndUpdateAreRejected(String body) throws Exception {
        mvc.perform(post(base).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(put(base + "/" + existing.getId()).header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertEquals(20000, existing.getPrice());
        assertEquals("숯불", existing.getName());
        assertEquals(1, repository.findBySiteIdOrderByIdAsc(site.getId()).size());
    }

    @Test
    void nonexistentSiteAndServiceAreRejected() throws Exception {
        mvc.perform(post("/sites/9223372036854775807/additional-services").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isNotFound());
        mvc.perform(get(base + "/9223372036854775807").header("Authorization", authorization))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherManagersAccessIsDeniedForEveryOperationEvenWithSpoofedUserId() throws Exception {
        String other = bearer(user(Role.MANAGER), "MANAGER");
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                get(base), get(base + "/" + existing.getId()), post(base).content(validBody()),
                put(base + "/" + existing.getId()).content(validBody()),
                patch(base + "/" + existing.getId() + "/active").content("{\"active\":false}"),
                delete(base + "/" + existing.getId())}) {
            mvc.perform(request.header("Authorization", other).param("userId", owner.getUserId().toString())
                            .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden());
        }
        assertTrue(existing.isActive());
        assertEquals(20000, existing.getPrice());
    }

    @Test
    void serviceCannotBeAccessedThroughAnotherOwnedSite() throws Exception {
        var otherSite = new Site("A2");
        otherSite.assignAccommodationItem(site.getAccommodationItem());
        em.persist(otherSite);
        mvc.perform(get("/sites/" + otherSite.getId() + "/additional-services/" + existing.getId())
                        .header("Authorization", authorization)).andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedRequestsAreRejectedForEveryOperation() throws Exception {
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                get(base), get(base + "/" + existing.getId()), post(base).content(validBody()),
                put(base + "/" + existing.getId()).content(validBody()),
                patch(base + "/" + existing.getId() + "/active").content("{\"active\":false}"),
                delete(base + "/" + existing.getId())}) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void invalidExpiredAndRefreshTokensCannotAccessApi() throws Exception {
        mvc.perform(get(base).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        var expiredProperties = new JwtProperties();
        expiredProperties.setSecret(jwtProperties.getSecret());
        expiredProperties.setAccessTokenExpiration(-60000);
        var expired = new JwtTokenProvider(expiredProperties).createAccessToken(owner.getUserId(), "MANAGER");
        mvc.perform(get(base).header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
        mvc.perform(get(base).header("Authorization", "Bearer " + tokens.createRefreshToken(owner.getUserId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenRoleAndCurrentDatabaseRoleAreBothRequired() throws Exception {
        mvc.perform(get(base).header("Authorization", bearer(owner, "STAFF"))).andExpect(status().isForbidden());
        mvc.perform(get(base).header("Authorization", bearer(user(Role.STAFF), "MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(base).header("Authorization", "Bearer " + tokens.createAccessToken(Long.MAX_VALUE, "MANAGER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void incompleteOwnershipChainIsDenied() throws Exception {
        var unassigned = new Site("미배정");
        em.persist(unassigned);
        mvc.perform(get("/sites/" + unassigned.getId() + "/additional-services").header("Authorization", authorization))
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivateAndReactivateWithoutPhysicalDeletion() throws Exception {
        mvc.perform(delete(base + "/" + existing.getId()).header("Authorization", authorization))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();
        var inactive = repository.findById(existing.getId()).orElseThrow();
        assertFalse(inactive.isActive());
        assertThrows(IllegalStateException.class, inactive::requireOrderable);
        mvc.perform(get(base).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].active").value(false));
        mvc.perform(patch(base + "/" + existing.getId() + "/active").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        em.flush();
        em.clear();
        assertDoesNotThrow(repository.findById(existing.getId()).orElseThrow()::requireOrderable);
    }

    @Test
    void patchRequiresActiveAndSupportsFalse() throws Exception {
        mvc.perform(patch(base + "/" + existing.getId() + "/active").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(base + "/" + existing.getId() + "/active").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void publicEmailCheckRemainsAccessibleWithoutToken() throws Exception {
        mvc.perform(post("/check-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unregistered@example.test\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void existingOrderSnapshotsRemainUnchangedAfterRenameRepriceAndDeactivate() throws Exception {
        var reservation = new Reservation();
        reservation.setPlatform(Platform.CAMFIT);
        reservation.setEventType(ReservationEventType.RESERVED);
        reservation.setReservationId(UUID.randomUUID().toString());
        reservation.setCheckInDate(LocalDate.of(2026, 12, 1));
        reservation.setCheckOutDate(LocalDate.of(2026, 12, 2));
        reservation.setNights(1);
        reservation.setSite(site);
        em.persist(reservation);
        var order = instantiate(ServiceOrder.class);
        ReflectionTestUtils.setField(order, "reservation", reservation);
        ReflectionTestUtils.setField(order, "status", ServiceOrder.ServiceOrderStatus.PAID);
        ReflectionTestUtils.setField(order, "totalAmount", 40000);
        em.persist(order);
        var orderItem = instantiate(ServiceOrderItem.class);
        ReflectionTestUtils.setField(orderItem, "serviceOrder", order);
        ReflectionTestUtils.setField(orderItem, "additionalService", existing);
        ReflectionTestUtils.setField(orderItem, "quantity", 2);
        ReflectionTestUtils.setField(orderItem, "unitPrice", 20000);
        ReflectionTestUtils.setField(orderItem, "totalPrice", 40000);
        ReflectionTestUtils.setField(orderItem, "fulfillmentStatus", ServiceOrderItem.FulfillmentStatus.NOT_APPLICABLE);
        em.persist(orderItem);
        em.flush();
        service.update(owner.getUserId(), site.getId(), existing.getId(),
                new AdditionalServiceRequestDto("새 이름", 50000, QUANTITY, true, null, null, null));
        service.changeActive(owner.getUserId(), site.getId(), existing.getId(), false);
        em.flush();
        em.clear();
        var saved = em.find(ServiceOrderItem.class, orderItem.getId());
        assertEquals(20000, saved.getUnitPrice());
        assertEquals(40000, saved.getTotalPrice());
        assertEquals(2, saved.getQuantity());
        assertEquals(40000, saved.getServiceOrder().getTotalAmount());
        assertEquals(ServiceOrder.ServiceOrderStatus.PAID, saved.getServiceOrder().getStatus());
        assertEquals("새 이름", saved.getAdditionalService().getName());
        // 상품명 스냅샷이 없는 과거 주문은 현재 상품명을 과거 주문명으로 사용하면 안 된다.
        assertNull(saved.getServiceName());
    }

    private User user(Role role) {
        var user = User.builder().email(UUID.randomUUID() + "@example.test").name("관리자")
                .password("test-only").role(role).build();
        em.persist(user);
        return user;
    }

    private String bearer(User user, String role) {
        return "Bearer " + tokens.createAccessToken(user.getUserId(), role);
    }

    private String validBody() {
        return "{\"name\":\"숯불\",\"price\":20000,\"inputType\":\"QUANTITY\",\"active\":true}";
    }

    private <T> T instantiate(Class<T> type) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
