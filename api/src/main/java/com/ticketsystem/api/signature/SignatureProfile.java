package com.ticketsystem.api.signature;

/**
 * A supporter's signature fields plus their own email address, already validated by {@link
 * ProfileFields}. Empty strings mean "not filled".
 */
public record SignatureProfile(
    String displayName,
    String jobTitle,
    String phone,
    String mobile,
    String freeLine,
    String email) {

  @Override
  public String toString() {
    // Personal data stays out of logs.
    return "SignatureProfile[...]";
  }
}
