package com.ticketsystem.api.mail.inbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tells whether a message was sent to the agent-only address {@code support+internal@<mailbox>}
 * (replies to agent notifications, which become internal notes) or the normal support address (spec
 * section 6, supporter replies by email). It reads the delivery headers the receiving server adds
 * ({@code Delivered-To}, {@code X-Original-To}, {@code Envelope-To}) and then To and Cc.
 *
 * <p>This is routing only, not trust: anyone can write to {@code +internal}. The intake still
 * requires the sender to be an active supporter with access to the ticket, otherwise the mail goes
 * to triage.
 */
public final class PlusAddress {

  /** The plus tag of the agent-only address. */
  public static final String INTERNAL_TAG = "internal";

  private static final String[] DELIVERY_HEADERS = {
    "Delivered-To", "X-Original-To", "Envelope-To", "X-Delivered-To"
  };

  private final String local;
  private final String domain;

  /**
   * @param supportAddress the mailbox address, e.g. {@code support@acmemsp.example}
   */
  public PlusAddress(String supportAddress) {
    String a = supportAddress.strip().toLowerCase(Locale.ROOT);
    int at = a.lastIndexOf('@');
    if (at <= 0 || at == a.length() - 1 || a.indexOf('+') >= 0) {
      throw new IllegalArgumentException("support address must be a plain local@domain address");
    }
    this.local = a.substring(0, at);
    this.domain = a.substring(at + 1);
  }

  public MailboxTarget detect(InboundMessage message) {
    List<InboundAddress> candidates = new ArrayList<>();
    for (String header : DELIVERY_HEADERS) {
      for (String value : message.header(header)) {
        candidates.addAll(InboundAddress.parseHeader(value));
      }
    }
    candidates.addAll(message.to());
    candidates.addAll(message.cc());

    MailboxTarget result = MailboxTarget.NOT_ADDRESSED;
    for (InboundAddress a : candidates) {
      if (!a.domain().equals(domain)) {
        continue;
      }
      String l = a.localPart();
      int plus = l.indexOf('+');
      String base = plus < 0 ? l : l.substring(0, plus);
      if (!base.equals(local)) {
        continue;
      }
      if (plus >= 0 && l.substring(plus + 1).equals(INTERNAL_TAG)) {
        return MailboxTarget.INTERNAL;
      }
      result = MailboxTarget.SUPPORT;
    }
    return result;
  }

  /** Which support address the message was sent to. */
  public enum MailboxTarget {
    /** {@code support+internal@}: a reply to an agent-only notification. */
    INTERNAL,
    /** {@code support@}, or {@code support+<other tag>@}. */
    SUPPORT,
    /** Neither address seen in the headers (Bcc or a forwarding alias): treat as support. */
    NOT_ADDRESSED
  }
}
