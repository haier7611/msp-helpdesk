package com.ticketsystem.api.ticket.agent;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.admin.AdminConfigAuditEvent;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.Person;
import com.ticketsystem.api.ticket.agent.AgentTicketViews.TicketDetail;
import com.ticketsystem.api.ticket.clientdata.SavedReplyRepository;
import com.ticketsystem.api.ticket.clientdata.SavedReplyRepository.SavedReplyRow;
import com.ticketsystem.api.ticket.clientdata.TicketPeopleRepository;
import com.ticketsystem.api.ticket.richtext.RichTextSanitiser;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/**
 * Saved replies: shared (admin-managed) and personal, inserted with "/" in the composer. Bodies are
 * sanitised on save with the comment allow list (no images). Rendering fills the placeholders from
 * the ticket with HTML-escaped values, so a name such as {@code <b>x</b>} can never become markup.
 */
@Service
public class SavedReplyService {

  public static final int MAX_TITLE = 120;
  public static final int MAX_BODY = 20_000;

  /** Placeholders filled by {@link #render}; anything else in braces is left as typed. */
  public static final List<String> PLACEHOLDERS =
      List.of("client_first_name", "client_name", "ticket_ref", "organisation_name", "agent_name");

  private static final Pattern PLACEHOLDER =
      Pattern.compile("(?:\\{|&#123;)([a-z_]+)(?:\\}|&#125;)");

  public record SavedReplyView(long id, String title, String bodyHtml, boolean shared) {}

  public record Rendered(String bodyHtml) {}

  private final SavedReplyRepository replies;
  private final AgentTicketQueryService queries;
  private final TicketPeopleRepository people;
  private final AuditService audit;
  private final Clock clock;

  public SavedReplyService(
      SavedReplyRepository replies,
      AgentTicketQueryService queries,
      TicketPeopleRepository people,
      AuditService audit,
      Clock clock) {
    this.replies = replies;
    this.queries = queries;
    this.people = people;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<SavedReplyView> list(AccessScope scope) {
    return replies.visible(scope).stream().map(SavedReplyService::view).toList();
  }

  @Transactional
  public SavedReplyView createPersonal(AccessScope scope, String title, String bodyHtml) {
    String t = title(title);
    String body = body(bodyHtml);
    long id = replies.insertPersonal(scope, t, body, clock.instant());
    return new SavedReplyView(id, t, body, false);
  }

  @Transactional
  public SavedReplyView updatePersonal(AccessScope scope, long id, String title, String bodyHtml) {
    String t = title(title);
    String body = body(bodyHtml);
    if (replies.updatePersonal(scope, id, t, body, clock.instant()) != 1) {
      throw AccessScope.notFound();
    }
    return new SavedReplyView(id, t, body, false);
  }

  @Transactional
  public void deletePersonal(AccessScope scope, long id) {
    if (replies.deletePersonal(scope, id) != 1) {
      throw AccessScope.notFound();
    }
  }

  // Shared replies (Admin - Saved replies) --------------------------------------------

  @Transactional(readOnly = true)
  public List<SavedReplyView> listShared(AccessScope scope) {
    scope.requireAdmin();
    return replies.shared(scope).stream().map(SavedReplyService::view).toList();
  }

  @Transactional
  public SavedReplyView createShared(
      AccessScope scope, String title, String bodyHtml, String clientIp) {
    scope.requireAdmin();
    String t = title(title);
    String body = body(bodyHtml);
    long id = replies.insertShared(scope, t, body, clock.instant());
    auditShared(scope, id, "created", clientIp);
    return new SavedReplyView(id, t, body, true);
  }

  @Transactional
  public SavedReplyView updateShared(
      AccessScope scope, long id, String title, String bodyHtml, String clientIp) {
    scope.requireAdmin();
    String t = title(title);
    String body = body(bodyHtml);
    if (replies.updateShared(scope, id, t, body, clock.instant()) != 1) {
      throw AccessScope.notFound();
    }
    auditShared(scope, id, "updated", clientIp);
    return new SavedReplyView(id, t, body, true);
  }

  @Transactional
  public void deleteShared(AccessScope scope, long id, String clientIp) {
    scope.requireAdmin();
    if (replies.deleteShared(scope, id) != 1) {
      throw AccessScope.notFound();
    }
    auditShared(scope, id, "deleted", clientIp);
  }

  private void auditShared(AccessScope scope, long id, String status, String clientIp) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("saved_reply", id);
    d.put("status", status);
    audit.record(
        AdminConfigAuditEvent.SHARED_SAVED_REPLY_CHANGED,
        scope.principalId().orElse(null),
        null,
        clientIp,
        d);
  }

  /** The reply with its placeholders filled for one ticket (404 for either not visible). */
  @Transactional(readOnly = true)
  public Rendered render(AccessScope scope, long ticketId, long replyId) {
    SavedReplyRow reply = replies.findVisible(scope, replyId).orElseThrow(AccessScope::notFound);
    TicketDetail ticket = queries.detail(scope, ticketId);
    Person reporter = ticket.reporter();
    String clientName = reporter == null || reporter.name() == null ? "" : reporter.name().strip();
    String firstName = clientName.isEmpty() ? "" : clientName.split("\\s+")[0];
    Long me = scope.principalId().orElse(null);
    String agentName = me == null ? "" : people.names(scope, Set.of(me)).getOrDefault(me, "");
    Map<String, String> values = new LinkedHashMap<>();
    values.put("client_first_name", firstName);
    values.put("client_name", clientName);
    values.put("ticket_ref", ticket.reference() == null ? "" : ticket.reference());
    values.put(
        "organisation_name", ticket.organisationName() == null ? "" : ticket.organisationName());
    values.put("agent_name", agentName);
    return new Rendered(fill(reply.bodyHtml(), values));
  }

  /** Replaces known placeholders with HTML-escaped values; leaves unknown ones as typed. */
  static String fill(String html, Map<String, String> values) {
    Matcher m = PLACEHOLDER.matcher(html);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = values.get(m.group(1));
      String replacement = value == null ? m.group() : HtmlUtils.htmlEscape(value);
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String title(String raw) {
    String t = raw == null ? "" : raw.strip();
    if (t.isEmpty() || t.length() > MAX_TITLE) {
      throw ApiException.invalidRequest("Enter a title of up to " + MAX_TITLE + " characters.");
    }
    return t;
  }

  private static String body(String raw) {
    if (raw != null && raw.length() > MAX_BODY) {
      throw ApiException.invalidRequest("A saved reply can be up to " + MAX_BODY + " characters.");
    }
    String body = RichTextSanitiser.sanitise(raw, Set.of());
    if (body.isBlank()) {
      throw ApiException.invalidRequest("Write the reply text.");
    }
    return body;
  }

  private static SavedReplyView view(SavedReplyRow r) {
    return new SavedReplyView(r.id(), r.title(), r.bodyHtml(), r.ownerUserId() == null);
  }
}
