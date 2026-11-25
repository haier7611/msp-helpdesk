package com.ticketsystem.api.ticket.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code saved_reply}: shared replies (no owner) and each supporter's personal ones. A supporter
 * sees the shared ones plus their own; another supporter's personal reply looks exactly like a
 * missing one. Supporters only.
 */
@Repository
public class SavedReplyRepository {

  public record SavedReplyRow(long id, Long ownerUserId, String title, String bodyHtml) {}

  private final JdbcClient jdbc;

  public SavedReplyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Shared replies plus the caller's personal ones, by title. */
  public List<SavedReplyRow> visible(AccessScope scope) {
    long me = me(scope);
    return jdbc.sql(
            "SELECT id, owner_user_id, title, body_html FROM saved_reply WHERE owner_user_id IS"
                + " NULL OR owner_user_id = :me ORDER BY lower(title), id")
        .param("me", me)
        .query(
            (rs, n) ->
                new SavedReplyRow(
                    rs.getLong("id"),
                    rs.getObject("owner_user_id", Long.class),
                    rs.getString("title"),
                    rs.getString("body_html")))
        .list();
  }

  /** One shared reply or one of the caller's own; empty otherwise. */
  public Optional<SavedReplyRow> findVisible(AccessScope scope, long id) {
    long me = me(scope);
    return jdbc.sql(
            "SELECT id, owner_user_id, title, body_html FROM saved_reply WHERE id = :id AND"
                + " (owner_user_id IS NULL OR owner_user_id = :me)")
        .param("id", id)
        .param("me", me)
        .query(
            (rs, n) ->
                new SavedReplyRow(
                    rs.getLong("id"),
                    rs.getObject("owner_user_id", Long.class),
                    rs.getString("title"),
                    rs.getString("body_html")))
        .optional();
  }

  /** A personal reply of the caller; returns its id. */
  public long insertPersonal(AccessScope scope, String title, String bodyHtml, Instant now) {
    long me = me(scope);
    return jdbc.sql(
            "INSERT INTO saved_reply (owner_user_id, title, body_html, created_at, updated_at)"
                + " VALUES (:me, :title, :body, :now, :now) RETURNING id")
        .param("me", me)
        .param("title", title)
        .param("body", bodyHtml)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  /** Updates one of the caller's personal replies; returns rows changed (0 = not theirs). */
  public int updatePersonal(
      AccessScope scope, long id, String title, String bodyHtml, Instant now) {
    long me = me(scope);
    return jdbc.sql(
            "UPDATE saved_reply SET title = :title, body_html = :body, updated_at = :now WHERE"
                + " id = :id AND owner_user_id = :me")
        .param("title", title)
        .param("body", bodyHtml)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .param("me", me)
        .update();
  }

  /** Deletes one of the caller's personal replies; returns rows removed (0 = not theirs). */
  public int deletePersonal(AccessScope scope, long id) {
    long me = me(scope);
    return jdbc.sql("DELETE FROM saved_reply WHERE id = :id AND owner_user_id = :me")
        .param("id", id)
        .param("me", me)
        .update();
  }

  /** Shared replies only, by title (Admin - Saved replies). */
  public List<SavedReplyRow> shared(AccessScope scope) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT id, owner_user_id, title, body_html FROM saved_reply WHERE owner_user_id IS"
                + " NULL ORDER BY lower(title), id")
        .query(
            (rs, n) ->
                new SavedReplyRow(
                    rs.getLong("id"), null, rs.getString("title"), rs.getString("body_html")))
        .list();
  }

  /** A shared reply (no owner, seen by every supporter); returns its id. Admins only. */
  public long insertShared(AccessScope scope, String title, String bodyHtml, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "INSERT INTO saved_reply (owner_user_id, title, body_html, created_at, updated_at)"
                + " VALUES (NULL, :title, :body, :now, :now) RETURNING id")
        .param("title", title)
        .param("body", bodyHtml)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  /** Updates a shared reply; returns rows changed (0 = no such shared reply). Admins only. */
  public int updateShared(AccessScope scope, long id, String title, String bodyHtml, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "UPDATE saved_reply SET title = :title, body_html = :body, updated_at = :now WHERE"
                + " id = :id AND owner_user_id IS NULL")
        .param("title", title)
        .param("body", bodyHtml)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** Deletes a shared reply (never a personal one); returns rows removed. Admins only. */
  public int deleteShared(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql("DELETE FROM saved_reply WHERE id = :id AND owner_user_id IS NULL")
        .param("id", id)
        .update();
  }

  private static long me(AccessScope scope) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    return scope.principalId().orElseThrow(AccessScope::notFound);
  }
}
