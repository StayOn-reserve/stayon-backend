package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.KakaoPayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.net.URI;

@Component
@RequiredArgsConstructor
public class KakaoPayUrls {
    private final KakaoPayProperties settings;
    public String callback(String configured,String id,String state){
        try{
            URI uri=URI.create(configured);validHttps(uri);
            String origin="https://"+uri.getHost();
            if(!settings.getCallbackOrigins().contains(origin)||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException();
            String result=configured+"?attempt="+id+"&state="+state;if(result.length()>255)throw new IllegalArgumentException();return result;
        }catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Invalid payment callback configuration");}
    }
    public String payment(String value){
        try{var uri=URI.create(value);validHttps(uri);if(uri.getRawFragment()!=null||!settings.getRedirectHosts().contains(uri.getHost())||value.length()>2048)throw new IllegalArgumentException();return value;}
        catch(RuntimeException e){throw new IllegalArgumentException("Invalid payment redirect");}
    }
    private void validHttps(URI uri){
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IllegalArgumentException();
    }
}
