package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.KakaoPayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.io.ByteArrayOutputStream;

@Component
@ConditionalOnProperty(name="payment.kakaopay.enabled",havingValue="true")
public class KakaoPayHttpClient {
    private final KakaoPayProperties settings;private final ObjectMapper json;private final HttpClient client;private final String apiOrigin;
    public KakaoPayHttpClient(KakaoPayProperties settings,ObjectMapper json,Environment environment){
        this.settings=settings;this.json=json;
        URI origin=URI.create(settings.getApiOrigin());
        if(settings.isMockTransport()){
            if(!Set.copyOf(Arrays.asList(environment.getActiveProfiles())).equals(Set.of("test"))||!"http".equals(origin.getScheme())||!"127.0.0.1".equals(origin.getHost())||origin.getPort()<1)throw new IllegalArgumentException("Mock transport requires isolated loopback test profile");
        }else if(!settings.getApiOrigin().equals("https://open-api.kakaopay.com"))throw new IllegalArgumentException("Only official KakaoPay origin is permitted");
        if(origin.getUserInfo()!=null||origin.getRawQuery()!=null||origin.getFragment()!=null||origin.getPath()!=null&&!origin.getPath().isEmpty())throw new IllegalArgumentException("Invalid API origin");
        apiOrigin=settings.getApiOrigin();
        if(settings.getRequestTimeout()==null||settings.getRequestTimeout().compareTo(Duration.ofMillis(10))<0||settings.getRequestTimeout().compareTo(Duration.ofSeconds(10))>0)throw new IllegalArgumentException("Invalid request deadline");
        var ssl=new javax.net.ssl.SSLParameters();ssl.setProtocols(new String[]{"TLSv1.2"});
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).sslParameters(ssl).version(HttpClient.Version.HTTP_1_1).build();
    }
    public JsonNode post(String operation,Map<String,Object> body,PgSecret secret){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("PG HTTP request held a DB transaction");
        char[] key=secret.copy();CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            if(!Set.of("ready","approve","order","cancel").contains(operation))throw new IllegalArgumentException();
            var request=HttpRequest.newBuilder(URI.create(apiOrigin+"/online/v1/payment/"+operation))
                    .timeout(settings.getRequestTimeout()).header("Authorization","SECRET_KEY "+new String(key)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            pending=client.sendAsync(request,ignored->new LimitedBody());
            var response=pending.get(settings.getRequestTimeout().toMillis(),TimeUnit.MILLISECONDS);
            if(response.statusCode()!=200)throw unavailable();
            var parsed=json.readTree(response.body());if(parsed==null||!parsed.isObject())throw unavailable();return parsed;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception e){throw unavailable();}
        finally{Arrays.fill(key,'\0');if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"KakaoPay result is unavailable");}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();private final ByteArrayOutputStream output=new ByteArrayOutputStream();private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return body;}
        @Override public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<ByteBuffer> items){
            for(var item:items){if(item.remaining()>65536-output.size()){subscription.cancel();body.completeExceptionally(new IllegalStateException("Response exceeds limit"));return;}byte[] bytes=new byte[item.remaining()];item.get(bytes);output.writeBytes(bytes);}subscription.request(1);
        }
        @Override public void onError(Throwable error){body.completeExceptionally(new IllegalStateException("Response unavailable"));}
        @Override public void onComplete(){body.complete(output.toByteArray());}
    }
}
