package com.ticketsystem.api.mail;

import org.springframework.stereotype.Component;

/**
 * Test connection for a mailbox's outgoing settings: connects to the SMTP server, starts TLS as
 * configured, signs in (AUTH) and says goodbye (QUIT). No message is sent. The outcome is a short
 * category built from an SMTP code or an exception type only ({@link SendFailure}), never the
 * server's reply text, which can echo the user name.
 */
@Component
public class SmtpConnectionCheck {

  /** {@code error} is null on success; {@code signInFailed} is true when AUTH was refused. */
  public record Result(String error, boolean signInFailed) {
    public boolean ok() {
      return error == null;
    }
  }

  private final MailSettings settings;

  SmtpConnectionCheck(MailSettings settings) {
    this.settings = settings;
  }

  public Result check(SmtpAccount account) {
    try {
      new SmtpMailTransport(account, settings).testConnection();
      return new Result(null, false);
    } catch (Exception e) {
      SendFailure f = SendFailure.classify(e);
      return new Result(f.summary(), f.summary().equals(SendFailure.AUTH_FAILED));
    }
  }
}
