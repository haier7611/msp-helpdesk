package com.ticketsystem.api.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.ApiException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Profile photo type detection, size limits and the metadata-stripping re-encode (ADR-013). */
class PhotoProcessorTest {

  private final PhotoProcessor processor = new PhotoProcessor();

  static byte[] image(String format, int w, int h) throws Exception {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < w; x++) {
      for (int y = 0; y < h; y++) {
        img.setRGB(x, y, x < w / 2 ? Color.RED.getRGB() : Color.BLUE.getRGB());
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, format, out);
    return out.toByteArray();
  }

  /**
   * A JPEG with an EXIF APP1 segment holding GPS-looking data, inserted right after the SOI marker
   * as cameras and phones write it.
   */
  static byte[] jpegWithGpsExif() throws Exception {
    byte[] jpeg = image("jpg", 400, 300);
    byte[] payload =
        ("Exif\0\0MM\0*\0\0\0\bGPSLatitude=51.5007 GPSLongitude=-0.1246 SYNTHETIC-GPS-MARKER")
            .getBytes(StandardCharsets.ISO_8859_1);
    int len = payload.length + 2;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(jpeg, 0, 2);
    out.write(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len});
    out.write(payload);
    out.write(jpeg, 2, jpeg.length - 2);
    return out.toByteArray();
  }

  private static BufferedImage read(byte[] png) throws Exception {
    return ImageIO.read(new ByteArrayInputStream(png));
  }

  @Test
  void pngIsCroppedAndReEncodedTo256Square() throws Exception {
    byte[] out = processor.process(image("png", 600, 300));
    assertThat(PhotoProcessor.detect(out)).isEqualTo(PhotoProcessor.Type.PNG);
    BufferedImage img = read(out);
    assertThat(img.getWidth()).isEqualTo(256);
    assertThat(img.getHeight()).isEqualTo(256);
  }

  @Test
  void jpegExifAndGpsAreGoneAfterTheReEncode() throws Exception {
    byte[] in = jpegWithGpsExif();
    assertThat(new String(in, StandardCharsets.ISO_8859_1)).contains("SYNTHETIC-GPS-MARKER");
    byte[] out = processor.process(in);
    String text = new String(out, StandardCharsets.ISO_8859_1);
    assertThat(text).doesNotContain("Exif").doesNotContain("GPS").doesNotContain("SYNTHETIC");
    assertThat(PhotoProcessor.detect(out)).isEqualTo(PhotoProcessor.Type.PNG);
    assertThat(read(out).getWidth()).isEqualTo(256);
  }

  @Test
  void svgHtmlGifAndRenamedFilesAreRefused() throws Exception {
    byte[] svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
            .getBytes(StandardCharsets.UTF_8);
    byte[] html = "<!DOCTYPE html><html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
    for (byte[] bad : new byte[][] {svg, html, image("gif", 10, 10), new byte[] {1, 2, 3}}) {
      assertThatThrownBy(() -> processor.process(bad))
          .isInstanceOf(ApiException.class)
          .hasMessageContaining("PNG, JPEG or WebP");
    }
  }

  @Test
  void webpIsAcceptedAndReEncodedToPng() throws Exception {
    // A 1 x 1 lossless WebP (synthetic, white).
    byte[] webp =
        java.util.Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");
    byte[] out = processor.process(webp);
    assertThat(PhotoProcessor.detect(out)).isEqualTo(PhotoProcessor.Type.PNG);
    assertThat(read(out).getWidth()).isEqualTo(256);
  }

  @Test
  void aBrokenWebpIsRefused() {
    byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
    assertThatThrownBy(() -> processor.process(webp))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("could not read");
  }

  @Test
  void emptyTooLargeAndCorruptInputsAreRefused() throws Exception {
    assertThatThrownBy(() -> processor.process(new byte[0])).isInstanceOf(ApiException.class);
    byte[] huge = new byte[PhotoProcessor.MAX_BYTES + 1];
    huge[0] = (byte) 0xFF;
    huge[1] = (byte) 0xD8;
    huge[2] = (byte) 0xFF;
    assertThatThrownBy(() -> processor.process(huge))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("too_large"));
    byte[] png = image("png", 20, 20);
    byte[] truncated = java.util.Arrays.copyOf(png, 30);
    assertThatThrownBy(() -> processor.process(truncated))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("could not read");
  }

  @Test
  void aSmallFileClaimingHugeDimensionsIsRefusedBeforeDecoding() throws Exception {
    byte[] png = image("png", 8, 8);
    // Patch the IHDR width/height (bytes 16-23) to 60000 x 60000; the CRC no longer matches, but
    // the size check reads the header first and refuses before any pixel is allocated.
    byte[] bomb = png.clone();
    int big = 60000;
    for (int i = 0; i < 2; i++) {
      int off = 16 + i * 4;
      bomb[off] = (byte) (big >> 24);
      bomb[off + 1] = (byte) (big >> 16);
      bomb[off + 2] = (byte) (big >> 8);
      bomb[off + 3] = (byte) big;
    }
    assertThatThrownBy(() -> processor.process(bomb)).isInstanceOf(ApiException.class);
  }
}
