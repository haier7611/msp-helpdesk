package com.ticketsystem.api.mail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal SMTP server that rejects every recipient with {@code 550 5.1.1}, for the permanent
 * failure path (GreenMail accepts everything). Binds 127.0.0.1 only.
 */
final class RejectingSmtpServer implements AutoCloseable {

  private final ServerSocket server;
  private final Thread thread;
  final AtomicInteger rcptCommands = new AtomicInteger();

  RejectingSmtpServer(int port) throws IOException {
    server = new ServerSocket(port, 10, InetAddress.getLoopbackAddress());
    thread = new Thread(this::serve, "rejecting-smtp");
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Starts on a fresh port from {@link TestPorts#free()}, retrying on new ports when the bind
   * fails. On a busy CI host the project port range can lie inside Linux's ephemeral range, so an
   * outgoing connection on the host (a JDBC connection to the test Postgres, another build) can
   * hold a port as its local port between the hand-out and the bind.
   */
  static RejectingSmtpServer start() throws IOException {
    BindException last = null;
    for (int attempt = 1; attempt <= TestGreenMail.ATTEMPTS; attempt++) {
      try {
        return new RejectingSmtpServer(TestPorts.free());
      } catch (BindException e) {
        last = e;
      }
    }
    throw last;
  }

  int port() {
    return server.getLocalPort();
  }

  private void serve() {
    while (!server.isClosed()) {
      try (Socket s = server.accept();
          BufferedReader in =
              new BufferedReader(
                  new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
          Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.US_ASCII)) {
        reply(out, "220 reject.test ESMTP");
        String line;
        while ((line = in.readLine()) != null) {
          String cmd = line.toUpperCase(Locale.ROOT);
          if (cmd.startsWith("EHLO") || cmd.startsWith("HELO")) {
            reply(out, "250 reject.test");
          } else if (cmd.startsWith("MAIL FROM")) {
            reply(out, "250 2.1.0 OK");
          } else if (cmd.startsWith("RCPT TO")) {
            rcptCommands.incrementAndGet();
            reply(out, "550 5.1.1 mailbox unavailable");
          } else if (cmd.startsWith("QUIT")) {
            reply(out, "221 2.0.0 bye");
            break;
          } else if (cmd.startsWith("RSET") || cmd.startsWith("NOOP")) {
            reply(out, "250 OK");
          } else {
            reply(out, "503 5.5.1 bad sequence");
          }
        }
      } catch (IOException e) {
        // closed or client went away
      }
    }
  }

  private static void reply(Writer out, String line) throws IOException {
    out.write(line + "\r\n");
    out.flush();
  }

  @Override
  public void close() throws IOException {
    server.close();
  }
}
