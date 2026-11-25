package com.ticketsystem.api.ticket.web;

import com.ticketsystem.api.auth.web.ApiExceptionHandler.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** JSON answers for uploads the multipart layer refuses (over the size cap, malformed). */
@RestControllerAdvice
public class UploadExceptionHandler {

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(new ApiError("file_too_large", "Files can be at most 20 MB each."));
  }

  @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
  ResponseEntity<ApiError> malformed(Exception e) {
    return ResponseEntity.badRequest().body(new ApiError("invalid_request", "Choose a file."));
  }
}
