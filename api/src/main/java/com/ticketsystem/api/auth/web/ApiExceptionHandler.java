package com.ticketsystem.api.auth.web;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.throttle.ThrottledException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** JSON error bodies {@code {"error": code, "message": text}} for the API. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestControllerAdvice
public class ApiExceptionHandler {

  public record ApiError(String error, String message) {}

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage()));
  }

  @ExceptionHandler(ThrottledException.class)
  ResponseEntity<ApiError> throttled(ThrottledException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
        .body(
            new ApiError(
                "too_many_requests",
                "Too many attempts. Please wait "
                    + e.retryAfterSeconds()
                    + " seconds and try again."));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<ApiError> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(new ApiError("invalid_request", "Some of the details are missing or not valid."));
  }
}
