package com.ticketsystem.api.ticket.attachment;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.ticket.attachment.AttachmentProcessor.Processed;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.AttachmentRow;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.NewAttachment;
import com.ticketsystem.api.ticket.clientdata.AttachmentRepository.Visibility;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Attachments: upload as a pending file owned by the uploader, bind to a comment when it is saved
 * (it then takes that comment's visibility), and serve downloads and thumbnails after checking
 * attachment -> comment -> ticket -> organisation through the caller's {@link AccessScope} on every
 * request.
 */
@Service
public class AttachmentService {

  /** A pending upload expires after this unless a comment binds it. */
  public static final Duration PENDING_LIFETIME = Duration.ofHours(24);

  /** Most attachments on one comment. */
  public static final int MAX_PER_COMMENT = 20;

  private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

  /** What the uploader gets back. */
  public record Uploaded(
      UUID id, String filename, long size, String contentType, boolean image, Instant expiresAt) {}

  /** A file to stream to the client. */
  public record Download(
      String filename, String contentType, boolean image, long size, InputStream content) {}

  private final AttachmentRepository attachments;
  private final AttachmentProcessor processor;
  private final BlobStore blobs;
  private final Clock clock;

  public AttachmentService(
      AttachmentRepository attachments,
      AttachmentProcessor processor,
      BlobStore blobs,
      Clock clock) {
    this.attachments = attachments;
    this.processor = processor;
    this.blobs = blobs;
    this.clock = clock;
  }

  /**
   * Checks, cleans (images re-encoded) and stores an upload as pending, owned by the scope's user.
   */
  @Transactional
  public Uploaded upload(AccessScope scope, String originalFilename, byte[] bytes) {
    if (scope.kind() != AccessScope.Kind.CUSTOMER && !scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    // Browsers send the file's own name (RFC 7578); a name saved from a link can still carry
    // %20 etc., decoded once here - the only place an upload's name is decoded.
    Processed p =
        processor.process(AttachmentProcessor.decodePercentEncodedName(originalFilename), bytes);
    UUID key = UUID.randomUUID();
    UUID thumbKey = p.thumbnail() == null ? null : UUID.randomUUID();
    Instant now = clock.instant();
    Instant expires = now.plus(PENDING_LIFETIME);
    try {
      blobs.put(key, p.bytes());
      if (thumbKey != null) {
        blobs.put(thumbKey, p.thumbnail());
      }
      UUID publicId = UUID.randomUUID();
      attachments.insertPending(
          scope,
          new NewAttachment(
              publicId, key, thumbKey, p.filename(), p.bytes().length, p.contentType(), p.image()),
          now,
          expires);
      return new Uploaded(
          publicId, p.filename(), p.bytes().length, p.contentType(), p.image(), expires);
    } catch (IOException | RuntimeException e) {
      deleteQuietly(key);
      deleteQuietly(thumbKey);
      if (e instanceof RuntimeException r) {
        throw r;
      }
      throw new UncheckedIOException((IOException) e);
    }
  }

  /**
   * A file taken from an inbound email: checked and cleaned exactly like an upload (20 MB, type
   * from the bytes, SVG refused, images re-encoded without EXIF) and stored as a pending file, so
   * the email's comment binds it like any upload. The SYSTEM scope (the poller) stores it without
   * an uploader; a supporter's scope (their emailed reply) owns it. The files are deleted again if
   * the surrounding transaction rolls back. Throws the same {@link ApiException}s as {@link
   * #upload} when the file is refused.
   *
   * <p>Deliberately not {@code @Transactional}: a refused file is an expected outcome the caller
   * catches, and an exception crossing a transactional proxy would mark the mail's whole
   * transaction rollback-only. It must still run inside the caller's transaction (checked).
   */
  public Uploaded storeFromEmail(AccessScope scope, String originalFilename, byte[] bytes) {
    if (!scope.isSystem() && !scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("storeFromEmail needs the caller's transaction");
    }
    Processed p = processor.process(originalFilename, bytes, AttachmentProcessor.EMAIL_MAX_PIXELS);
    UUID key = UUID.randomUUID();
    UUID thumbKey = p.thumbnail() == null ? null : UUID.randomUUID();
    Instant now = clock.instant();
    Instant expires = now.plus(PENDING_LIFETIME);
    try {
      blobs.put(key, p.bytes());
      if (thumbKey != null) {
        blobs.put(thumbKey, p.thumbnail());
      }
    } catch (IOException e) {
      deleteQuietly(key);
      deleteQuietly(thumbKey);
      throw new UncheckedIOException(e);
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              deleteQuietly(key);
              deleteQuietly(thumbKey);
            }
          }
        });
    UUID publicId = UUID.randomUUID();
    attachments.insertPending(
        scope,
        new NewAttachment(
            publicId, key, thumbKey, p.filename(), p.bytes().length, p.contentType(), p.image()),
        now,
        expires);
    return new Uploaded(
        publicId, p.filename(), p.bytes().length, p.contentType(), p.image(), expires);
  }

  /**
   * Locks the scope's own unexpired pending uploads among {@code ids} for binding to a comment
   * being saved in the caller's transaction. 400 when any id is unknown, someone else's, already
   * used or expired (one message for all, so ids cannot be probed).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<AttachmentRow> claimPending(AccessScope scope, Collection<UUID> ids) {
    Set<UUID> unique = new LinkedHashSet<>(ids == null ? List.of() : ids);
    if (unique.size() > MAX_PER_COMMENT) {
      throw ApiException.invalidRequest("At most " + MAX_PER_COMMENT + " files per message.");
    }
    if (unique.isEmpty()) {
      return List.of();
    }
    List<AttachmentRow> rows = attachments.lockOwnPending(scope, unique, clock.instant());
    if (rows.size() != unique.size()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "attachment_unavailable",
          "One of the files is no longer available. Please upload it again.");
    }
    return rows;
  }

  /** Binds claimed uploads to a public comment; {@code inline} = referenced as an image. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void bindToPublicComment(
      AccessScope scope,
      Long organisationId,
      long ticketId,
      long commentId,
      List<AttachmentRow> claimed,
      Set<UUID> inline) {
    for (AttachmentRow a : claimed) {
      attachments.bindToPublicComment(
          scope, organisationId, a.id(), ticketId, commentId, inline.contains(a.publicId()));
    }
  }

  /** Binds claimed uploads to an internal note (supporters and system only). */
  @Transactional(propagation = Propagation.MANDATORY)
  public void bindToInternalNote(
      AccessScope scope,
      Long organisationId,
      long ticketId,
      long noteId,
      List<AttachmentRow> claimed,
      Set<UUID> inline) {
    for (AttachmentRow a : claimed) {
      attachments.bindToInternalNote(
          scope, organisationId, a.id(), ticketId, noteId, inline.contains(a.publicId()));
    }
  }

  /**
   * The file, if the scope may see it: the uploader's own pending upload, or a bound attachment
   * whose comment's ticket is in an organisation the scope sees; internal-note attachments only for
   * supporters. 404 otherwise, exactly as for an id that does not exist.
   */
  @Transactional(readOnly = true)
  public Download download(AccessScope scope, UUID publicId) {
    AttachmentRow a = find(scope, publicId);
    return new Download(
        a.filename(), a.contentType(), a.image(), a.sizeBytes(), open(a.storageKey()));
  }

  /** The thumbnail of an image attachment, with the same checks as {@link #download}. */
  @Transactional(readOnly = true)
  public Download thumbnail(AccessScope scope, UUID publicId) {
    AttachmentRow a = find(scope, publicId);
    if (!a.image() || a.thumbnailKey() == null) {
      throw AccessScope.notFound();
    }
    return new Download(
        "thumbnail-" + a.filename(), a.contentType(), true, -1, open(a.thumbnailKey()));
  }

  /**
   * Bound attachments of a ticket. {@code includeInternal} true is for the agent view (and still
   * returns internal-note files to supporter and system scopes only); false for every public read
   * model.
   */
  @Transactional(readOnly = true)
  public List<AttachmentRow> listForTicket(
      AccessScope scope, long ticketId, boolean includeInternal) {
    return attachments.listForTicket(scope, ticketId, includeInternal);
  }

  /** Deletes expired pending uploads and their files (called by the cleanup job). */
  @Transactional
  public int deleteExpiredPending(AccessScope scope, int limit) {
    Instant now = clock.instant();
    int deleted = 0;
    for (AttachmentRow a : attachments.expiredPending(scope, now, limit)) {
      if (attachments.deleteExpiredPending(scope, a.id(), now)) {
        deleteQuietly(a.storageKey());
        deleteQuietly(a.thumbnailKey());
        deleted++;
      }
    }
    return deleted;
  }

  private AttachmentRow find(AccessScope scope, UUID publicId) {
    AttachmentRow a =
        attachments.findForDownload(scope, publicId).orElseThrow(AccessScope::notFound);
    // Defence in depth behind the SQL: a customer or API key never gets an internal file.
    if (a.visibility() == Visibility.INTERNAL && !scope.isSupporter() && !scope.isSystem()) {
      throw AccessScope.notFound();
    }
    return a;
  }

  private InputStream open(UUID key) {
    try {
      return blobs.open(key);
    } catch (IOException e) {
      log.warn("attachment file missing or unreadable (key {})", key);
      throw AccessScope.notFound();
    }
  }

  private void deleteQuietly(UUID key) {
    if (key == null) {
      return;
    }
    try {
      blobs.delete(key);
    } catch (IOException e) {
      log.warn("could not delete attachment file (key {})", key);
    }
  }
}
