package com.ticketsystem.api.mail;

/**
 * An admin-configured outbound SMTP account: the enabled mailbox with SMTP settings. {@code secret}
 * is the decrypted password, or an OAuth2 access token when {@code xoauth2}; it is held only for
 * one outbox round and never logged.
 */
public record SmtpAccount(
    long mailboxId,
    String fromAddress,
    String host,
    int port,
    MailSettings.Security security,
    String username,
    String secret,
    boolean xoauth2) {

  /** Never prints the secret. */
  @Override
  public String toString() {
    return "SmtpAccount[mailbox=" + mailboxId + ", host=" + host + ", port=" + port + "]";
  }
}
