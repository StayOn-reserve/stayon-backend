package com.stayon.stayon_backend.handler;

import com.stayon.stayon_backend.controller.CustomerPaymentController;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Also covers failures opening/committing the read transaction, outside the service method body. */
@RestControllerAdvice(assignableTypes = CustomerPaymentController.class)
public class CustomerPaymentExceptionHandler {
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<Void> databaseUnavailable(RuntimeException exception) {
        return ResponseEntity.status(503).cacheControl(org.springframework.http.CacheControl.noStore()).build();
    }
}
