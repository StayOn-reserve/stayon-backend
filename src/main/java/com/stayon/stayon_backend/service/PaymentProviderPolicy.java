package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.PaymentProviderProperties;
import com.stayon.stayon_backend.entity.ServiceOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class PaymentProviderPolicy {
    private final PaymentProviderProperties properties;
    private final PgAdapters adapters;
    /** Local checks only, inside the owned reservation/order transaction. Existing results remain recoverable. */
    public void requireNewPayment(String provider, ServiceOrder order) {
        var rule = properties.getRules().get(provider);
        if (rule == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment provider");
        if (!rule.isEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider is disabled");
        adapters.require(provider);
        var site = order.getReservation().getSite();
        if (rule.getMinimumAmount() < 0 || rule.getMaximumAmount() < rule.getMinimumAmount()
                || rule.getCurrencies() == null || rule.getSiteIds() == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invalid payment provider settings");
        if (site == null || (!rule.isAllowAllSites() && !rule.getSiteIds().contains(site.getId()))
                || !rule.getCurrencies().contains("KRW") || order.getTotalAmount() < rule.getMinimumAmount()
                || order.getTotalAmount() > rule.getMaximumAmount())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment provider is unavailable for this order");
    }
}
