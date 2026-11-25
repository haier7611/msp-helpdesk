package com.ticketsystem.api.settings;

import com.ticketsystem.api.auth.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Profile photo handling. The type is detected from the bytes (magic numbers), never from the file
 * name or the Content-Type header: PNG, JPEG and WebP (TwelveMonkeys reader) are accepted; SVG,
 * HTML, GIF and anything else are refused. The picture's size is read from its header before
 * decoding, so a small file claiming huge dimensions (decompression bomb) is refused without
 * allocating it. The image is then centre-cropped and re-encoded to a 256 x 256 PNG: only pixels
 * survive, so EXIF, GPS and any other metadata or trailing data are gone.
 */
@Component
public class PhotoProcessor {

  public static final int MAX_BYTES = 5 * 1024 * 1024;
  public static final int SIZE = 256;
  static final int MAX_DIMENSION = 6000;
  static final long MAX_PIXELS = 25_000_000L;

  /** Detected input type. */
  enum Type {
    PNG,
    JPEG,
    WEBP
  }

  /** Returns a 256 x 256 PNG, or throws a 400 with a message for the person. */
  public byte[] process(byte[] input) {
    if (input == null || input.length == 0) {
      throw invalid("Choose a picture to upload.");
    }
    if (input.length > MAX_BYTES) {
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "The picture can be at most 5 MB.");
    }
    Type type = detect(input);
    if (type == null) {
      throw invalid("Use a PNG, JPEG or WebP picture.");
    }
    BufferedImage source = decode(input, type);
    return encode(cropAndScale(source));
  }

  static Type detect(byte[] b) {
    if (b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 0x0D
        && b[5] == 0x0A
        && b[6] == 0x1A
        && b[7] == 0x0A) {
      return Type.PNG;
    }
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return Type.JPEG;
    }
    if (isWebp(b)) {
      return Type.WEBP;
    }
    return null;
  }

  private static boolean isWebp(byte[] b) {
    return b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P';
  }

  private static BufferedImage decode(byte[] input, Type type) {
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
      // Only the reader for the detected type, never whatever plug-in claims the bytes.
      Iterator<ImageReader> readers =
          ImageIO.getImageReadersByFormatName(
              switch (type) {
                case PNG -> "png";
                case JPEG -> "jpeg";
                case WEBP -> "webp";
              });
      if (in == null || !readers.hasNext()) {
        throw invalid("We could not read that picture. Use a PNG, JPEG or WebP picture.");
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
          throw invalid("The picture is too large. Use one up to 6000 x 6000 pixels.");
        }
        BufferedImage image = reader.read(0);
        if (image == null) {
          throw invalid("We could not read that picture. Use a PNG, JPEG or WebP picture.");
        }
        return image;
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      if (e instanceof ApiException api) {
        throw api;
      }
      throw invalid("We could not read that picture. Use a PNG, JPEG or WebP picture.");
    }
  }

  private static BufferedImage cropAndScale(BufferedImage source) {
    int side = Math.min(source.getWidth(), source.getHeight());
    int x = (source.getWidth() - side) / 2;
    int y = (source.getHeight() - side) / 2;
    BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    try {
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      // Transparent pictures get a white background (TYPE_INT_RGB has no alpha).
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, SIZE, SIZE);
      g.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
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
      throw invalid("We could not read that picture. Use a PNG, JPEG or WebP picture.");
    }
    return out.toByteArray();
  }

  private static ApiException invalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "invalid_photo", message);
  }
}
