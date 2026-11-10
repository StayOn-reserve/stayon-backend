package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.KakaoPayProperties;
import com.stayon.stayon_backend.dto.customer.CustomerPaymentReadyResponse;
import com.stayon.stayon_backend.entity.PaymentAttempt;
import com.stayon.stayon_backend.security.CustomerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.Duration;
import java.security.SecureRandom;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class KakaoPayReadyService {
    private final PaymentTransactions transactions;private final PgSecrets secrets;private final ObjectProvider<KakaoPayAdapter> adapters;
    private final KakaoPayProperties settings;private final KakaoPayUrls urls;private final Clock clock;
    public CustomerPaymentReadyResponse ready(Authentication auth,String id){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Ready requires no outer DB transaction");
        var initial=transactions.inspect(auth,id);
        if(!"KAKAOPAY".equals(initial.provider()))throw new ResponseStatusException(HttpStatus.CONFLICT);
        if(initial.preparation().status()!=PaymentAttempt.PreparationStatus.NONE)return response(auth,initial);
        var adapter=adapters.getIfAvailable();if(adapter==null)throw unavailable();adapter.validate(initial.command(false));
        if(settings.getAuthenticationLifetime()==null||settings.getAuthenticationLifetime().compareTo(Duration.ofSeconds(1))<0||settings.getAuthenticationLifetime().compareTo(Duration.ofMinutes(15))>0
                ||settings.getTaxMode()==null||settings.getTaxMode()==KakaoPayProperties.TaxMode.UNCONFIGURED)throw unavailable();
        byte[] entropy=new byte[32];new SecureRandom().nextBytes(entropy);String state=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        String approval=urls.callback(settings.getApprovalUrl(),id,state),cancel=urls.callback(settings.getCancelUrl(),id,state),fail=urls.callback(settings.getFailUrl(),id,state);
        try(var secret=secrets.resolve(initial.merchant())){
            long taxFree=settings.getTaxMode()==KakaoPayProperties.TaxMode.ALL_TAX_FREE?initial.amount():0;
            var claim=transactions.claimPreparation(auth,id,PaymentAttempt.digest(state),clock.instant().plus(settings.getAuthenticationLifetime()),taxFree);
            if(!claim.claimed())return response(auth,claim.view());
            KakaoPayAdapter.ReadyResult result;
            try{result=adapter.ready(claim.view().command(false),approval,cancel,fail,secret);}
            catch(RuntimeException failure){return response(auth,transactions.preparationUnknown(id,claim.view().preparation().claim()));}
            try{return response(auth,transactions.applyPreparation(id,claim.view().preparation().claim(),result.tid(),result.pcUrl(),result.mobileUrl()));}
            catch(RuntimeException failure){
                // Retry only the same known DB result once. Never retry the remote Ready request.
                if(failure instanceof ResponseStatusException unavailable&&unavailable.getStatusCode().value()==503){
                    try{return response(auth,transactions.applyPreparation(id,claim.view().preparation().claim(),result.tid(),result.pcUrl(),result.mobileUrl()));}
                    catch(RuntimeException retryFailure){failure=retryFailure;}
                }
                try{transactions.preparationUnknown(id,claim.view().preparation().claim());}catch(RuntimeException ignored){/* Durable REQUESTING claim prevents another Ready RPC. */}
                throw failure;
            }
        }
    }
    private CustomerPaymentReadyResponse response(Authentication auth,PaymentTransactions.View view){
        var p=view.preparation();
        if(view.status()!=PaymentAttempt.Status.READY||!PaymentAttempt.digest(((CustomerPrincipal)auth.getPrincipal()).sessionId()).equals(p.sessionHash())
                ||p.expiresAt()==null||!p.expiresAt().isAfter(clock.instant()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Preparation context expired or unavailable");
        return new CustomerPaymentReadyResponse(view.id(),p.status(),p.status()==PaymentAttempt.PreparationStatus.PREPARED?p.pcUrl():null,p.status()==PaymentAttempt.PreparationStatus.PREPARED?p.mobileUrl():null,p.expiresAt());
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"KakaoPay preparation is not configured");}
}
