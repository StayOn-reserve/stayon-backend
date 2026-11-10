package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.PgMerchantProperties;
import com.stayon.stayon_backend.repository.PgMerchantRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PgSecrets {
    private final PgMerchantRepository merchants;private final PgMerchantProperties settings;private final PgSecretResolver resolver;private final TransactionTemplate read;
    public PgSecrets(PgMerchantRepository merchants,PgMerchantProperties settings,ObjectProvider<PgSecretResolver> resolvers,PgRuntimeGuard guard,PlatformTransactionManager manager){
        this.merchants=merchants;this.settings=settings;resolver=resolvers.getIfAvailable();
        if(resolver!=null)guard.verify("SECRET_RESOLVER",resolver.testOnly());
        read=new TransactionTemplate(manager);read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);read.setReadOnly(true);read.setTimeout(10);
    }
    public PgMerchantContext context(String id){
        try{return read.execute(status->merchants.findById(id).map(PgMerchantContext::from).orElseThrow(PgSecrets::unavailable));}
        catch(org.springframework.dao.DataAccessException|TransactionException unavailable){throw unavailable();}
    }
    public PgSecret resolve(PgMerchantContext requested){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Secrets must resolve outside DB transactions");
        if(requested==null||requested.environment()!=settings.getEnvironment()||!requested.equals(context(requested.id()))||resolver==null)throw unavailable();
        try{var result=resolver.resolve(requested);if(result==null)throw unavailable();return result;}
        catch(RuntimeException failure){throw unavailable();}
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Merchant credentials are unavailable");}
}
