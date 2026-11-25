package com.ticketsystem.api.mail;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for {@code outbox_message}. Outbox rows are installation-wide system data (not client-owned
 * data): only the mail module reads them, and no endpoint exposes them.
 */
@Repository
class OutboxRepository {

  private static final String COLUMNS =
      "id, message_id, kind, ticket_id, to_addresses, cc_addresses, from_name, reply_to, subject,"
          + " html_body, text_body, extra_headers, sensitive, expires_at, attempts";

  private final JdbcClient jdbc;

  OutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  long insert(OutboundEmail email, String messageId, String fromName, String subject) {
    long id = insertRow(email, messageId, fromName, subject);
    int position = 0;
    for (MailPart part : email.parts()) {
      jdbc.sql(
              """
              INSERT INTO outbox_part (outbox_message_id, position, content_id, filename,
                  content_type, inline, content)
              VALUES (:id, :pos, :cid, :name, :type, :inline, :content)
              """)
          .param("id", id)
          .param("pos", position++)
          .param("cid", part.contentId())
          .param("name", part.filename())
          .param("type", part.contentType())
          .param("inline", part.inline())
          .param("content", part.content())
          .update();
    }
    return id;
  }

  private long insertRow(OutboundEmail email, String messageId, String fromName, String subject) {
    return jdbc.sql(
            """
            INSERT INTO outbox_message (message_id, kind, ticket_id, to_addresses, cc_addresses,
                from_name, reply_to, subject, html_body, text_body, extra_headers, sensitive,
                expires_at)
            VALUES (:messageId, :kind, :ticketId, :to, :cc, :fromName, :replyTo, :subject,
                :html, :text, :headers, :sensitive, :expiresAt)
            RETURNING id
            """)
        .param("messageId", messageId)
        .param("kind", email.kind().dbValue())
        .param("ticketId", email.ticketId())
        .param("to", String.join(",", email.to()))
        .param("cc", email.cc().isEmpty() ? null : String.join(",", email.cc()))
        .param("fromName", fromName)
        .param("replyTo", email.replyTo())
        .param("subject", subject)
        .param("html", email.htmlBody())
        .param("text", email.textBody())
        .param("headers", encodeHeaders(email.extraHeaders()))
        .param("sensitive", email.sensitive())
        .param(
            "expiresAt",
            email.expiresAt() == null
                ? null
                : OffsetDateTime.ofInstant(email.expiresAt(), ZoneOffset.UTC))
        .query(Long.class)
        .single();
  }

  /**
   * Claims up to {@code limit} due rows in one statement: marks them 'sending' and counts the
   * attempt. {@code FOR UPDATE SKIP LOCKED} means a second worker (thread or instance) running the
   * same statement at the same moment skips these rows instead of sending them again.
   */
  List<OutboxMessage> claimDue(int limit) {
    return claimDue(limit, List.of(MailKind.values()));
  }

  /**
   * As {@link #claimDue(int)}, only rows of these kinds (the sender split: ticket mail vs account
   * mail).
   */
  List<OutboxMessage> claimDue(int limit, List<MailKind> kinds) {
    return jdbc
        .sql(
            """
            UPDATE outbox_message
               SET status = 'sending', attempts = attempts + 1, claimed_at = now()
             WHERE id IN (SELECT id FROM outbox_message
                           WHERE status = 'queued' AND next_attempt_at <= now()
                             AND kind IN (:kinds)
                           ORDER BY next_attempt_at, id
                           LIMIT :limit
                           FOR UPDATE SKIP LOCKED)
            RETURNING %s
            """
                .formatted(COLUMNS))
        .param("limit", limit)
        .param("kinds", kinds.stream().map(MailKind::dbValue).toList())
        .query(OutboxRepository::map)
        .list()
        .stream()
        .map(m -> m.withParts(parts(m.id())))
        .toList();
  }

  private List<MailPart> parts(long messageId) {
    return jdbc.sql(
            "SELECT content_id, filename, content_type, inline, content FROM outbox_part"
                + " WHERE outbox_message_id = :id ORDER BY position")
        .param("id", messageId)
        .query(
            (rs, n) ->
                new MailPart(
                    rs.getString("content_id"),
                    rs.getString("filename"),
                    rs.getString("content_type"),
                    rs.getBoolean("inline"),
                    rs.getBytes("content")))
        .list();
  }

  /**
   * The ticket and kind of the outbox messages among {@code messageIds} (inbound threading), keyed
   * by Message-ID. Only messages that belong to a ticket are returned.
   */
  Map<String, ThreadRow> threadRows(List<String> messageIds) {
    Map<String, ThreadRow> out = new LinkedHashMap<>();
    if (messageIds.isEmpty()) {
      return out;
    }
    jdbc.sql(
            "SELECT message_id, ticket_id, kind FROM outbox_message WHERE message_id IN (:ids)"
                + " AND ticket_id IS NOT NULL")
        .param("ids", messageIds)
        .query(
            rs -> {
              out.put(
                  rs.getString("message_id"),
                  new ThreadRow(rs.getLong("ticket_id"), MailKind.fromDb(rs.getString("kind"))));
            });
    return out;
  }

  /** Message-IDs of the client-facing mail already sent or queued for a ticket, newest first. */
  List<String> clientThreadMessageIds(long ticketId, int limit) {
    return jdbc.sql(
            "SELECT message_id FROM outbox_message WHERE ticket_id = :t AND kind ="
                + " 'client_facing' ORDER BY id DESC LIMIT :limit")
        .param("t", ticketId)
        .param("limit", limit)
        .query(String.class)
        .list();
  }

  record ThreadRow(long ticketId, MailKind kind) {}

  /** Sent: sensitive bodies are blanked in the same statement. */
  void markSent(long id) {
    jdbc.sql(
            """
            UPDATE outbox_message
               SET status = 'sent', sent_at = now(), claimed_at = NULL, last_error = NULL,
                   html_body = CASE WHEN sensitive THEN '' ELSE html_body END,
                   text_body = CASE WHEN sensitive THEN '' ELSE text_body END
             WHERE id = :id AND status = 'sending'
            """)
        .param("id", id)
        .update();
  }

  void markRetry(long id, Duration delay, String error) {
    jdbc.sql(
            """
            UPDATE outbox_message
               SET status = 'queued', claimed_at = NULL, last_error = :error,
                   next_attempt_at = now() + make_interval(secs => CAST(:secs AS double precision))
             WHERE id = :id AND status = 'sending'
            """)
        .param("id", id)
        .param("secs", delay.toMillis() / 1000.0)
        .param("error", truncate(error))
        .update();
  }

  /** Terminal failure: sensitive bodies are blanked in the same statement. */
  void markFailed(long id, String error) {
    jdbc.sql(
            """
            UPDATE outbox_message
               SET status = 'failed', claimed_at = NULL, last_error = :error,
                   html_body = CASE WHEN sensitive THEN '' ELSE html_body END,
                   text_body = CASE WHEN sensitive THEN '' ELSE text_body END
             WHERE id = :id AND status = 'sending'
            """)
        .param("id", id)
        .param("error", truncate(error))
        .update();
  }

  /**
   * Rows left in 'sending' longer than {@code staleAfter} were interrupted (crash or restart
   * mid-send). They go back to the queue, or to failed once out of attempts. Delivery is therefore
   * at-least-once: if the server accepted the mail just before the crash it is sent again.
   */
  int recoverStale(Duration staleAfter, int maxAttempts) {
    return jdbc.sql(
            """
            UPDATE outbox_message
               SET status = CASE WHEN attempts >= :max THEN 'failed' ELSE 'queued' END,
                   html_body = CASE WHEN attempts >= :max AND sensitive THEN '' ELSE html_body END,
                   text_body = CASE WHEN attempts >= :max AND sensitive THEN '' ELSE text_body END,
                   claimed_at = NULL, next_attempt_at = now(),
                   last_error = 'interrupted while sending'
             WHERE status = 'sending' AND claimed_at < now() - make_interval(secs => CAST(:secs AS double precision))
            """)
        .param("max", maxAttempts)
        .param("secs", staleAfter.toMillis() / 1000.0)
        .update();
  }

  private static OutboxMessage map(ResultSet rs, int row) throws SQLException {
    OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
    return new OutboxMessage(
        rs.getLong("id"),
        rs.getString("message_id"),
        MailKind.fromDb(rs.getString("kind")),
        rs.getObject("ticket_id", Long.class),
        splitAddresses(rs.getString("to_addresses")),
        splitAddresses(rs.getString("cc_addresses")),
        rs.getString("from_name"),
        rs.getString("reply_to"),
        rs.getString("subject"),
        rs.getString("html_body"),
        rs.getString("text_body"),
        decodeHeaders(rs.getString("extra_headers")),
        rs.getBoolean("sensitive"),
        expires == null ? null : expires.toInstant(),
        rs.getInt("attempts"),
        List.of());
  }

  static String encodeHeaders(Map<String, String> headers) {
    StringBuilder sb = new StringBuilder();
    headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
    return sb.toString();
  }

  static Map<String, String> decodeHeaders(String encoded) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (encoded == null || encoded.isEmpty()) {
      return headers;
    }
    for (String line : encoded.split("\n")) {
      int colon = line.indexOf(": ");
      if (colon > 0) {
        headers.put(line.substring(0, colon), line.substring(colon + 2));
      }
    }
    return headers;
  }

  private static List<String> splitAddresses(String joined) {
    if (joined == null || joined.isEmpty()) {
      return List.of();
    }
    return Arrays.stream(joined.split(",")).map(String::strip).toList();
  }

  static String truncate(String error) {
    if (error == null) {
      return null;
    }
    return error.length() <= 500 ? error : error.substring(0, 500);
  }
}
