package com.ticketsystem.api.ticket.attachment;

import com.ticketsystem.api.auth.ApiException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Checks and prepares an upload before it is stored:
 *
 * <ul>
 *   <li>At most {@link #MAX_BYTES} (20 MB); larger is refused with 413.
 *   <li>The type is detected from the bytes with Apache Tika (magic numbers only - the file name
 *       and the browser's Content-Type are ignored).
 *   <li>SVG is refused, whether detected from the content or named {@code .svg}/{@code .svgz}.
 *   <li>PNG, JPEG, GIF and WebP are decoded and re-encoded with ImageIO (TwelveMonkeys for WebP),
 *       which drops all metadata (EXIF, GPS, comments): JPEG stays JPEG; PNG and GIF become PNG (a
 *       GIF keeps its first frame only); WebP becomes JPEG, or PNG when it has transparency. EXIF
 *       orientation is not applied. A thumbnail at most {@link #THUMBNAIL_SIZE} px is made. Images
 *       over {@link #MAX_PIXELS} pixels, or that do not decode, are refused.
 *   <li>Everything else is kept byte for byte and is only ever served as a download.
 * </ul>
 */
@Component
public class AttachmentProcessor {

  public static final long MAX_BYTES = 20L * 1024 * 1024;
  static final long MAX_PIXELS = 25_000_000L;

  /** The lower cap for images taken from inbound email. */
  public static final long EMAIL_MAX_PIXELS = 12_000_000L;

  static final int MAX_SIDE = 12_000;
  static final int THUMBNAIL_SIZE = 320;

  private static final Set<String> IMAGE_TYPES =
      Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

  /** Decoding is memory hungry; at most two images are decoded at a time. */
  static final Semaphore DECODERS = new Semaphore(2);

  /**
   * How long a user upload waits for a decoder before it is refused with 429 {@code uploads_busy},
   * so a signed-in user flooding large images cannot park request threads forever. Email intake
   * (one background poller) still waits.
   */
  static final Duration UPLOAD_DECODER_WAIT = Duration.ofSeconds(10);

  private final Tika tika = new Tika();
  private final Duration uploadDecoderWait;

  public AttachmentProcessor() {
    this(UPLOAD_DECODER_WAIT);
  }

  /** Test hook: a shorter decoder wait. */
  AttachmentProcessor(Duration uploadDecoderWait) {
    this.uploadDecoderWait = uploadDecoderWait;
  }

  /** The result: what to store and how to describe it. */
  public record Processed(
      String filename, String contentType, boolean image, byte[] bytes, byte[] thumbnail) {}

  /** A user upload (portal, agent UI): waits at most {@link #UPLOAD_DECODER_WAIT} to decode. */
  public Processed process(String originalFilename, byte[] bytes) {
    return process(originalFilename, bytes, MAX_PIXELS, uploadDecoderWait);
  }

  /**
   * The pixel count of an image that {@link #process} would decode, read from the image header only
   * (no pixel data is decoded); -1 when the bytes are not such an image (other files, SVG, empty or
   * over 20 MB - {@link #process} refuses or keeps those without decoding). Lets a caller budget
   * decoding work before any decode.
   *
   * @throws ApiException {@code image_unreadable} when the header cannot be read
   */
  public long imagePixels(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > MAX_BYTES || !IMAGE_TYPES.contains(detect(bytes))) {
      return -1;
    }
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
      if (readers == null || !readers.hasNext()) {
        throw unreadable();
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in, true, true);
        int w = reader.getWidth(0);
        int h = reader.getHeight(0);
        if (w <= 0 || h <= 0) {
          throw unreadable();
        }
        return (long) w * h;
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      if (e instanceof ApiException api) {
        throw api;
      }
      throw unreadable();
    }
  }

  /**
   * As {@link #process(String, byte[])} with a lower pixel cap for images (email files use {@link
   * #EMAIL_MAX_PIXELS}); the cap is checked from the header before any decode.
   */
  public Processed process(String originalFilename, byte[] bytes, long maxPixels) {
    return process(originalFilename, bytes, maxPixels, null);
  }

  /** {@code decoderWait} null waits as long as it takes (email intake). */
  private Processed process(
      String originalFilename, byte[] bytes, long maxPixels, Duration decoderWait) {
    if (bytes.length > MAX_BYTES) {
      throw tooLarge();
    }
    if (bytes.length == 0) {
      throw ApiException.invalidRequest("That file is empty.");
    }
    String filename = cleanFilename(originalFilename);
    String detected = detect(bytes);
    String lowerName = filename.toLowerCase(Locale.ROOT);
    if (detected.startsWith("image/svg")
        || lowerName.endsWith(".svg")
        || lowerName.endsWith(".svgz")) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "file_type_refused", "SVG files cannot be uploaded.");
    }
    if (!IMAGE_TYPES.contains(detected)) {
      return new Processed(filename, detected, false, bytes, null);
    }
    return reencode(filename, detected, bytes, Math.min(maxPixels, MAX_PIXELS), decoderWait);
  }

  String detect(byte[] bytes) {
    // detect(byte[]) uses the content only (no file name hint).
    String type = tika.detect(bytes);
    return type == null ? "application/octet-stream" : type.toLowerCase(Locale.ROOT);
  }

  private Processed reencode(
      String filename, String detected, byte[] bytes, long maxPixels, Duration decoderWait) {
    try {
      if (decoderWait == null) {
        DECODERS.acquire();
      } else if (!DECODERS.tryAcquire(decoderWait.toMillis(), TimeUnit.MILLISECONDS)) {
        throw busy();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unreadable();
    }
    try {
      BufferedImage image = decode(bytes, maxPixels);
      boolean alpha = image.getColorModel().hasAlpha();
      String format =
          switch (detected) {
            case "image/jpeg" -> "jpeg";
            case "image/webp" -> alpha ? "png" : "jpeg";
            default -> "png";
          };
      byte[] out = write(image, format);
      if (out.length > MAX_BYTES) {
        throw tooLarge();
      }
      byte[] thumb = write(thumbnail(image), format);
      String type = format.equals("jpeg") ? "image/jpeg" : "image/png";
      return new Processed(withExtension(filename, format), type, true, out, thumb);
    } catch (IOException | RuntimeException e) {
      if (e instanceof ApiException api) {
        throw api;
      }
      throw unreadable();
    } finally {
      DECODERS.release();
    }
  }

  private static BufferedImage decode(byte[] bytes, long maxPixels) throws IOException {
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) {
        throw unreadable();
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in, true, true);
        int w = reader.getWidth(0);
        int h = reader.getHeight(0);
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > maxPixels) {
          throw new ApiException(
              HttpStatus.BAD_REQUEST,
              "image_too_large",
              "That image is too large. Please use one under "
                  + maxPixels / 1_000_000
                  + " megapixels.");
        }
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }

  private static byte[] write(BufferedImage image, String format) throws IOException {
    BufferedImage source = image;
    if (format.equals("jpeg") && (image.getColorModel().hasAlpha() || !isRgb(image))) {
      source = copy(image, image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
    }
    ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ImageOutputStream out = ImageIO.createImageOutputStream(buffer)) {
      writer.setOutput(out);
      ImageWriteParam param = writer.getDefaultWriteParam();
      if (format.equals("jpeg")) {
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.9f);
      }
      // No metadata is passed, so none of the original EXIF/GPS/comments is written.
      writer.write(null, new IIOImage(source, null, null), param);
    } finally {
      writer.dispose();
    }
    return buffer.toByteArray();
  }

  private static boolean isRgb(BufferedImage image) {
    int t = image.getType();
    return t == BufferedImage.TYPE_INT_RGB || t == BufferedImage.TYPE_3BYTE_BGR;
  }

  /**
   * The thumbnail, at most {@link #THUMBNAIL_SIZE} px. A large photo is first reduced with a cheap
   * nearest-neighbour step to about twice the thumbnail size, then smoothed with bilinear
   * filtering; one bilinear pass straight from 12 MP was the slowest step of an image after the
   * decode and the re-encode.
   */
  static BufferedImage thumbnail(BufferedImage image) {
    int w = image.getWidth();
    int h = image.getHeight();
    double scale = Math.min(1.0, (double) THUMBNAIL_SIZE / Math.max(w, h));
    int tw = Math.max(1, (int) Math.round(w * scale));
    int th = Math.max(1, (int) Math.round(h * scale));
    int type =
        image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    BufferedImage source = image;
    if (Math.max(w, h) > 4 * THUMBNAIL_SIZE) {
      double step = (double) (2 * THUMBNAIL_SIZE) / Math.max(w, h);
      int iw = Math.max(tw, (int) Math.round(w * step));
      int ih = Math.max(th, (int) Math.round(h * step));
      source = copy(image, iw, ih, type, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }
    return copy(source, tw, th, type);
  }

  private static BufferedImage copy(BufferedImage image, int w, int h, int type) {
    return copy(image, w, h, type, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
  }

  private static BufferedImage copy(
      BufferedImage image, int w, int h, int type, Object interpolation) {
    BufferedImage out = new BufferedImage(w, h, type);
    Graphics2D g = out.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
      if (type == BufferedImage.TYPE_INT_RGB) {
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
      }
      g.drawImage(image, 0, 0, w, h, null);
    } finally {
      g.dispose();
    }
    return out;
  }

  /**
   * The file name to show and to offer on download: no path, no control or invisible format
   * characters (bidi overrides), at most 200 characters; "attachment" when nothing is left.
   */
  public static String cleanFilename(String name) {
    if (name == null) {
      return "attachment";
    }
    String base = name.replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1);
    StringBuilder out = new StringBuilder();
    base.codePoints()
        .filter(
            c ->
                !Character.isISOControl(c)
                    && c != '"'
                    && Character.isDefined(c)
                    // Bidi overrides/isolates and other invisible format characters (U+202E ...)
                    // could make "gpj.exe" read as "exe.jpg": dropped.
                    && Character.getType(c) != Character.FORMAT)
        .forEach(out::appendCodePoint);
    String cleaned = out.toString().strip();
    while (cleaned.startsWith(".")) {
      cleaned = cleaned.substring(1);
    }
    if (cleaned.length() > 200) {
      cleaned = cleaned.substring(cleaned.length() - 200);
    }
    return cleaned.isBlank() ? "attachment" : cleaned;
  }

  /**
   * Undoes URL-style percent-encoding left in a file name, exactly once (fix 2026-11-20): a file
   * saved from a link or sent by a mailer that put an RFC 5987 value in a plain {@code filename=}
   * arrives as {@code Übersicht%20März.xls}. The name is decoded only when it is plainly
   * percent-encoded: it has at least one {@code %XX}, every {@code %} starts a {@code %XX}, there
   * is no literal space (an encoded name never has one) and the bytes are valid UTF-8. Otherwise it
   * is returned unchanged, so a literal per cent sign ({@code 50% off.pdf}) stays as it is. The
   * result is decoded once only ({@code a%2520b} becomes {@code a%20b}) and must still go through
   * {@link #cleanFilename} (paths, control and bidi characters are removed after decoding).
   */
  public static String decodePercentEncodedName(String name) {
    if (name == null || name.indexOf('%') < 0 || name.indexOf(' ') >= 0) {
      return name;
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(name.length());
    byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < utf8.length; i++) {
      byte b = utf8[i];
      if (b != '%') {
        bytes.write(b);
        continue;
      }
      if (i + 2 >= utf8.length) {
        return name;
      }
      int hi = Character.digit(utf8[i + 1], 16);
      int lo = Character.digit(utf8[i + 2], 16);
      if (hi < 0 || lo < 0) {
        return name;
      }
      bytes.write((hi << 4) | lo);
      i += 2;
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toByteArray()))
          .toString();
    } catch (CharacterCodingException e) {
      return name;
    }
  }

  private static String withExtension(String filename, String format) {
    String ext = format.equals("jpeg") ? ".jpg" : ".png";
    String lower = filename.toLowerCase(Locale.ROOT);
    if (lower.endsWith(ext) || (ext.equals(".jpg") && lower.endsWith(".jpeg"))) {
      return filename;
    }
    int dot = filename.lastIndexOf('.');
    String stem = dot > 0 ? filename.substring(0, dot) : filename;
    return stem + ext;
  }

  private static ApiException tooLarge() {
    return new ApiException(
        HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "Files can be at most 20 MB each.");
  }

  private static ApiException busy() {
    return new ApiException(
        HttpStatus.TOO_MANY_REQUESTS,
        "uploads_busy",
        "Too many images are being processed. Please wait a moment and try again.");
  }

  private static ApiException unreadable() {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "image_unreadable", "We could not read that image.");
  }
}
