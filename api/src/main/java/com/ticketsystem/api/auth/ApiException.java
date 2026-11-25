package com.ticketsystem.api.auth;

import org.springframework.http.HttpStatus;

/**
 * A client error with a stable machine code and a plain-English message for the UI. Messages never
 * echo secrets or say whether an account exists.
 */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message, null, false, false);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  /** The one answer for a wrong, expired, used or revoked link token. */
  public static ApiException invalidLink() {
    return new ApiException(
        HttpStatus.BAD_REQUEST,
        "invalid_link",
        "This link is not valid any more. It may have expired or already been used.");
  }

  /** The one answer for any failed sign-in (unknown email, wrong password, inactive account). */
  public static ApiException invalidCredentials() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "invalid_credentials", "The email or password is not right.");
  }

  public static ApiException weakPassword(PasswordPolicy.Violation violation) {
    return new ApiException(HttpStatus.BAD_REQUEST, "weak_password", violation.message());
  }

  public static ApiException invalidRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", message);
  }
}
