package com.ticketsystem.api.mail.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.ticketsystem.api.mail.TestGreenMail;
import com.ticketsystem.api.mail.TestPorts;
import com.ticketsystem.api.mail.inbound.BlockListMatcher;
import com.ticketsystem.api.mail.inbound.InboundAnalysis;
import com.ticketsystem.api.mail.inbound.InboundAttachment;
import com.ticketsystem.api.mail.inbound.InboundLimits;
import com.ticketsystem.api.mail.inbound.InboundMailEngine;
import com.ticketsystem.api.mail.intake.imap.ImapMailboxClient;
import com.ticketsystem.api.ticket.attachment.AttachmentProcessor;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Before/after timings for a synthetic email shaped like a slow real-world one (about 4.2 MB, 9
 * attachments: 3 JPEG photos, 2 PDF, 1 XLSX, 1 ZIP, 2 text files). Measures the IMAP download
 * through a proxy that adds {@link #LATENCY_MS} of round-trip latency per client command (a remote
 * mailbox such as Gmail; GreenMail on localhost has none), the MIME parse, and each attachment's
 * check and re-encode with its type, size and milliseconds.
 *
 * <p>Not part of CI (slow, prints numbers rather than asserting them): it runs only when the file
 * {@code api/build/run-benchmark} exists, e.g. {@code mkdir -p api/build && touch
 * api/build/run-benchmark && scripts/ci/api.sh test --tests '*InboundMailSpeedBenchmarkTest'}.
 */
@EnabledIf("requested")
class InboundMailSpeedBenchmarkTest {

  static final int LATENCY_MS = 100;
  static final String USER = "bench@support.example";

  static boolean requested() {
    return Files.exists(Path.of("build", "run-benchmark"));
  }

  @Test
  void syntheticNineAttachmentEmail() throws Exception {
    List<Part> parts = parts();
    byte[] raw = email(parts);
    StringBuilder report = new StringBuilder();
    report
        .append("ADR-039 benchmark: email ")
        .append(raw.length / 1024)
        .append(" KB, ")
        .append(parts.size())
        .append(" attachments\n");

    // 1. IMAP download through a latency proxy.
    TestGreenMail.Started started = TestGreenMail.start(ServerSetup.PROTOCOL_IMAP);
    GreenMail greenMail = started.greenMail();
    try (LatencyProxy proxy =
        new LatencyProxy(TestPorts.free(), started.port(ServerSetup.PROTOCOL_IMAP))) {
      GreenMailUser user = greenMail.setUser(USER, USER, "bench-password");
      user.deliver(new MimeMessage(Session.getInstance(new Properties()), stream(raw)));
      ImapMailboxClient.Connection c =
          new ImapMailboxClient.Connection("127.0.0.1", proxy.port(), "NONE", "INBOX", USER, false);
      long t0 = System.nanoTime();
      byte[] downloaded;
      try (ImapMailboxClient client = ImapMailboxClient.open(c, "bench-password")) {
        List<ImapMailboxClient.Pending> pending = client.listAfter(0, 10);
        ImapMailboxClient.Fetched f =
            client.download(pending.get(0).uid(), ImapMailboxClient.MAX_DOWNLOAD_BYTES).get();
        downloaded = f.raw();
        client.release(f);
      }
      long ms = (System.nanoTime() - t0) / 1_000_000;
      report
          .append(
              String.format(
                  "imap download (+%d ms per command): %d ms, %d client commands%n",
                  LATENCY_MS, ms, proxy.commands()))
          .append("");
      assertThat(downloaded.length).isGreaterThan(raw.length / 2);
    } finally {
      greenMail.stop();
    }

    // 2. MIME parse and HTML sanitising.
    long t1 = System.nanoTime();
    InboundAnalysis analysis =
        new InboundMailEngine("support@support.example", Set.of(), InboundLimits.DEFAULT)
            .analyse(raw, BlockListMatcher.none());
    report.append(String.format("parse: %d ms%n", (System.nanoTime() - t1) / 1_000_000));
    assertThat(analysis).isInstanceOf(InboundAnalysis.Analysed.class);
    List<InboundAttachment> files = ((InboundAnalysis.Analysed) analysis).message().attachments();
    assertThat(files).hasSize(parts.size());

    // 3. Each attachment through the email path: header check, then check and re-encode.
    AttachmentProcessor processor = new AttachmentProcessor();
    // Warm-up (class loading, JIT) on a copy, so the first file is not charged for it.
    processor.process("warm.jpg", parts.get(0).bytes(), AttachmentProcessor.EMAIL_MAX_PIXELS);
    // Best of three runs per file: busy CI hosts make single runs noisy.
    long total = 0;
    for (InboundAttachment f : files) {
      long best = Long.MAX_VALUE;
      String type = null;
      for (int run = 0; run < 3; run++) {
        long s = System.nanoTime();
        processor.imagePixels(f.content());
        AttachmentProcessor.Processed p =
            processor.process(f.fileName(), f.content(), AttachmentProcessor.EMAIL_MAX_PIXELS);
        best = Math.min(best, (System.nanoTime() - s) / 1_000_000);
        type = p.contentType();
      }
      total += best;
      report.append(
          String.format(
              "  %-14s %-28s %8d bytes %6d ms%n", f.fileName(), type, f.content().length, best));
    }
    report.append(String.format("attachments total: %d ms%n", total));

    // 4. Where an image's time goes (plain ImageIO, the steps the processor takes).
    byte[] jpeg = parts.get(0).bytes();
    long d0 = System.nanoTime();
    BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
    long d1 = System.nanoTime();
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    ImageIO.write(decoded, "jpeg", encoded);
    long d2 = System.nanoTime();
    report.append(
        String.format(
            "  one photo: decode %d ms, encode %d ms (%s reader)%n",
            (d1 - d0) / 1_000_000,
            (d2 - d1) / 1_000_000,
            ImageIO.getImageReadersByFormatName("jpeg").next().getClass().getName()));
    System.out.println(report);
  }

  // ---------------------------------------------------------------------------------------------
  // The synthetic email

  record Part(String name, String type, byte[] bytes) {}

  static List<Part> parts() throws IOException {
    Random r = new Random(39);
    List<Part> out = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      out.add(new Part("photo-" + i + ".jpg", "image/jpeg", photo(r, 4000, 3000)));
    }
    out.add(new Part("invoice.pdf", "application/pdf", pdf(r, 180_000)));
    out.add(new Part("report.pdf", "application/pdf", pdf(r, 240_000)));
    out.add(
        new Part(
            "figures.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            xlsx(r, 90_000)));
    out.add(new Part("logs.zip", "application/zip", zip(r, "logs/app.log", 300_000)));
    out.add(new Part("notes.txt", "text/plain", text(r, 20_000)));
    out.add(new Part("steps.txt", "text/plain", text(r, 12_000)));
    return out;
  }

  /** A phone-like 12 MP JPEG: gradient plus noise, so it compresses like a photo. */
  static byte[] photo(Random r, int w, int h) throws IOException {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setPaint(new GradientPaint(0, 0, new Color(40, 90, 160), w, h, new Color(220, 180, 90)));
    g.fillRect(0, 0, w, h);
    g.dispose();
    for (int y = 0; y < h; y += 2) {
      for (int x = 0; x < w; x += 2) {
        int rgb = img.getRGB(x, y);
        int n = r.nextInt(24) - 12;
        int red = clamp(((rgb >> 16) & 0xFF) + n);
        int green = clamp(((rgb >> 8) & 0xFF) + n);
        int blue = clamp((rgb & 0xFF) + n);
        img.setRGB(x, y, (red << 16) | (green << 8) | blue);
      }
    }
    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ImageOutputStream out = ImageIO.createImageOutputStream(buffer)) {
      writer.setOutput(out);
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(0.75f);
      writer.write(null, new IIOImage(img, null, null), param);
    } finally {
      writer.dispose();
    }
    return buffer.toByteArray();
  }

  static int clamp(int v) {
    return Math.max(0, Math.min(255, v));
  }

  static byte[] pdf(Random r, int size) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(
        "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\nstream\n"
            .getBytes(StandardCharsets.US_ASCII));
    byte[] body = new byte[size];
    r.nextBytes(body);
    out.writeBytes(body);
    out.writeBytes("\nendstream\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
  }

  static byte[] xlsx(Random r, int size) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write(
          ("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                  + "content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/"
                  + "vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>")
              .getBytes(StandardCharsets.UTF_8));
      zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
      zip.write("<workbook/>".getBytes(StandardCharsets.UTF_8));
      zip.putNextEntry(new ZipEntry("xl/media/image1.bin"));
      byte[] body = new byte[size];
      r.nextBytes(body);
      zip.write(body);
    }
    return buffer.toByteArray();
  }

  static byte[] zip(Random r, String entry, int size) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
      zip.putNextEntry(new ZipEntry(entry));
      byte[] body = new byte[size];
      r.nextBytes(body);
      zip.write(body);
    }
    return buffer.toByteArray();
  }

  static byte[] text(Random r, int size) {
    StringBuilder sb = new StringBuilder();
    while (sb.length() < size) {
      sb.append("2026-11-20 10:").append(r.nextInt(60)).append(" step ok\n");
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  static byte[] email(List<Part> parts) throws Exception {
    MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
    m.setFrom(new InternetAddress("sam@client.example", "Sam Example"));
    m.setRecipients(Message.RecipientType.TO, "support@support.example");
    m.setSubject("Printer broken - photos and logs attached");
    MimeMultipart mixed = new MimeMultipart("mixed");
    MimeBodyPart body = new MimeBodyPart();
    body.setText(
        "<p>Hello, the printer is broken again. Photos and logs attached.</p>", "utf-8", "html");
    mixed.addBodyPart(body);
    for (Part p : parts) {
      MimeBodyPart file = new MimeBodyPart();
      file.setDataHandler(
          new jakarta.activation.DataHandler(new ByteArrayDataSource(p.bytes(), p.type())));
      file.setDisposition(MimeBodyPart.ATTACHMENT);
      file.setFileName(p.name());
      mixed.addBodyPart(file);
    }
    m.setContent(mixed);
    m.saveChanges();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    m.writeTo(out);
    return out.toByteArray();
  }

  static InputStream stream(byte[] raw) {
    return new java.io.ByteArrayInputStream(raw);
  }

  // ---------------------------------------------------------------------------------------------
  // A TCP proxy that delays every client command by LATENCY_MS (one round trip)

  static final class LatencyProxy implements AutoCloseable {
    private final ServerSocket server;
    private final int target;
    private volatile int commands;
    private final List<Socket> open = new ArrayList<>();

    LatencyProxy(int port, int target) throws IOException {
      this.server = new ServerSocket(port, 5, InetAddress.getLoopbackAddress());
      this.target = target;
      Thread accept = new Thread(this::accept, "bench-proxy");
      accept.setDaemon(true);
      accept.start();
    }

    int port() {
      return server.getLocalPort();
    }

    int commands() {
      return commands;
    }

    private void accept() {
      try {
        while (!server.isClosed()) {
          Socket client = server.accept();
          Socket upstream = new Socket(InetAddress.getLoopbackAddress(), target);
          synchronized (open) {
            open.add(client);
            open.add(upstream);
          }
          pump(client.getInputStream(), upstream.getOutputStream(), true);
          pump(upstream.getInputStream(), client.getOutputStream(), false);
        }
      } catch (IOException e) {
        // closed
      }
    }

    private void pump(InputStream in, OutputStream out, boolean delay) {
      Thread t =
          new Thread(
              () -> {
                byte[] buf = new byte[64 * 1024];
                try {
                  int n;
                  while ((n = in.read(buf)) > 0) {
                    if (delay) {
                      commands++;
                      Thread.sleep(LATENCY_MS);
                    }
                    out.write(buf, 0, n);
                    out.flush();
                  }
                } catch (IOException | InterruptedException e) {
                  // closed
                }
              },
              "bench-pump");
      t.setDaemon(true);
      t.start();
    }

    @Override
    public void close() throws IOException {
      server.close();
      synchronized (open) {
        for (Socket s : open) {
          s.close();
        }
      }
    }
  }
}
