package com.ticketsystem.api.auth.device;

import java.util.Locale;

/**
 * A short human label such as "Firefox on Windows" from a User-Agent header, for the session and
 * trusted-device lists and the new-device email. Display only: the header is chosen by the client,
 * so nothing security-relevant ever depends on it.
 */
public final class DeviceLabels {

  private DeviceLabels() {}

  public static String fromUserAgent(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return "Unknown browser";
    }
    String ua = userAgent.toLowerCase(Locale.ROOT);
    String browser;
    if (ua.contains("edg/") || ua.contains("edge/")) {
      browser = "Edge";
    } else if (ua.contains("opr/") || ua.contains("opera")) {
      browser = "Opera";
    } else if (ua.contains("firefox/") || ua.contains("fxios/")) {
      browser = "Firefox";
    } else if (ua.contains("chrome/") || ua.contains("crios/")) {
      browser = "Chrome";
    } else if (ua.contains("safari/")) {
      browser = "Safari";
    } else {
      browser = "Unknown browser";
    }
    String os;
    if (ua.contains("iphone") || ua.contains("ipad")) {
      os = "iOS";
    } else if (ua.contains("android")) {
      os = "Android";
    } else if (ua.contains("windows")) {
      os = "Windows";
    } else if (ua.contains("mac os x") || ua.contains("macintosh")) {
      os = "macOS";
    } else if (ua.contains("cros")) {
      os = "ChromeOS";
    } else if (ua.contains("linux")) {
      os = "Linux";
    } else {
      return browser;
    }
    return browser + " on " + os;
  }
}
