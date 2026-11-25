package com.ticketsystem.api.ticket.attachment;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Synthetic test images (no real photos). */
public final class TestImages {

  /** Text planted in the fake EXIF block; must never survive re-encoding. */
  public static final String GPS_MARKER = "GPSLatitude-SYNTHETIC-10.7769N";

  /** A well-known 1x1 lossless WebP. */
  public static final byte[] WEBP_1X1 =
      Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

  private TestImages() {}

  public static byte[] png(int w, int h) {
    return write(image(w, h, BufferedImage.TYPE_INT_ARGB), "png");
  }

  public static byte[] gif(int w, int h) {
    return write(image(w, h, BufferedImage.TYPE_INT_RGB), "gif");
  }

  public static byte[] jpeg(int w, int h) {
    return write(image(w, h, BufferedImage.TYPE_INT_RGB), "jpeg");
  }

  /**
   * A JPEG with an APP1 "Exif" segment right after SOI: a valid empty big-endian TIFF header
   * followed by {@link #GPS_MARKER} as padding - stands in for a phone photo with GPS data.
   */
  public static byte[] jpegWithGpsExif(int w, int h) {
    byte[] plain = jpeg(w, h);
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.writeBytes("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
    // TIFF header (MM, 42, IFD at offset 8), IFD with 0 entries, next IFD 0.
    payload.writeBytes(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0});
    payload.writeBytes(GPS_MARKER.getBytes(StandardCharsets.ISO_8859_1));
    byte[] p = payload.toByteArray();
    int len = p.length + 2;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(plain, 0, 2); // SOI
    out.write(0xFF);
    out.write(0xE1);
    out.write((len >> 8) & 0xFF);
    out.write(len & 0xFF);
    out.writeBytes(p);
    out.write(plain, 2, plain.length - 2);
    return out.toByteArray();
  }

  /** An SVG with a script. */
  public static byte[] svg() {
    return ("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\""
            + " height=\"10\"><script>alert(1)</script><rect width=\"10\" height=\"10\"/></svg>")
        .getBytes(StandardCharsets.UTF_8);
  }

  public static byte[] html() {
    return "<!DOCTYPE html><html><head><title>x</title></head><body><script>alert(1)</script></body></html>"
        .getBytes(StandardCharsets.UTF_8);
  }

  private static BufferedImage image(int w, int h, int type) {
    BufferedImage img = new BufferedImage(w, h, type);
    Graphics2D g = img.createGraphics();
    g.setColor(new Color(0x2B, 0x57, 0x97));
    g.fillRect(0, 0, w, h);
    g.setColor(Color.WHITE);
    g.fillRect(w / 4, h / 4, Math.max(1, w / 2), Math.max(1, h / 2));
    g.dispose();
    return img;
  }

  private static byte[] write(BufferedImage img, String format) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!ImageIO.write(img, format, out)) {
        throw new IllegalStateException("no writer for " + format);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
