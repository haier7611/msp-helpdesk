package com.ticketsystem.api.signature;

import com.ticketsystem.api.auth.ApiException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The company logo upload. The logo is embedded in every email as an inline part, so it must stay
 * small (target under 30 KB) and safe:
 *
 * <ul>
 *   <li>The type is detected from the bytes (PNG, JPEG or WebP); SVG, GIF, HTML and anything else
 *       are refused. The file name and Content-Type are ignored.
 *   <li>The picture's size is read from its header before decoding; over 4000 x 4000 pixels (or 16
 *       MP) is refused without allocating it, and large pictures are decoded with source
 *       subsampling, so a small file claiming huge dimensions cannot exhaust memory.
 *   <li>It is scaled to fit 300 x 150 pixels (shown at up to 150 x 75, so it stays sharp on
 *       high-density screens) and re-encoded as PNG: only pixels survive, no metadata or trailing
 *       data. If the PNG is over 30 KB it is scaled down step by step to its display size; a logo
 *       that still does not fit is refused with a message.
 * </ul>
 */
@Component
public class LogoProcessor {

  public static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
  public static final int MAX_LOGO_BYTES = 30 * 1024;
  static final int MAX_DIMENSION = 4000;
  static final long MAX_PIXELS = 16_000_000L;
  static final int BOX_WIDTH = 300;
  static final int BOX_HEIGHT = 150;
  static final int DISPLAY_WIDTH = 150;
  static final int DISPLAY_HEIGHT = 75;

  /** Returns the re-encoded logo with its display size, or throws a 400 with a message. */
  public SignatureTemplate.Logo process(byte[] input) {
    if (input == null || input.length == 0) {
      throw invalid("Choose a logo file to upload.");
    }
    if (input.length > MAX_UPLOAD_BYTES) {
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "The logo file can be at most 2 MB.");
    }
    String format = detect(input);
    if (format == null) {
      throw invalid("Use a PNG, JPEG or WebP logo.");
    }
    BufferedImage source = decode(input, format);
    int w = source.getWidth();
    int h = source.getHeight();
    double display = Math.min(Math.min((double) DISPLAY_WIDTH / w, (double) DISPLAY_HEIGHT / h), 1);
    int displayWidth = Math.max(1, (int) Math.round(w * display));
    int displayHeight = Math.max(1, (int) Math.round(h * display));
    double scale = Math.min(Math.min((double) BOX_WIDTH / w, (double) BOX_HEIGHT / h), 1);
    while (true) {
      int pw = Math.max(1, (int) Math.round(w * scale));
      int ph = Math.max(1, (int) Math.round(h * scale));
      byte[] png = encode(scaled(source, pw, ph));
      if (png.length <= MAX_LOGO_BYTES) {
        return new SignatureTemplate.Logo(png, displayWidth, displayHeight);
      }
      if (pw <= displayWidth) {
        throw invalid(
            "This logo is too detailed to stay under 30 KB in every email. Use a simpler logo,"
                + " such as a PNG with a few flat colours.");
      }
      scale = Math.max(scale * 0.75, (double) displayWidth / w);
    }
  }

  static String detect(byte[] b) {
    if (b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 0x0D
        && b[5] == 0x0A
        && b[6] == 0x1A
        && b[7] == 0x0A) {
      return "png";
    }
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return "jpeg";
    }
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return "webp";
    }
    return null;
  }

  private static BufferedImage decode(byte[] input, String format) {
    String unreadable = "We could not read that logo. Use a PNG, JPEG or WebP file.";
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
      // Only the reader for the detected type, never whatever plug-in claims the bytes.
      Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
      if (in == null || !readers.hasNext()) {
        throw invalid(unreadable);
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in, true, true);
        int w = reader.getWidth(0);
        int h = reader.getHeight(0);
        if (w <= 0
            || h <= 0
            || w > MAX_DIMENSION
            || h > MAX_DIMENSION
            || (long) w * h > MAX_PIXELS) {
          throw invalid("The logo is too large. Use one up to 4000 x 4000 pixels.");
        }
        ImageReadParam param = reader.getDefaultReadParam();
        // Decode no more than about twice the box we scale into.
        int step = Math.max(1, Math.min(w / (BOX_WIDTH * 2), h / (BOX_HEIGHT * 2)));
        if (step > 1) {
          param.setSourceSubsampling(step, step, 0, 0);
        }
        BufferedImage image = reader.read(0, param);
        if (image == null) {
          throw invalid(unreadable);
        }
        return image;
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      if (e instanceof ApiException api) {
        throw api;
      }
      throw invalid(unreadable);
    }
  }

  private static BufferedImage scaled(BufferedImage source, int w, int h) {
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    try {
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.drawImage(source, 0, 0, w, h, null);
    } finally {
      g.dispose();
    }
    return out;
  }

  private static byte[] encode(BufferedImage image) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", out);
    } catch (IOException e) {
      throw invalid("We could not read that logo. Use a PNG, JPEG or WebP file.");
    }
    return out.toByteArray();
  }

  private static ApiException invalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "invalid_logo", message);
  }
}
