package com.stayon.stayon_backend.handler;

import com.stayon.stayon_backend.Exception.InvalidLoginException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidLoginException.class)
    public ResponseEntity<String> handleInvalidLogin(
            InvalidLoginException e
    ) {
        return ResponseEntity
                .badRequest()
                .body(e.getMessage());
    }
}