package com.ticketsystem.api.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.ApiException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Company logo upload (spec 5, ADR-015): type sniffing, resize, re-encode, the 30 KB budget. */
class LogoProcessorTest {

  private final LogoProcessor processor = new LogoProcessor();

  /** A synthetic two-colour logo-like picture. */
  static byte[] logo(String format, int w, int h) throws Exception {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < w; x++) {
      for (int y = 0; y < h; y++) {
        img.setRGB(x, y, x < w / 3 ? new Color(0x2B5797).getRGB() : Color.WHITE.getRGB());
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, format, out);
    return out.toByteArray();
  }

  /** Random noise: does not compress, so it cannot fit in 30 KB at display size either. */
  static byte[] noise(int w, int h) throws Exception {
    Random random = new Random(42);
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < w; x++) {
      for (int y = 0; y < h; y++) {
        img.setRGB(x, y, random.nextInt(0xFFFFFF));
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  private static BufferedImage read(byte[] png) throws Exception {
    return ImageIO.read(new ByteArrayInputStream(png));
  }

  @Test
  void aLargeLogoIsScaledIntoTheBoxAndReEncodedAsASmallPng() throws Exception {
    SignatureTemplate.Logo l = processor.process(logo("png", 1200, 400));
    BufferedImage out = read(l.png());
    assertThat(out.getWidth()).isEqualTo(300);
    assertThat(out.getHeight()).isEqualTo(100);
    assertThat(l.width()).isEqualTo(150);
    assertThat(l.height()).isEqualTo(50);
    assertThat(l.png().length).isLessThanOrEqualTo(LogoProcessor.MAX_LOGO_BYTES);
    assertThat(LogoProcessor.detect(l.png())).isEqualTo("png");
  }

  @Test
  void aTallLogoIsLimitedByHeightAndASmallOneIsNotEnlarged() throws Exception {
    SignatureTemplate.Logo tall = processor.process(logo("jpg", 400, 800));
    assertThat(tall.height()).isEqualTo(75);
    assertThat(tall.width()).isEqualTo(38);
    SignatureTemplate.Logo small = processor.process(logo("png", 120, 40));
    assertThat(small.width()).isEqualTo(120);
    assertThat(small.height()).isEqualTo(40);
    assertThat(read(small.png()).getWidth()).isEqualTo(120);
  }

  @Test
  void jpegMetadataIsGoneAfterTheReEncode() throws Exception {
    byte[] jpeg = logo("jpg", 600, 200);
    byte[] payload = "Exif\0\0SYNTHETIC-GPS-MARKER".getBytes(StandardCharsets.ISO_8859_1);
    ByteArrayOutputStream withExif = new ByteArrayOutputStream();
    withExif.write(jpeg, 0, 2);
    withExif.write(new byte[] {(byte) 0xFF, (byte) 0xE1, 0, (byte) (payload.length + 2)}, 0, 4);
    withExif.write(payload);
    withExif.write(jpeg, 2, jpeg.length - 2);
    byte[] png = processor.process(withExif.toByteArray()).png();
    assertThat(new String(png, StandardCharsets.ISO_8859_1)).doesNotContain("SYNTHETIC-GPS");
  }

  @Test
  void svgHtmlGifAndEmptyFilesAreRefused() throws Exception {
    for (String bad :
        new String[] {
          "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x</script></svg>",
          "<html><body>x</body></html>",
          "GIF89a...."
        }) {
      assertThatThrownBy(() -> processor.process(bad.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(ApiException.class)
          .hasMessageContaining("PNG, JPEG or WebP");
    }
    assertThatThrownBy(() -> processor.process(new byte[0])).isInstanceOf(ApiException.class);
  }

  @Test
  void anOversizeFileAndAHugeDimensionClaimAreRefused() throws Exception {
    byte[] big = new byte[LogoProcessor.MAX_UPLOAD_BYTES + 1];
    assertThatThrownBy(() -> processor.process(big))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("too_large"));
    byte[] png = logo("png", 8, 8);
    byte[] bomb = png.clone();
    int huge = 60000;
    for (int i = 0; i < 2; i++) {
      int off = 16 + i * 4;
      bomb[off] = (byte) (huge >> 24);
      bomb[off + 1] = (byte) (huge >> 16);
      bomb[off + 2] = (byte) (huge >> 8);
      bomb[off + 3] = (byte) huge;
    }
    assertThatThrownBy(() -> processor.process(bomb)).isInstanceOf(ApiException.class);
  }

  @Test
  void aLogoThatCannotFitIn30KbIsRefusedWithAMessage() throws Exception {
    assertThatThrownBy(() -> processor.process(noise(600, 300)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("30 KB");
  }
}
