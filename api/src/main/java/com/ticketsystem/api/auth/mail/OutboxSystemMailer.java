package com.ticketsystem.api.auth.mail;

import com.ticketsystem.api.auth.AuthProperties;
import com.ticketsystem.api.mail.SystemEmail;
import com.ticketsystem.api.mail.SystemMailService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link SystemMailer} on the mail outbox: renders the system template and queues it in the
 * caller's transaction. Token-bearing mails are marked sensitive with the token's expiry, so the
 * outbox never sends them late and blanks their bodies once sent.
 */
@Component
public class OutboxSystemMailer implements SystemMailer {

  /** "4 December 2026, 09:30 UTC": plain and unambiguous for a reader in any timezone. */
  static final DateTimeFormatter NOTICE_TIME =
      DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm 'UTC'", Locale.UK).withZone(ZoneOffset.UTC);

  private final SystemMailService mail;
  private final AuthProperties props;

  public OutboxSystemMailer(SystemMailService mail, AuthProperties props) {
    this.mail = mail;
    this.props = props;
  }

  @Override
  public void supporterInvitation(String to, String name, String link, Instant expiresAt) {
    mail.send(
        SystemEmail.SUPPORTER_INVITATION,
        to,
        linkVars(name, link, AuthProperties.describe(props.supporterInvitationValidity())),
        expiresAt);
  }

  @Override
  public void customerInvitation(String to, String name, String link, Instant expiresAt) {
    // A neutral phrase stands in for the organisation name.
    mail.send(
        SystemEmail.CUSTOMER_INVITATION,
        to,
        Map.of(
            "recipientName",
            name,
            "organisationName",
            "the support portal",
            "link",
            link,
            "validFor",
            AuthProperties.describe(props.customerInvitationValidity())),
        expiresAt);
  }

  @Override
  public void passwordReset(String to, String name, String link, Instant expiresAt) {
    mail.send(
        SystemEmail.PASSWORD_RESET,
        to,
        linkVars(name, link, AuthProperties.describe(props.passwordResetValidity())),
        expiresAt);
  }

  @Override
  public void customerMagicLink(String to, String name, String link, Instant expiresAt) {
    mail.send(
        SystemEmail.CUSTOMER_MAGIC_LINK,
        to,
        linkVars(name, link, AuthProperties.describe(props.magicLinkValidity())),
        expiresAt);
  }

  @Override
  public void firstAdminSetup(String to, String name, String signInLink) {
    mail.send(
        SystemEmail.FIRST_ADMIN_SETUP,
        to,
        Map.of("recipientName", name, "signInLink", signInLink),
        null);
  }

  @Override
  public void securityNotice(String to, String name, SecurityNotice notice) {
    SystemEmail email =
        switch (notice) {
          case PASSWORD_CHANGED -> SystemEmail.PASSWORD_CHANGED;
          case MFA_ENROLLED -> SystemEmail.MFA_ENROLLED;
          case MFA_RESET -> SystemEmail.MFA_RESET;
          case ROLE_CHANGED -> SystemEmail.ROLE_CHANGED;
          case ACCOUNT_DEACTIVATED -> SystemEmail.ACCOUNT_DEACTIVATED;
        };
    mail.send(
        email, to, Map.of("recipientName", name, "link", props.page("/forgot-password")), null);
  }

  @Override
  public void newDeviceSignIn(String to, String name, String device) {
    mail.send(
        SystemEmail.NEW_DEVICE_SIGN_IN,
        to,
        Map.of("recipientName", name, "device", device, "link", props.page("/forgot-password")),
        null);
  }

  @Override
  public void mfaExemptionGranted(String to, String name, Instant endsAt) {
    mail.send(
        SystemEmail.MFA_EXEMPTION_GRANTED,
        to,
        Map.of(
            "recipientName",
            name,
            "endsAt",
            NOTICE_TIME.format(endsAt),
            "link",
            props.page("/forgot-password")),
        null);
  }

  private static Map<String, String> linkVars(String name, String link, String validFor) {
    return Map.of("recipientName", name, "link", link, "validFor", validFor);
  }
}
