package com.ticketsystem.api.mail.ticketmail;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.MailSettings;
import com.ticketsystem.api.mail.inbound.PlusAddress;
import com.ticketsystem.api.mail.intake.clientdata.MailboxRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The support mailbox addresses: the first configured mailbox (enabled first) is the support
 * address for {@code Reply-To}; until an admin configures one, the bootstrap sender {@code
 * MAIL_FROM_ADDRESS}. Agent-only mail uses its plus address {@code support+internal@}.
 */
@Component
public class SupportAddresses {

  private final MailboxRepository mailboxes;
  private final MailSettings settings;

  public SupportAddresses(MailboxRepository mailboxes, MailSettings settings) {
    this.mailboxes = mailboxes;
    this.settings = settings;
  }

  /** The support address, if any is configured. */
  public Optional<String> support(AccessScope scope) {
    List<String> all = all(scope);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
  }

  /** {@code support+internal@<domain>} for agent-only notifications. */
  public Optional<String> internal(AccessScope scope) {
    return support(scope).map(SupportAddresses::internalOf);
  }

  /**
   * {@code support@x} -> {@code support+internal@x}; null when the address has no {@code @}. An
   * address that is already plus-tagged (the environment address may be, e.g. {@code
   * support+tickets@x}) gives the {@code +internal} address of its base mailbox, {@code
   * support+internal@x}, so agent-only mail always keeps an internal Reply-To.
   */
  static String internalOf(String address) {
    String a = address.strip().toLowerCase(Locale.ROOT);
    int at = a.lastIndexOf('@');
    if (at <= 0 || at == a.length() - 1) {
      return null;
    }
    String local = a.substring(0, at);
    int plus = local.indexOf('+');
    String base = plus < 0 ? local : local.substring(0, plus);
    if (base.isEmpty()) {
      return null;
    }
    return base + "+" + PlusAddress.INTERNAL_TAG + a.substring(at);
  }

  /** Every support address (mailboxes, then the bootstrap sender), lower case. */
  public List<String> all(AccessScope scope) {
    List<String> out = new ArrayList<>(mailboxes.addresses(scope));
    if (settings.fromAddress() != null) {
      String from = settings.fromAddress().toLowerCase(Locale.ROOT);
      if (!out.contains(from)) {
        out.add(from);
      }
    }
    return out;
  }

  /**
   * Is {@code address} one of ours, including plus addresses such as {@code support+internal@}? Our
   * own addresses never become participants and never get client mail.
   */
  public boolean isOurs(AccessScope scope, String address) {
    String a = address.toLowerCase(Locale.ROOT);
    int at = a.lastIndexOf('@');
    if (at <= 0) {
      return false;
    }
    String local = a.substring(0, at);
    int plus = local.indexOf('+');
    String base = (plus < 0 ? local : local.substring(0, plus)) + a.substring(at);
    return all(scope).contains(base);
  }
}
