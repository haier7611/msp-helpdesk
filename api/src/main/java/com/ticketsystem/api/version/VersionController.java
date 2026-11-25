package com.ticketsystem.api.version;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public build information, used by the web page and the deploy check. */
@RestController
@RequestMapping("/api/v1")
public class VersionController {

  static final String FALLBACK_NAME = "Ticket System";
  static final String FALLBACK_VERSION = "dev";

  private final VersionResponse response;

  public VersionController(
      ObjectProvider<BuildProperties> buildProperties, @Value("${app.git-sha}") String gitSha) {
    BuildProperties build = buildProperties.getIfAvailable();
    String name = build != null && build.getName() != null ? build.getName() : FALLBACK_NAME;
    String version = build != null ? build.getVersion() : FALLBACK_VERSION;
    this.response = new VersionResponse(name, version, sanitiseSha(gitSha));
  }

  @GetMapping("/version")
  public VersionResponse version() {
    return response;
  }

  /** Only a lower-case hex commit id is echoed back; anything else becomes "unknown". */
  static String sanitiseSha(String value) {
    if (value != null && value.matches("[0-9a-f]{7,40}")) {
      return value;
    }
    return "unknown";
  }

  public record VersionResponse(String name, String version, String gitSha) {}
}
