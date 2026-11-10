package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.*;
import com.stayon.stayon_backend.entity.PgMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="payment.kakaopay.enabled",havingValue="true")
public class KakaoPayAdapter implements PgAdapter {
    private final KakaoPayHttpClient http;private final KakaoPayProperties settings;private final PgMerchantProperties merchantSettings;private final KakaoPayUrls urls;
    @Override public String provider(){return "KAKAOPAY";}
    @Override public boolean supportsCancellation(){return true;}
    public record ReadyResult(String tid,String pcUrl,String mobileUrl){@Override public String toString(){return "ReadyResult[REDACTED]";}}
    public void validate(Command c){
        validateHistorical(c);
        if(!settings.isMockTransport()&&c.merchant().environment()==PgMerchant.Environment.LIVE&&!settings.isLiveEnabled())throw conflict();
    }
    private void validateHistorical(Command c){
        if(!provider().equals(c.provider())||c.merchant()==null||!provider().equals(c.merchant().provider())||c.merchant().environment()!=merchantSettings.getEnvironment()
                ||!"KRW".equals(c.currency())||c.amount()<1||c.amount()>Integer.MAX_VALUE||c.merchant().externalId().length()!=10)throw conflict();
        if(!settings.isMockTransport()){
            if(c.merchant().environment()==PgMerchant.Environment.SANDBOX&&!c.merchant().externalId().equals("TC0ONETIME"))throw conflict();
            if(c.merchant().environment()==PgMerchant.Environment.LIVE&&c.merchant().externalId().equals("TC0ONETIME"))throw conflict();
        }
    }
    public ReadyResult ready(Command c,String approvalUrl,String cancelUrl,String failUrl,PgSecret secret){
        validate(c);var p=Objects.requireNonNull(c.preparation());
        var body=new LinkedHashMap<String,Object>();body.put("cid",c.merchant().externalId());body.put("partner_order_id",c.reference());body.put("partner_user_id",p.userReference());
        body.put("item_name","StayOn additional services");body.put("quantity",1);body.put("total_amount",c.amount());body.put("tax_free_amount",p.taxFreeAmount());
        body.put("approval_url",approvalUrl);body.put("cancel_url",cancelUrl);body.put("fail_url",failUrl);
        var response=http.post("ready",body,secret);String tid=text(response,"tid");if(tid.length()!=20)throw conflict();
        return new ReadyResult(tid,urls.payment(text(response,"next_redirect_pc_url")),urls.payment(text(response,"next_redirect_mobile_url")));
    }
    @Override public void validateAuthentication(BrowserAuthentication proof){
        if(!"KAKAOPAY_V1".equals(proof.version())||!proof.payload().keySet().equals(Set.of("state","pg_token"))||!proof.payload().get("state").matches("[A-Za-z0-9_-]{43}")
                ||proof.payload().get("pg_token").isBlank()||proof.payload().get("pg_token").chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid KakaoPay browser proof");
    }
    @Override public ApprovalResult approve(Command c,BrowserAuthentication proof,PgSecret secret){
        validate(c);validateAuthentication(proof);requirePrepared(c);
        var body=identity(c);body.put("partner_order_id",c.reference());body.put("partner_user_id",c.preparation().userReference());body.put("pg_token",proof.payload().get("pg_token"));body.put("total_amount",c.amount());
        var response=http.post("approve",body,secret);validateResponse(c,response);
        if(text(response,"approved_at").isBlank())throw conflict();
        return new ApprovalResult(c.reference(),provider(),ApprovalOutcome.APPROVED,c.preparation().tid(),c.amount(),"KRW",c.merchant().externalId());
    }
    @Override public ApprovalResult lookupApproval(Command c,PgSecret secret){
        validateHistorical(c);requirePrepared(c);var response=http.post("order",identity(c),secret);validateResponse(c,response);
        var outcome="SUCCESS_PAYMENT".equals(text(response,"status"))&&!text(response,"approved_at").isBlank()&&integer(response.path("canceled_amount"),"total")==0?ApprovalOutcome.APPROVED:ApprovalOutcome.UNKNOWN;
        return new ApprovalResult(c.reference(),provider(),outcome,c.preparation().tid(),c.amount(),"KRW",c.merchant().externalId());
    }
    @Override public CancelResult lookupCancellation(Command c,PgSecret secret){
        validateCancellation(c);var response=http.post("order",identity(c),secret);validateResponse(c,response);
        return cancellationResult(c,fullyCanceled(c,response));
    }
    /** Only the durable, approved TID may be voided; never uses browser input. */
    private void validateCancellation(Command c){
        validateHistorical(c);requirePrepared(c);
        if(!c.preparation().tid().equals(c.transactionId())||c.preparation().taxFreeAmount()<0||c.preparation().taxFreeAmount()>c.amount())throw conflict();
    }
    private boolean fullyCanceled(Command c,JsonNode response){
        return "CANCEL_PAYMENT".equals(text(response,"status"))
                &&integer(response.path("canceled_amount"),"total")==c.amount()
                &&integer(response.path("canceled_amount"),"tax_free")==c.preparation().taxFreeAmount()
                &&integer(response.path("cancel_available_amount"),"total")==0
                &&integer(response.path("cancel_available_amount"),"tax_free")==0
                &&!text(response,"approved_at").isBlank()&&!text(response,"canceled_at").isBlank();
    }
    private CancelResult cancellationResult(Command c,boolean canceled){
        return new CancelResult(c.reference(),provider(),canceled?CancelOutcome.CANCELED:CancelOutcome.UNKNOWN,c.transactionId(),c.amount(),"KRW",c.merchant().externalId());
    }
    private Map<String,Object> identity(Command c){var result=new LinkedHashMap<String,Object>();result.put("cid",c.merchant().externalId());result.put("tid",c.preparation().tid());return result;}
    private void requirePrepared(Command c){if(c.preparation()==null||c.preparation().tid()==null||c.preparation().tid().length()!=20||c.preparation().userReference()==null)throw conflict();}
    private void validateResponse(Command c,JsonNode response){
        if(!c.merchant().externalId().equals(text(response,"cid"))||!c.preparation().tid().equals(text(response,"tid"))||!c.reference().equals(text(response,"partner_order_id"))
                ||!c.preparation().userReference().equals(text(response,"partner_user_id"))||integer(response.path("amount"),"total")!=c.amount()
                ||integer(response.path("amount"),"tax_free")!=c.preparation().taxFreeAmount())throw conflict();
    }
    private String text(JsonNode node,String field){var value=node.get(field);if(value==null||!value.isString()||value.asString().isBlank())throw conflict();return value.asString();}
    private long integer(JsonNode node,String field){var value=node.get(field);if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw conflict();return value.longValue();}
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"KakaoPay response or merchant mismatch");}
    @Override public ApprovalResult approve(Command c){throw new UnsupportedOperationException("Browser authentication required");}
    @Override public CancelResult cancel(Command c){throw new UnsupportedOperationException("Merchant credentials required");}
    @Override public CancelResult cancel(Command c,PgSecret secret){
        validateCancellation(c);
        var body=identity(c);body.put("cancel_amount",c.amount());body.put("cancel_tax_free_amount",c.preparation().taxFreeAmount());
        var response=http.post("cancel",body,secret);validateResponse(c,response);
        // AID identifies this action, not the original payment. Do not replace the pinned TID.
        text(response,"aid");
        boolean canceled=fullyCanceled(c,response)
                &&integer(response.path("approved_cancel_amount"),"total")==c.amount()
                &&integer(response.path("approved_cancel_amount"),"tax_free")==c.preparation().taxFreeAmount();
        return cancellationResult(c,canceled);
    }
}
