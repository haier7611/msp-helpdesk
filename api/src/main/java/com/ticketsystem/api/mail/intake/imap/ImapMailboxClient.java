package com.ticketsystem.api.mail.intake.imap;

import com.ticketsystem.api.mail.inbound.InboundLimits;
import com.ticketsystem.api.mail.intake.imap.MailboxAccessException.Category;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.BODY;

/**
 * One IMAP session to a mailbox (Jakarta Mail / Angus Mail), password or XOAUTH2 login, TLS
 * (implicit or required STARTTLS; plain text only to a loopback test server). Reads raw messages by
 * UID without marking them read, and marks them read only after the poller has committed them.
 * Protocol tracing stays off (it would print credentials and mail).
 */
public final class ImapMailboxClient implements AutoCloseable {

  /**
   * Largest message downloaded whole: the 30 MB cap ({@link InboundLimits#DEFAULT}). A bigger one
   * is never downloaded; only its first {@link #TRUNCATED_COPY_BYTES} are kept (headers and the
   * start of the body) and it goes to triage as too large.
   */
  public static final long MAX_DOWNLOAD_BYTES = InboundLimits.DEFAULT.maxMessageBytes();

  /** How much of an oversized message is kept as its truncated raw copy. */
  public static final int TRUNCATED_COPY_BYTES = 256 * 1024;

  /**
   * Bytes read per IMAP FETCH round trip while downloading a message (Angus Mail's default is 16
   * KB). The whole message is held in memory anyway, so a larger block costs no extra memory beyond
   * one block of buffering.
   */
  static final int FETCH_BLOCK_BYTES = 1024 * 1024;

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
  static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

  /** How to reach and log in to the mailbox. {@code secret} is a password or an access token. */
  public record Connection(
      String host, int port, String security, String folder, String username, boolean oauth2) {}

  /** A message waiting on the server: its UID and RFC822.SIZE, no content. */
  public record Pending(long uid, long size) {}

  /**
   * A message read from the server. {@code tooLarge}: only a truncated copy (headers and the start
   * of the body) was downloaded; {@code size} is the server's size of the whole message.
   */
  public record Fetched(long uid, byte[] raw, boolean tooLarge, long size) {}

  private final Store store;
  private final Folder folder;

  /** The body handed out by {@link #download} and not yet {@link #release}d (at most one). */
  private Fetched held;

  /** The largest body this client has held at once, in bytes (tests prove memory is bounded). */
  private long peakHeldBytes;

  private ImapMailboxClient(Store store, Folder folder) {
    this.store = store;
    this.folder = folder;
  }

  /** Connects, logs in and opens the folder read-write. */
  public static ImapMailboxClient open(Connection c, String secret) throws MailboxAccessException {
    String security = c.security() == null ? "TLS" : c.security().toUpperCase(Locale.ROOT);
    if ("NONE".equals(security) && !isLoopback(c.host())) {
      // Plain-text IMAP would send the password or token in the clear.
      throw new MailboxAccessException(Category.NOT_CONFIGURED);
    }
    boolean implicitTls = "TLS".equals(security);
    String protocol = implicitTls ? "imaps" : "imap";
    String p = "mail." + protocol + ".";
    Properties props = new Properties();
    props.setProperty("mail.store.protocol", protocol);
    props.setProperty(p + "connectiontimeout", String.valueOf(CONNECT_TIMEOUT.toMillis()));
    props.setProperty(p + "timeout", String.valueOf(READ_TIMEOUT.toMillis()));
    props.setProperty(p + "writetimeout", String.valueOf(READ_TIMEOUT.toMillis()));
    // BODY.PEEK: reading a message does not set \Seen; the poller sets it after commit.
    props.setProperty(p + "peek", "true");
    // Read a message body in blocks of FETCH_BLOCK_BYTES, not Angus Mail's default 16 KB.
    // Each block is one FETCH round trip, so a 4 MB mail took about 260 sequential round trips to
    // the server (minutes on a remote mailbox such as Gmail); now it takes about 4.
    props.setProperty(p + "fetchsize", String.valueOf(FETCH_BLOCK_BYTES));
    if ("STARTTLS".equals(security)) {
      props.setProperty(p + "starttls.enable", "true");
      props.setProperty(p + "starttls.required", "true");
    }
    if (!"NONE".equals(security)) {
      props.setProperty(p + "ssl.checkserveridentity", "true");
      props.setProperty(p + "ssl.protocols", "TLSv1.3 TLSv1.2");
    }
    if (c.oauth2()) {
      props.setProperty(p + "auth.mechanisms", "XOAUTH2");
      props.setProperty(p + "auth.login.disable", "true");
      props.setProperty(p + "auth.plain.disable", "true");
    }
    props.setProperty("mail.debug", "false");
    Session session = Session.getInstance(props);
    Store store;
    try {
      store = session.getStore(protocol);
      store.connect(c.host(), c.port(), c.username(), secret);
    } catch (AuthenticationFailedException e) {
      throw new MailboxAccessException(Category.LOGIN_FAILED);
    } catch (MessagingException | RuntimeException e) {
      throw new MailboxAccessException(Category.CONNECTION_FAILED);
    }
    try {
      Folder folder = store.getFolder(c.folder() == null ? "INBOX" : c.folder());
      if (!folder.exists()) {
        closeQuietly(store);
        throw new MailboxAccessException(Category.FOLDER_NOT_FOUND);
      }
      folder.open(Folder.READ_WRITE);
      return new ImapMailboxClient(store, folder);
    } catch (MessagingException | RuntimeException e) {
      closeQuietly(store);
      throw new MailboxAccessException(Category.READ_FAILED);
    }
  }

  public long uidValidity() throws MailboxAccessException {
    try {
      return ((UIDFolder) folder).getUIDValidity();
    } catch (MessagingException e) {
      throw new MailboxAccessException(Category.READ_FAILED);
    }
  }

  /**
   * Messages with a UID above {@code lastUid}, oldest first, at most {@code max}: UIDs and sizes
   * only, no content. Read or unread makes no difference: the starting position, not the \Seen
   * flag, decides what is new.
   */
  public List<Pending> listAfter(long lastUid, int max) throws MailboxAccessException {
    try {
      UIDFolder uids = (UIDFolder) folder;
      Message[] messages = uids.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
      FetchProfile fp = new FetchProfile();
      fp.add(UIDFolder.FetchProfileItem.UID);
      fp.add(FetchProfile.Item.SIZE);
      folder.fetch(messages, fp);
      List<Pending> wanted = new ArrayList<>();
      for (Message m : messages) {
        // "n:*" returns the last message even when its UID is below n.
        if (m == null) {
          continue;
        }
        long uid = uids.getUID(m);
        if (uid > lastUid) {
          wanted.add(new Pending(uid, Math.max(0, m.getSize())));
        }
      }
      wanted.sort(Comparator.comparingLong(Pending::uid));
      return List.copyOf(wanted.subList(0, Math.min(max, wanted.size())));
    } catch (MessagingException | RuntimeException e) {
      throw new MailboxAccessException(Category.READ_FAILED);
    }
  }

  /**
   * Downloads one message: the whole raw message when it is at most {@code maxBytes} (written
   * straight into one buffer of that size, no second copy), otherwise only its first {@link
   * #TRUNCATED_COPY_BYTES} as a too-large copy. A server that sends more than it announced is cut
   * off at {@code maxBytes} and the message is treated as too large. Only one body is handed out at
   * a time: the caller {@link #release}s it before downloading the next.
   *
   * @return empty when the message is no longer on the server (deleted meanwhile)
   */
  public Optional<Fetched> download(long uid, long maxBytes) throws MailboxAccessException {
    if (held != null) {
      throw new IllegalStateException("release the previous message first");
    }
    try {
      Message m = ((UIDFolder) folder).getMessageByUID(uid);
      if (m == null) {
        return Optional.empty();
      }
      long size = Math.max(0, m.getSize());
      Fetched f = null;
      if (size <= maxBytes) {
        BoundedBuffer out = new BoundedBuffer((int) Math.max(1024, size), maxBytes);
        try {
          m.writeTo(out);
          f = new Fetched(uid, out.toBytes(), false, Math.max(size, out.count()));
        } catch (BoundedBuffer.Overflow e) {
          f = null; // announced small, sent more: keep only the truncated copy below
        }
      }
      if (f == null) {
        f = new Fetched(uid, truncatedCopy(m), true, Math.max(size, maxBytes + 1));
      }
      held = f;
      peakHeldBytes = Math.max(peakHeldBytes, f.raw().length);
      return Optional.of(f);
    } catch (MessagingException | IOException | RuntimeException e) {
      throw new MailboxAccessException(Category.READ_FAILED);
    }
  }

  /** The poller is done with {@code f} (committed or given up); the next one may be downloaded. */
  public void release(Fetched f) {
    if (held == f) {
      held = null;
    }
  }

  /** The largest message body held at once by this session, in bytes. */
  public long peakHeldBytes() {
    return peakHeldBytes;
  }

  /**
   * The folder's starting position: the UIDNEXT the server reported when the folder was opened, the
   * UID the next arriving message will get. A server that does not report UIDNEXT falls back to the
   * highest existing UID + 1 ({@link #startUid}). Mail already in the folder has a lower UID, so it
   * is never imported.
   */
  public long uidNext() throws MailboxAccessException {
    try {
      long reported = ((UIDFolder) folder).getUIDNext();
      return startUid(reported, reported > 0 ? 0 : highestUid());
    } catch (MessagingException | RuntimeException e) {
      throw new MailboxAccessException(Category.READ_FAILED);
    }
  }

  /**
   * The starting UID from the server's UIDNEXT ({@code -1} or {@code 0} when not reported) or,
   * failing that, from the highest UID in the folder ({@code 0} when it is empty). Never below 1.
   */
  public static long startUid(long reportedUidNext, long highestUid) {
    if (reportedUidNext > 0) {
      return reportedUidNext;
    }
    return Math.max(1, highestUid + 1);
  }

  /**
   * The highest UID in the folder, or 0 when it is empty: the UID of the last message (UIDs rise
   * with message sequence numbers, RFC 9051 2.3.1.1), read with one {@code UID FETCH}.
   */
  long highestUid() throws MailboxAccessException {
    try {
      UIDFolder uids = (UIDFolder) folder;
      int count = folder.getMessageCount();
      if (count == 0) {
        return 0;
      }
      return uids.getUID(folder.getMessage(count));
    } catch (MessagingException e) {
      throw new MailboxAccessException(Category.READ_FAILED);
    }
  }

  /** Marks a processed message as read (best effort: the UID mark is what counts). */
  public void markSeen(long uid) {
    try {
      Message m = ((UIDFolder) folder).getMessageByUID(uid);
      if (m != null) {
        m.setFlag(Flags.Flag.SEEN, true);
      }
    } catch (MessagingException | RuntimeException e) {
      // The UID mark already stops reprocessing; a missing \Seen flag is cosmetic.
    }
  }

  @Override
  public void close() {
    try {
      if (folder.isOpen()) {
        folder.close(false);
      }
    } catch (MessagingException | RuntimeException e) {
      // closing anyway
    }
    closeQuietly(store);
  }

  /**
   * The first {@link #TRUNCATED_COPY_BYTES} of the raw message, fetched with a partial {@code
   * BODY.PEEK[]<0.n>} so the rest never leaves the server.
   */
  private byte[] truncatedCopy(Message m) throws MessagingException {
    int msgno = m.getMessageNumber();
    Object body =
        ((IMAPFolder) folder).doCommand(p -> p.peekBody(msgno, "", 0, TRUNCATED_COPY_BYTES));
    byte[] bytes = new byte[0];
    if (body instanceof BODY b && b.getByteArray() != null) {
      bytes = b.getByteArray().getNewBytes();
    }
    if (bytes.length > TRUNCATED_COPY_BYTES) {
      bytes = Arrays.copyOf(bytes, TRUNCATED_COPY_BYTES);
    }
    return bytes;
  }

  /**
   * A byte buffer sized up front from RFC822.SIZE that refuses to grow beyond a cap, so a download
   * is held once (no {@code toByteArray()} copy when the size was right) and never exceeds the cap.
   */
  static final class BoundedBuffer extends OutputStream {

    static final class Overflow extends IOException {
      private static final long serialVersionUID = 1L;

      Overflow() {
        super("message larger than the cap");
      }
    }

    private final long cap;
    private byte[] buf;
    private int count;

    BoundedBuffer(int initial, long cap) {
      this.cap = cap;
      this.buf = new byte[(int) Math.min(initial, cap)];
    }

    @Override
    public void write(int b) throws IOException {
      ensure(1);
      buf[count++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      ensure(len);
      System.arraycopy(b, off, buf, count, len);
      count += len;
    }

    private void ensure(int more) throws Overflow {
      long needed = (long) count + more;
      if (needed > cap) {
        buf = null; // let the partial download go
        throw new Overflow();
      }
      if (needed > buf.length) {
        long grown = Math.min(cap, Math.max(needed, (long) buf.length * 2));
        buf = Arrays.copyOf(buf, (int) grown);
      }
    }

    int count() {
      return count;
    }

    byte[] toBytes() {
      return count == buf.length ? buf : Arrays.copyOf(buf, count);
    }
  }

  private static boolean isLoopback(String host) {
    return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
  }

  private static void closeQuietly(Store store) {
    try {
      store.close();
    } catch (MessagingException | RuntimeException e) {
      // nothing to do
    }
  }
}
