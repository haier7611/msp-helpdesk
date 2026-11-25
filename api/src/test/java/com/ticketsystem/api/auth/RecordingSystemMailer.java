package com.ticketsystem.api.auth;

import com.ticketsystem.api.auth.mail.SecurityNotice;
import com.ticketsystem.api.auth.mail.SystemMailer;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test {@link SystemMailer}: records every call so tests can follow the link, and (when given one)
 * passes it on to the real outbox mailer so the outbox row is written too.
 */
public class RecordingSystemMailer implements SystemMailer {

  public record Sent(String type, String to, String link, Instant expiresAt) {
    /** The raw token from a {@code ...#token=} link. */
    public String token() {
      return link.substring(link.indexOf("#token=") + "#token=".length());
    }
  }

  private final SystemMailer delegate;
  private final List<Sent> sent = new CopyOnWriteArrayList<>();

  public RecordingSystemMailer(SystemMailer delegate) {
    this.delegate = delegate;
  }

  public List<Sent> sent() {
    return List.copyOf(sent);
  }

  public List<Sent> sentTo(String to) {
    return sent.stream().filter(s -> s.to().equalsIgnoreCase(to)).toList();
  }

  public Sent last(String to, String type) {
    List<Sent> list = sentTo(to).stream().filter(s -> s.type().equals(type)).toList();
    if (list.isEmpty()) {
      throw new AssertionError("no " + type + " mail to " + to);
    }
    return list.get(list.size() - 1);
  }

  public void clear() {
    sent.clear();
  }

  @Override
  public void supporterInvitation(String to, String name, String link, Instant expiresAt) {
    sent.add(new Sent("supporter-invitation", to, link, expiresAt));
    if (delegate != null) {
      delegate.supporterInvitation(to, name, link, expiresAt);
    }
  }

  @Override
  public void customerInvitation(String to, String name, String link, Instant expiresAt) {
    sent.add(new Sent("customer-invitation", to, link, expiresAt));
    if (delegate != null) {
      delegate.customerInvitation(to, name, link, expiresAt);
    }
  }

  @Override
  public void passwordReset(String to, String name, String link, Instant expiresAt) {
    sent.add(new Sent("password-reset", to, link, expiresAt));
    if (delegate != null) {
      delegate.passwordReset(to, name, link, expiresAt);
    }
  }

  @Override
  public void customerMagicLink(String to, String name, String link, Instant expiresAt) {
    sent.add(new Sent("magic-link", to, link, expiresAt));
    if (delegate != null) {
      delegate.customerMagicLink(to, name, link, expiresAt);
    }
  }

  @Override
  public void firstAdminSetup(String to, String name, String signInLink) {
    sent.add(new Sent("first-admin-setup", to, signInLink, null));
    if (delegate != null) {
      delegate.firstAdminSetup(to, name, signInLink);
    }
  }

  @Override
  public void securityNotice(String to, String name, SecurityNotice notice) {
    sent.add(new Sent("notice-" + notice.name(), to, null, null));
    if (delegate != null) {
      delegate.securityNotice(to, name, notice);
    }
  }

  @Override
  public void newDeviceSignIn(String to, String name, String device) {
    sent.add(new Sent("notice-NEW_DEVICE", to, device, null));
    if (delegate != null) {
      delegate.newDeviceSignIn(to, name, device);
    }
  }

  @Override
  public void mfaExemptionGranted(String to, String name, Instant endsAt) {
    sent.add(new Sent("notice-MFA_EXEMPTION_GRANTED", to, null, endsAt));
    if (delegate != null) {
      delegate.mfaExemptionGranted(to, name, endsAt);
    }
  }
}
