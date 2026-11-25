package com.ticketsystem.api.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

/**
 * Why a send failed, reduced to what is safe to store and log: a permanent/temporary verdict, the
 * SMTP reply code if there was one, and a short summary built only from that code or an exception
 * type. Server reply text and exception messages are never kept (they can echo addresses or
 * credentials).
 *
 * <p>Rules: SMTP 5xx and invalid addresses or messages are permanent (no retry); SMTP 4xx,
 * connection and timeout errors are temporary. Authentication failures are treated as temporary:
 * they are a settings problem, not a problem with this email, so queued mail goes out once the
 * settings are fixed (within the retry window).
 */
record SendFailure(boolean permanent, Integer smtpCode, String summary) {

  static final String AUTH_FAILED = "SMTP authentication failed";
  static final String CONNECTION_PREFIX = "connection or transport error";

  /**
   * A failure of the sending server or account (sign-in, connection), not of this one email: only
   * these are shown as the mailbox's error.
   */
  boolean mailboxLevel() {
    return summary.equals(AUTH_FAILED) || summary.startsWith(CONNECTION_PREFIX);
  }

  static SendFailure classify(Throwable error) {
    Integer code = null;
    boolean badInput = false;
    boolean auth = false;
    String rootType = error.getClass().getSimpleName();

    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Throwable> todo = new ArrayDeque<>();
    todo.add(error);
    while (!todo.isEmpty()) {
      Throwable t = todo.poll();
      if (t == null || !seen.add(t)) {
        continue;
      }
      rootType = t.getClass().getSimpleName();
      if (code == null) {
        code = smtpCode(t);
      }
      if (t instanceof AddressException
          || t instanceof MailParseException
          || t instanceof MailPreparationException) {
        badInput = true;
      }
      if (t instanceof MailAuthenticationException || t instanceof AuthenticationFailedException) {
        auth = true;
      }
      if (t instanceof MailSendException mse) {
        mse.getFailedMessages().values().forEach(e -> push(todo, e));
        for (Exception e : mse.getMessageExceptions()) {
          push(todo, e);
        }
      }
      if (t instanceof MessagingException me) {
        push(todo, me.getNextException());
      }
      push(todo, t.getCause());
    }

    if (auth) {
      return new SendFailure(false, code, AUTH_FAILED);
    }
    if (code != null && code >= 500 && code < 600) {
      return new SendFailure(true, code, "SMTP " + code + " (permanent)");
    }
    if (code != null && code >= 400 && code < 500) {
      return new SendFailure(false, code, "SMTP " + code + " (temporary)");
    }
    if (badInput) {
      return new SendFailure(true, code, "invalid address or message");
    }
    return new SendFailure(false, code, CONNECTION_PREFIX + " (" + rootType + ")");
  }

  /** ArrayDeque refuses nulls. */
  private static void push(Deque<Throwable> todo, Throwable t) {
    if (t != null) {
      todo.add(t);
    }
  }

  private static Integer smtpCode(Throwable t) {
    int code = -1;
    if (t instanceof SMTPAddressFailedException e) {
      code = e.getReturnCode();
    } else if (t instanceof SMTPSenderFailedException e) {
      code = e.getReturnCode();
    } else if (t instanceof SMTPSendFailedException e) {
      code = e.getReturnCode();
    }
    return code >= 200 && code < 600 ? code : null;
  }
}
