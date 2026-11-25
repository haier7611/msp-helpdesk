package com.ticketsystem.api.branding;

import com.ticketsystem.api.mail.SupportName;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The support name the web UI shows in the top bar, the sign-in card and the browser tab. Public
 * (the sign-in page needs it before anyone signs in) and read-only; it returns only the display
 * name - the same name the emails carry ({@link SupportName}: Admin - Branding, else {@code
 * MAIL_SUPPORT_NAME}), or the neutral {@link #DEFAULT_NAME} if that is somehow blank. {@code
 * no-cache} so an admin's change shows on the next page load.
 */
@RestController
@RequestMapping("/api/v1")
public class DisplayNameController {

  /** Neutral default; the web UI shows the same text while loading or on error. */
  public static final String DEFAULT_NAME = "Support desk";

  private final SupportName supportName;

  public DisplayNameController(SupportName supportName) {
    this.supportName = supportName;
  }

  @GetMapping("/display-name")
  public ResponseEntity<DisplayNameResponse> displayName() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noCache())
        .body(new DisplayNameResponse(resolve(supportName.get())));
  }

  static String resolve(String configured) {
    if (configured == null || configured.isBlank()) {
      return DEFAULT_NAME;
    }
    return configured.strip();
  }

  public record DisplayNameResponse(String name) {}
}
