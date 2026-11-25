package com.ticketsystem.api.ticket.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.security.SecurityGoLive;
import com.ticketsystem.api.ticket.attachment.AttachmentProcessor.Processed;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Upload rules (spec 8, security test 3 save part): type, size, EXIF, SVG. */
@SecurityGoLive({3})
class AttachmentProcessorTest {

  private final AttachmentProcessor processor = new AttachmentProcessor();

  private static String latin1(byte[] b) {
    return new String(b, StandardCharsets.ISO_8859_1);
  }

  @Test
  void jpegExifAndGpsAreStripped() throws Exception {
    byte[] in = TestImages.jpegWithGpsExif(64, 48);
    assertThat(latin1(in)).contains("Exif").contains(TestImages.GPS_MARKER);
    Processed p = processor.process("holiday.jpg", in);
    assertThat(p.image()).isTrue();
    assertThat(p.contentType()).isEqualTo("image/jpeg");
    assertThat(latin1(p.bytes())).doesNotContain("Exif").doesNotContain(TestImages.GPS_MARKER);
    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(p.bytes()));
    assertThat(decoded.getWidth()).isEqualTo(64);
    assertThat(p.thumbnail()).isNotEmpty();
  }

  /** AUD-10: a user upload does not wait forever for a decoder (authenticated DoS). */
  @Test
  void aUserUploadIsRefusedWith429WhenNoDecoderFreesUpInTime() throws Exception {
    AttachmentProcessor quick = new AttachmentProcessor(java.time.Duration.ofMillis(200));
    byte[] png = TestImages.png(40, 40);
    int held = AttachmentProcessor.DECODERS.drainPermits();
    try {
      long started = System.nanoTime();
      assertThatThrownBy(() -> quick.process("a.png", png))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(e.code()).isEqualTo("uploads_busy");
              });
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
          .isLessThan(java.time.Duration.ofSeconds(5));
      // Files that are not decoded never wait.
      assertThat(quick.process("notes.txt", "hello".getBytes(StandardCharsets.UTF_8)).image())
          .isFalse();
    } finally {
      AttachmentProcessor.DECODERS.release(held);
    }
    // A decoder free again: the same upload goes through.
    assertThat(quick.process("a.png", png).image()).isTrue();
    assertThat(AttachmentProcessor.UPLOAD_DECODER_WAIT).isEqualTo(java.time.Duration.ofSeconds(10));
  }

  @Test
  void pngGifAndWebpAreReencoded() {
    Processed png = processor.process("a.png", TestImages.png(500, 200));
    assertThat(png.contentType()).isEqualTo("image/png");
    Processed gif = processor.process("b.gif", TestImages.gif(20, 20));
    assertThat(gif.contentType()).isEqualTo("image/png");
    assertThat(gif.filename()).isEqualTo("b.png");
    Processed webp = processor.process("c.webp", TestImages.WEBP_1X1);
    assertThat(webp.image()).isTrue();
    assertThat(webp.contentType()).isIn("image/png", "image/jpeg");
  }

  @Test
  void thumbnailsAreAtMost320Pixels() throws Exception {
    Processed p = processor.process("wide.png", TestImages.png(1000, 400));
    BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(p.thumbnail()));
    assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isEqualTo(320);
  }

  @Test
  void svgIsRefusedByContentOrByName() {
    assertThatThrownBy(() -> processor.process("logo.svg", TestImages.svg()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("file_type_refused"));
    // Renamed SVG: still detected from the content.
    assertThatThrownBy(() -> processor.process("logo.png", TestImages.svg()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("file_type_refused"));
    assertThatThrownBy(() -> processor.process("x.SVGZ", new byte[] {1, 2, 3}))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void htmlIsKeptAsANonImageWhateverItIsCalled() {
    Processed p = processor.process("photo.png", TestImages.html());
    assertThat(p.image()).isFalse();
    assertThat(p.contentType()).isEqualTo("text/html");
    assertThat(p.thumbnail()).isNull();
  }

  @Test
  void aFakeImageIsRefused() {
    byte[] fake = TestImages.png(10, 10);
    byte[] broken = java.util.Arrays.copyOf(fake, 40); // PNG signature, truncated body
    assertThatThrownBy(() -> processor.process("x.png", broken))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("image_unreadable"));
  }

  @Test
  void overTwentyMegabytesIsRefusedAndExactlyTwentyIsAccepted() {
    byte[] big = new byte[21 * 1024 * 1024];
    assertThatThrownBy(() -> processor.process("big.bin", big))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    byte[] limit = new byte[(int) AttachmentProcessor.MAX_BYTES];
    limit[0] = 1;
    assertThat(processor.process("limit.bin", limit).bytes()).hasSize(limit.length);
  }

  @Test
  void emptyFilesAreRefused() {
    assertThatThrownBy(() -> processor.process("e.txt", new byte[0]))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void fileNamesAreCleaned() {
    assertThat(AttachmentProcessor.cleanFilename("../../etc/passwd")).isEqualTo("passwd");
    assertThat(AttachmentProcessor.cleanFilename("C:\\Users\\x\\report.pdf"))
        .isEqualTo("report.pdf");
    assertThat(AttachmentProcessor.cleanFilename("a\u0000b\r\n\"c.txt")).isEqualTo("abc.txt");
    assertThat(AttachmentProcessor.cleanFilename(".hidden")).isEqualTo("hidden");
    assertThat(AttachmentProcessor.cleanFilename(null)).isEqualTo("attachment");
    assertThat(AttachmentProcessor.cleanFilename("   ")).isEqualTo("attachment");
    assertThat(AttachmentProcessor.cleanFilename("x".repeat(300) + ".pdf"))
        .hasSize(200)
        .endsWith(".pdf");
  }

  @Test
  void percentEncodedNamesAreDecodedOnceAndLiteralPerCentSignsStay() {
    assertThat(
            AttachmentProcessor.decodePercentEncodedName(
                "Übersicht%20Drucker%20Märkte%20Süd%20Q1%20bis%20Q2%20Jahr%202026.xls"))
        .isEqualTo("Übersicht Drucker Märkte Süd Q1 bis Q2 Jahr 2026.xls");
    assertThat(AttachmentProcessor.decodePercentEncodedName("%C3%9Cbersicht%20M%C3%A4rz.xls"))
        .isEqualTo("Übersicht März.xls");
    // a literal space or a lone % means the name is not percent-encoded: unchanged
    assertThat(AttachmentProcessor.decodePercentEncodedName("50% off.pdf"))
        .isEqualTo("50% off.pdf");
    assertThat(AttachmentProcessor.decodePercentEncodedName("100%.pdf")).isEqualTo("100%.pdf");
    assertThat(AttachmentProcessor.decodePercentEncodedName("Q3%20plan%.pdf"))
        .isEqualTo("Q3%20plan%.pdf");
    assertThat(AttachmentProcessor.decodePercentEncodedName("a b%20c.pdf"))
        .isEqualTo("a b%20c.pdf");
    // not valid UTF-8 once decoded: unchanged
    assertThat(AttachmentProcessor.decodePercentEncodedName("caf%E9.pdf")).isEqualTo("caf%E9.pdf");
    // exactly once
    assertThat(AttachmentProcessor.decodePercentEncodedName("a%2520b.pdf")).isEqualTo("a%20b.pdf");
    assertThat(AttachmentProcessor.decodePercentEncodedName("plain.pdf")).isEqualTo("plain.pdf");
    assertThat(AttachmentProcessor.decodePercentEncodedName(null)).isNull();
    // decoding never gets round the cleaning: paths, controls and bidi overrides still go
    assertThat(
            AttachmentProcessor.cleanFilename(
                AttachmentProcessor.decodePercentEncodedName("..%2F..%2Fetc%2Fpasswd")))
        .isEqualTo("passwd");
    assertThat(
            AttachmentProcessor.cleanFilename(
                AttachmentProcessor.decodePercentEncodedName("a%00b%0D%0A%E2%80%AEfdp.exe")))
        .isEqualTo("abfdp.exe");
  }

  @Test
  void imagePixelsReadsTheHeaderAndTheEmailCapRefusesBeforeDecoding() throws Exception {
    BufferedImage big = new BufferedImage(5000, 5000, BufferedImage.TYPE_BYTE_BINARY);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    ImageIO.write(big, "png", out);
    byte[] png = out.toByteArray();
    assertThat(processor.imagePixels(png)).isEqualTo(25_000_000L);
    assertThat(processor.imagePixels("plain text".getBytes(StandardCharsets.UTF_8))).isEqualTo(-1);
    assertThat(processor.imagePixels(TestImages.png(40, 30))).isEqualTo(1200);
    // Allowed on the upload path (25 MP), refused for email (12 MP) from the header.
    assertThatThrownBy(
            () -> processor.process("big.png", png, AttachmentProcessor.EMAIL_MAX_PIXELS))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo("image_too_large");
  }
}
