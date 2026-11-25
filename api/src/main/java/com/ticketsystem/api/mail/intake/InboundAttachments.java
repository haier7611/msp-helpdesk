package com.ticketsystem.api.mail.intake;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.mail.inbound.HtmlToSafeHtml;
import com.ticketsystem.api.mail.inbound.InboundAnalysis;
import com.ticketsystem.api.mail.inbound.InboundAttachment;
import com.ticketsystem.api.ticket.attachment.AttachmentProcessor;
import com.ticketsystem.api.ticket.attachment.AttachmentService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns the attachments of an inbound email into ticket attachments through the attachment
 * pipeline: each file is checked and stored by {@link AttachmentService#storeFromEmail} (20 MB,
 * type from the bytes, SVG refused, images re-encoded without EXIF), then bound to the comment the
 * email becomes, so it takes that comment's visibility (a client reply is public, a supporter's
 * {@code support+internal@} reply is internal). Inline {@code cid:} images, which the parser turned
 * into {@code attachment:<index>}, point at the stored file and show inline. Files that are refused
 * are listed as "not attached" with the reason; the original stays in the raw {@code .eml} for 90
 * days.
 *
 * <p>For a reply, an inline image that only the quoted history used (referenced in the whole HTML
 * but not in the kept reply) is not stored again: it belongs to an earlier message.
 *
 * <p>Image work budget: anyone can email the support address, and a small file can hold a huge
 * image that takes seconds of CPU to decode and re-encode. So every file is counted towards the
 * {@link #MAX_FILES} cap, refused or not, and before any decode the image's size is read from its
 * header and checked against: {@link AttachmentProcessor#EMAIL_MAX_PIXELS} per image, {@link
 * #MAIL_MAX_IMAGES} images and {@link #MAIL_MAX_PIXELS} pixels per email, and {@link
 * #ROUND_MAX_IMAGES} images and {@link #ROUND_MAX_PIXELS} pixels per poll round (all mailboxes). A
 * file over a limit is never decoded: it is listed as "not attached (limit)" and stays only in the
 * kept raw copy of the email. A round is opened by the poller ({@link #openRound}); a mail handled
 * outside a round (a restore) gets a round of its own.
 *
 * <p>Ticket first, files after: the files are processed after the ticket or comment is committed,
 * by the attachment worker ({@code InboundAttachmentWorker}); the comment is first saved with
 * {@link #placeholderBody} ("[image]" where an inline image goes). The worker runs each job inside
 * the poll round that read the mail ({@link #inRound}), so the per-round budget still holds. Every
 * file's type, size and processing time is logged (no names or content).
 */
@Component
public class InboundAttachments {

  /** The limit of files per comment; refused files count towards it too. */
  static final int MAX_FILES = AttachmentService.MAX_PER_COMMENT;

  /** Images decoded per email. */
  static final int MAIL_MAX_IMAGES = 10;

  /** Pixels decoded per email (about three 12 MP photos). */
  static final long MAIL_MAX_PIXELS = 40_000_000L;

  /** Images decoded per poll round, across all mails and mailboxes. */
  static final int ROUND_MAX_IMAGES = 60;

  /** Pixels decoded per poll round, across all mails and mailboxes. */
  static final long ROUND_MAX_PIXELS = 200_000_000L;

  private static final Logger log = LoggerFactory.getLogger(InboundAttachments.class);

  /** What an inline image reads as until its file is stored. */
  public static final String IMAGE_PLACEHOLDER = "[image]";

  private static final Pattern INDEX_IMG =
      Pattern.compile("<img\\b[^>]*?src=\"" + HtmlToSafeHtml.ATTACHMENT_SCHEME + "(\\d+)\"[^>]*>");

  private final AttachmentService attachments;
  private final AttachmentProcessor processor;

  /** The poll round running on this thread, if any. */
  private final ThreadLocal<Round> current = new ThreadLocal<>();

  public InboundAttachments(AttachmentService attachments, AttachmentProcessor processor) {
    this.attachments = attachments;
    this.processor = processor;
  }

  /**
   * Image work done in one poll round. Opened on the poller's thread; the attachment worker charges
   * the round's jobs to it from its own thread, so the counters are synchronised.
   */
  public static final class Round implements AutoCloseable {
    private final ThreadLocal<Round> owner;
    private int images;
    private long pixels;

    private Round(ThreadLocal<Round> owner) {
      this.owner = owner;
    }

    /** A round not bound to any thread (a recovered job, a restore). */
    public static Round detached() {
      return new Round(null);
    }

    public synchronized int images() {
      return images;
    }

    public synchronized long pixels() {
      return pixels;
    }

    /** Takes {@code px} from the round's budget; false (nothing taken) when it is used up. */
    synchronized boolean reserve(long px) {
      if (images >= ROUND_MAX_IMAGES || pixels + px > ROUND_MAX_PIXELS) {
        return false;
      }
      images++;
      pixels += px;
      return true;
    }

    /** Gives back what {@link #reserve} took for images whose transaction rolled back. */
    synchronized void release(int count, long px) {
      images = Math.max(0, images - count);
      pixels = Math.max(0, pixels - px);
    }

    @Override
    public void close() {
      if (owner != null && owner.get() == this) {
        owner.remove();
      }
    }
  }

  /**
   * Starts a poll round on this thread: until it is closed, every mail stored on this thread shares
   * the round's image budget. Use with try-with-resources.
   */
  public Round openRound() {
    Round r = new Round(current);
    current.set(r);
    return r;
  }

  /** The round open on this thread, or null. */
  public Round currentRound() {
    return current.get();
  }

  /**
   * Runs {@code work} with {@code round} as this thread's round (the attachment worker running a
   * job of that round), then restores what was there.
   */
  public void inRound(Round round, Runnable work) {
    Round before = current.get();
    current.set(round);
    try {
      work.run();
    } finally {
      if (before == null) {
        current.remove();
      } else {
        current.set(before);
      }
    }
  }

  /**
   * Does {@code a} have files that {@link #store} would consider for {@code bodyHtml}? False when
   * there are none, or (for a reply) only images of the quoted history.
   */
  public boolean hasFiles(InboundAnalysis.Analysed a, String bodyHtml, boolean reply) {
    List<InboundAttachment> files = a.message().attachments();
    if (files.isEmpty()) {
      return false;
    }
    if (!reply) {
      return true;
    }
    Set<Integer> inBody = referenced(bodyHtml);
    Set<Integer> inWhole = referenced(a.safeHtml().html());
    for (int i = 0; i < files.size(); i++) {
      if (!inWhole.contains(i) || inBody.contains(i)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The body saved before the files are processed: each inline image reads {@value
   * #IMAGE_PLACEHOLDER} until the worker replaces the body with {@link Stored#bodyHtml()}.
   */
  public static String placeholderBody(String html) {
    if (html == null) {
      return null;
    }
    return INDEX_IMG.matcher(html).replaceAll(Matcher.quoteReplacement(IMAGE_PLACEHOLDER));
  }

  /**
   * What became of an email's files.
   *
   * @param bodyHtml the body with {@code attachment:<index>} replaced by the stored public ids, and
   *     images that were not stored replaced by a text note
   * @param ids the stored pending files, to bind to the comment
   * @param notAttached one line per refused file: "name - reason"
   */
  public record Stored(String bodyHtml, List<UUID> ids, List<String> notAttached) {
    public Stored {
      ids = List.copyOf(ids);
      notAttached = List.copyOf(notAttached);
    }

    public static Stored none(String bodyHtml) {
      return new Stored(bodyHtml, List.of(), List.of());
    }
  }

  /**
   * Stores the files of {@code a} with {@code scope} (SYSTEM for client mail, the supporter's own
   * scope for their emailed reply) in the caller's transaction.
   *
   * @param bodyHtml the body that will be saved (whole message, or the reply without history)
   * @param reply true when {@code bodyHtml} is the reply part only
   */
  public Stored store(
      AccessScope scope, InboundAnalysis.Analysed a, String bodyHtml, boolean reply) {
    List<InboundAttachment> files = a.message().attachments();
    if (files.isEmpty()) {
      return Stored.none(bodyHtml);
    }
    Set<Integer> inBody = referenced(bodyHtml);
    Set<Integer> inWhole = referenced(a.safeHtml().html());
    Round round = current.get();
    if (round == null) {
      round = new Round(null); // outside a poll round: this mail only
    }
    Map<Integer, UUID> stored = new HashMap<>();
    List<UUID> ids = new ArrayList<>();
    List<String> notAttached = new ArrayList<>();
    int considered = 0;
    int mailImages = 0;
    long mailPixels = 0;
    for (int i = 0; i < files.size(); i++) {
      InboundAttachment f = files.get(i);
      if (reply && inWhole.contains(i) && !inBody.contains(i)) {
        continue; // an image of the quoted history
      }
      String name = displayName(f, i);
      // Every file counts, stored or refused, so a mail cannot make hundreds of attempts.
      if (considered >= MAX_FILES) {
        notAttached.add(name + " - more than " + MAX_FILES + " files in one email");
        continue;
      }
      considered++;
      try {
        // Header only, before any decode: budget the work first.
        long px = processor.imagePixels(f.content());
        if (px >= 0) {
          String limit = null;
          if (px > AttachmentProcessor.EMAIL_MAX_PIXELS) {
            limit = "image larger than " + AttachmentProcessor.EMAIL_MAX_PIXELS / 1_000_000 + " MP";
          } else if (mailImages >= MAIL_MAX_IMAGES || mailPixels + px > MAIL_MAX_PIXELS) {
            limit = "too many or too large images in one email";
          } else if (!round.reserve(px)) {
            limit = "too many images arriving at once, try again later";
          }
          if (limit != null) {
            notAttached.add(name + " - not attached (limit): " + limit);
            continue;
          }
          mailImages++;
          mailPixels += px;
        }
        long started = System.nanoTime();
        try {
          AttachmentService.Uploaded u = attachments.storeFromEmail(scope, name, f.content());
          stored.put(i, u.id());
          ids.add(u.id());
          logTiming(u.contentType(), f.content().length, started, "stored");
        } catch (ApiException e) {
          logTiming(f.contentType(), f.content().length, started, "refused");
          throw e;
        }
      } catch (ApiException e) {
        notAttached.add(name + " - " + reason(e.code()));
      } catch (DataAccessException e) {
        // A database error aborts the whole transaction: the job is retried as a whole.
        throw e;
      } catch (RuntimeException e) {
        // One file that fails unexpectedly (a decoder bug, a disk write error) never
        // blocks the others; it is listed like a refused file.
        log.warn("inbound attachment failed ({})", e.getClass().getSimpleName());
        notAttached.add(name + " - could not be processed");
      }
    }
    giveBackOnRollback(round, mailImages, mailPixels);
    return new Stored(rewrite(bodyHtml, stored), ids, notAttached);
  }

  /**
   * The round budget an attempt took is given back when its transaction rolls back (a database
   * error makes the worker retry the job, which reserves again), so a retry is not refused for
   * images that were never stored.
   */
  private static void giveBackOnRollback(Round round, int images, long pixels) {
    if (images == 0 || !TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              round.release(images, pixels);
            }
          }
        });
  }

  /** One line per file: type, size and time, never the name or content. */
  private static void logTiming(String type, long bytes, long startedNanos, String result) {
    log.info(
        "inbound attachment {}: type {}, {} bytes, {} ms",
        result,
        type,
        bytes,
        (System.nanoTime() - startedNanos) / 1_000_000);
  }

  /** The system-entry text listing refused files, or null when there are none. */
  public static String notAttachedNote(Stored s) {
    if (s.notAttached().isEmpty()) {
      return null;
    }
    String text =
        "Not attached (the original email is kept for 90 days): "
            + String.join("; ", s.notAttached());
    return text.length() > 3900 ? text.substring(0, 3900) + " ..." : text;
  }

  private static String displayName(InboundAttachment f, int index) {
    String raw = f.fileName();
    if (raw == null || raw.isBlank()) {
      String sub = f.contentType().substring(f.contentType().indexOf('/') + 1);
      raw = (f.contentType().startsWith("image/") ? "image-" : "file-") + (index + 1);
      if (sub.matches("[a-z0-9]{1,10}")) {
        raw += "." + sub;
      }
    }
    // The same cleaning as uploads: no path, no control or bidi override characters.
    return AttachmentProcessor.cleanFilename(raw);
  }

  static String reason(String code) {
    return switch (code == null ? "" : code) {
      case "file_too_large" -> "larger than 20 MB";
      case "file_type_refused" -> "SVG files are not accepted";
      case "image_too_large" -> "image larger than 12 megapixels";
      case "image_unreadable" -> "image could not be read";
      default -> "empty or unreadable file";
    };
  }

  private static Set<Integer> referenced(String html) {
    Set<Integer> out = new HashSet<>();
    if (html == null) {
      return out;
    }
    Matcher m = INDEX_IMG.matcher(html);
    while (m.find()) {
      try {
        out.add(Integer.parseInt(m.group(1)));
      } catch (NumberFormatException e) {
        // not an index we made
      }
    }
    return out;
  }

  /** Points stored inline images at their file; any other indexed image becomes a text note. */
  private static String rewrite(String html, Map<Integer, UUID> stored) {
    if (html == null) {
      return null;
    }
    Matcher m = INDEX_IMG.matcher(html);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      UUID id = null;
      try {
        id = stored.get(Integer.parseInt(m.group(1)));
      } catch (NumberFormatException e) {
        // left as a note
      }
      String replacement =
          id == null
              ? "[Image not attached]"
              : m.group()
                  .replace(
                      "src=\"" + HtmlToSafeHtml.ATTACHMENT_SCHEME + m.group(1) + "\"",
                      "src=\"" + HtmlToSafeHtml.ATTACHMENT_SCHEME + id + "\"");
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }
}
