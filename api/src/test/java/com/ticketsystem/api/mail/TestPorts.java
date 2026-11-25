package com.ticketsystem.api.mail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;

/**
 * Free ports for in-process test servers. The CI host may be shared and the build runs with host
 * networking, so test servers bind 127.0.0.1 only and use the project's port range 55500-55599,
 * skipping the Testcontainers Postgres port the CI script chose. CI (scripts/ci/api.sh) reserves
 * one port per caller of {@link #free()} plus a margin (12; see the count there), handed out first.
 * The bind probe below is the fallback for local runs without reserved ports, and for CI only if
 * the reserved ones run out (it can race a parallel build, hence {@link TestGreenMail}'s retries).
 * Public since WP-10.
 */
public final class TestPorts {

  private static final int FIRST = 55500;
  private static final int LAST = 55599;
  private static final Set<Integer> HANDED_OUT = new HashSet<>();

  private TestPorts() {}

  public static synchronized int free() {
    // CI (scripts/ci/api.sh) reserves ports for this build with flock: use those first, so a
    // parallel build can never take the same port between our check and our bind.
    String reserved = System.getenv("TICKETSYS_IT_EXTRA_PORTS");
    if (reserved != null && !reserved.isBlank()) {
      for (String s : reserved.split(",")) {
        int port = Integer.parseInt(s.strip());
        if (!HANDED_OUT.contains(port)) {
          HANDED_OUT.add(port);
          return port;
        }
      }
    }
    String pg = System.getenv("TICKETSYS_IT_PORT");
    int start = FIRST + (int) (ProcessHandle.current().pid() % (LAST - FIRST + 1));
    for (int i = 0; i <= LAST - FIRST; i++) {
      int port = FIRST + (start - FIRST + i) % (LAST - FIRST + 1);
      if (HANDED_OUT.contains(port) || String.valueOf(port).equals(pg)) {
        continue;
      }
      try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
        s.setReuseAddress(true);
        HANDED_OUT.add(port);
        return port;
      } catch (IOException busy) {
        // try the next one
      }
    }
    throw new IllegalStateException("no free port in 55500-55599");
  }
}
