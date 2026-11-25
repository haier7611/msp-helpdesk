package com.ticketsystem.api.mail;

/**
 * Delivers one outbox message. The SMTP implementation is {@link SmtpMailTransport}; the
 * admin-configured mailbox (password or OAuth2) behind the same interface.
 */
interface MailTransport {

  /** Sends the message or throws; the worker classifies the exception with {@link SendFailure}. */
  void send(OutboxMessage message) throws Exception;
}
