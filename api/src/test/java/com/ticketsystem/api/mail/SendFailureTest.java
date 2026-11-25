package com.ticketsystem.api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.eclipse.angus.mail.util.SocketConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

class SendFailureTest {

  private static final String SERVER_TEXT = "5.1.1 <sam@client.example> user unknown pw=hunter2";

  private static InternetAddress addr() throws AddressException {
    return new InternetAddress("sam@client.example");
  }

  /** How JavaMailSenderImpl reports a failed message: MailSendException with a per-message map. */
  private static MailSendException wrapped(Exception e) {
    return new MailSendException(Map.of(new Object(), e));
  }

  @Test
  void smtp5xxRecipientRejectionIsPermanent() throws Exception {
    var e = new SMTPAddressFailedException(addr(), "RCPT TO", 550, SERVER_TEXT);
    SendFailure f = SendFailure.classify(wrapped(new MessagingException("send failed", e)));

    assertThat(f.permanent()).isTrue();
    assertThat(f.smtpCode()).isEqualTo(550);
    assertThat(f.summary()).isEqualTo("SMTP 550 (permanent)");
  }

  @Test
  void smtp4xxIsTemporary() throws Exception {
    var e = new SMTPSendFailedException("DATA", 451, "4.3.0 try later", null, null, null, null);
    SendFailure f = SendFailure.classify(wrapped(e));

    assertThat(f.permanent()).isFalse();
    assertThat(f.smtpCode()).isEqualTo(451);
  }

  @Test
  void senderRejected5xxIsPermanent() throws Exception {
    var e = new SMTPSenderFailedException(addr(), "MAIL FROM", 553, "not allowed");
    assertThat(SendFailure.classify(wrapped(e)).permanent()).isTrue();
  }

  @Test
  void connectionRefusedIsTemporary() {
    var e =
        new MailConnectException(
            new SocketConnectException("refused", new ConnectException("refused"), "h", 25, 1000));
    SendFailure f = SendFailure.classify(new MailSendException("Mail server connection failed", e));

    assertThat(f.permanent()).isFalse();
    assertThat(f.smtpCode()).isNull();
    assertThat(f.summary()).startsWith("connection or transport error");
  }

  @Test
  void timeoutIsTemporary() {
    var e = new MessagingException("timeout", new SocketTimeoutException("Read timed out"));
    assertThat(SendFailure.classify(wrapped(e)).permanent()).isFalse();
  }

  @Test
  void invalidAddressIsPermanent() {
    var e = new AddressException("Illegal address", "x@@y");
    assertThat(SendFailure.classify(e).permanent()).isTrue();
  }

  @Test
  void authenticationFailureIsTemporarySoMailWaitsForFixedSettings() {
    var e =
        new MailAuthenticationException(new AuthenticationFailedException("535 bad pw=hunter2"));
    SendFailure f = SendFailure.classify(e);

    assertThat(f.permanent()).isFalse();
    assertThat(f.summary()).isEqualTo("SMTP authentication failed");
  }

  @Test
  void onlySignInAndConnectionFailuresAreTheMailboxsError() throws Exception {
    // AUD L3 (PR #19): a rejected address must not mark the sending mailbox as failing.
    var auth = new MailAuthenticationException(new AuthenticationFailedException("535"));
    assertThat(SendFailure.classify(auth).mailboxLevel()).isTrue();
    var conn =
        new MailConnectException(
            new SocketConnectException("refused", new ConnectException("refused"), "h", 25, 1000));
    assertThat(SendFailure.classify(new MailSendException("failed", conn)).mailboxLevel()).isTrue();
    var rcpt = new SMTPAddressFailedException(addr(), "RCPT TO", 550, SERVER_TEXT);
    assertThat(SendFailure.classify(wrapped(rcpt)).mailboxLevel()).isFalse();
    assertThat(SendFailure.classify(new AddressException("Illegal", "x@@y")).mailboxLevel())
        .isFalse();
  }

  @Test
  void summaryNeverContainsServerTextOrMessages() throws Exception {
    var e = new SMTPAddressFailedException(addr(), "RCPT TO", 550, SERVER_TEXT);
    String summary = SendFailure.classify(wrapped(e)).summary();
    assertThat(summary).doesNotContain("sam@").doesNotContain("hunter2").doesNotContain("unknown");

    var conn = new MessagingException("could not connect pw=hunter2");
    assertThat(SendFailure.classify(conn).summary()).doesNotContain("hunter2");
  }
}
