package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.PgMerchantProperties;
import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.repository.PgMerchantRepository;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PgMerchantRouting {
    private final EntityManager em;
    private final PgMerchantRepository merchants;
    private final PgMerchantProperties settings;
    /** Caller already holds reservation/order; route locks are acquired before the attempt row. */
    @Transactional(propagation=Propagation.MANDATORY)
    public PgMerchant select(ServiceOrder order,String provider){
        Long owner=lockOwner(order);
        var matches=merchants.findByOwner_UserIdAndProviderAndEnvironmentAndActiveTrue(owner,provider,settings.getEnvironment());
        if(matches.size()!=1)throw denied();
        var m=lock(PgMerchant.class,matches.getFirst().getId());
        if(!m.isActive()||!owner.equals(m.getOwner().getUserId())||!m.getCurrencies().contains("KRW"))throw denied();
        return m;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireApproval(ServiceOrder order,PaymentAttempt attempt){
        Long owner=lockOwner(order);
        if(attempt.getMerchant()==null)throw denied();
        var m=lock(PgMerchant.class,attempt.getMerchant().getId());
        if(!m.isActive()||!owner.equals(m.getOwner().getUserId())||m.getEnvironment()!=settings.getEnvironment()
                ||!m.getProvider().equals(attempt.getProvider())||!m.getCurrencies().contains(attempt.getCurrency()))throw denied();
    }
    private Long lockOwner(ServiceOrder order){
        var linked=order.getReservation().getSite();if(linked==null)throw denied();
        var site=lock(Site.class,linked.getId());
        if(site==null||site.getAccommodationItem()==null)throw denied();
        var item=lock(AccommodationItem.class,site.getAccommodationItem().getId());
        if(item==null||item.getAccommodation()==null)throw denied();
        var accommodation=lock(Accommodation.class,item.getAccommodation().getAccommodationId());
        if(accommodation==null||accommodation.getOwner()==null)throw denied();
        return accommodation.getOwner().getUserId();
    }
    private <T> T lock(Class<T> type,Object id){
        T row=em.find(type,id);if(row==null)throw denied();
        // A prior request stage may have populated the persistence context. Lock AND reload current ownership/configuration.
        em.refresh(row,LockModeType.PESSIMISTIC_READ);return row;
    }
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.CONFLICT,"Merchant routing is unavailable");}
}
