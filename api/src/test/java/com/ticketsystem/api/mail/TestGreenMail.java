package com.ticketsystem.api.mail;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import java.net.BindException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts GreenMail robustly on a busy CI machine. Several PR builds can run there in parallel, so a
 * start can fail when the machine is loaded (GreenMail's default 2 s start-up timeout) or when a
 * port from {@link TestPorts#free()}'s unreserved bind probe is taken by another build between the
 * probe and the bind. Each start gets a 30 s start-up timeout and up to 5 attempts, each on fresh
 * ports; every retry is logged with the ports and the reason. Tests read the ports actually bound
 * from {@link Started#port(String)}.
 */
public final class TestGreenMail {

  static final long STARTUP_TIMEOUT_MS = 30_000;
  static final int ATTEMPTS = 5;

  private static final Logger log = LoggerFactory.getLogger(TestGreenMail.class);

  private TestGreenMail() {}

  /** A started GreenMail and the port each protocol is bound to. */
  public record Started(GreenMail greenMail, Map<String, Integer> ports) {
    public int port(String protocol) {
      Integer port = ports.get(protocol);
      if (port == null) {
        throw new IllegalArgumentException("GreenMail was not started with " + protocol);
      }
      return port;
    }
  }

  /** Starts GreenMail on 127.0.0.1 with one server per protocol, each on a fresh free port. */
  public static Started start(String... protocols) {
    return start(TestPorts::free, protocols);
  }

  /**
   * Starts GreenMail on 127.0.0.1 with ports from {@code ports} (asked again on every attempt). A
   * caller that must keep a fixed port (e.g. one already in the Spring context) passes {@code () ->
   * port}; the start then still gets the longer timeout and the retries, on the same port.
   */
  public static Started start(IntSupplier ports, String... protocols) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      Map<String, Integer> bound = new LinkedHashMap<>();
      ServerSetup[] setups = new ServerSetup[protocols.length];
      for (int i = 0; i < protocols.length; i++) {
        int port = ports.getAsInt();
        bound.put(protocols[i], port);
        setups[i] = new ServerSetup(port, "127.0.0.1", protocols[i]);
        setups[i].setServerStartupTimeout(STARTUP_TIMEOUT_MS);
      }
      GreenMail greenMail = new GreenMail(setups);
      try {
        greenMail.start();
        return new Started(greenMail, Map.copyOf(bound));
      } catch (RuntimeException e) {
        if (!(e instanceof IllegalStateException) && !isBindFailure(e)) {
          throw e;
        }
        last = e;
        log.warn(
            "GreenMail start attempt {}/{} on {} failed: {}: {}",
            attempt,
            ATTEMPTS,
            bound,
            e.getClass().getSimpleName(),
            describe(e));
        try {
          greenMail.stop();
        } catch (RuntimeException ignored) {
          // a half-started server; nothing more to release
        }
      }
    }
    throw new IllegalStateException(
        "GreenMail did not start after " + ATTEMPTS + " attempts: " + describe(last), last);
  }

  private static boolean isBindFailure(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof BindException) {
        return true;
      }
    }
    return false;
  }

  /** The message chain, so a wrapped bind failure shows its cause in one log line. */
  private static String describe(Throwable e) {
    StringBuilder sb = new StringBuilder();
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (sb.length() > 0) {
        sb.append(" <- ");
      }
      sb.append(t.getMessage());
      if (t.getCause() == t) {
        break;
      }
    }
    return sb.toString();
  }
}
