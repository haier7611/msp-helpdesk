package com.ticketsystem.api.settings.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.settings.MySettingsService;
import com.ticketsystem.api.settings.MySettingsService.Notifications;
import com.ticketsystem.api.settings.MySettingsService.ProfileInput;
import com.ticketsystem.api.settings.NotificationRow;
import com.ticketsystem.api.settings.PhotoProcessor;
import com.ticketsystem.api.settings.PreferencesRow;
import com.ticketsystem.api.settings.ProfileRow;
import com.ticketsystem.api.signature.SignatureRenderer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * My settings for every supporter, under the {@code /api/v1/agent} tree (agents and admins with a
 * FULL session). Every endpoint acts on the caller's own account only. Security settings are the
 * {@code /api/v1/auth} endpoints.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/agent/me")
public class MySettingsController {

  public record ProfileRequest(
      @Size(max = 200) String displayName,
      @Size(max = 200) String jobTitle,
      @Size(max = 100) String phone,
      @Size(max = 100) String mobile,
      @Size(max = 300) String freeLine) {
    ProfileInput input() {
      return new ProfileInput(displayName, jobTitle, phone, mobile, freeLine);
    }
  }

  public record ProfileResponse(
      String email,
      String displayName,
      String jobTitle,
      String phone,
      String mobile,
      String freeLine,
      boolean hasPhoto) {
    static ProfileResponse of(ProfileRow r) {
      return new ProfileResponse(
          r.email(),
          r.displayName(),
          r.jobTitle(),
          r.phone(),
          r.mobile(),
          r.freeLine(),
          r.hasPhoto());
    }
  }

  public record PreferencesRequest(
      @NotNull String composerTab,
      @NotNull @Size(max = 40) String queueView,
      @NotNull @Size(max = 64) String timezone,
      @NotNull String dateFormat,
      @NotNull String theme) {}

  public record PreferencesResponse(
      String composerTab, String queueView, String timezone, String dateFormat, String theme) {
    static PreferencesResponse of(PreferencesRow p) {
      return new PreferencesResponse(
          p.composerTab(), p.queueView(), p.timezone(), p.dateFormat(), p.theme());
    }
  }

  public record OutOfOffice(boolean enabled, LocalDate from, LocalDate until) {
    static OutOfOffice of(PreferencesRow p) {
      return new OutOfOffice(p.outOfOffice(), p.outOfOfficeFrom(), p.outOfOfficeUntil());
    }
  }

  public record NotificationsRequest(
      @NotNull @Size(max = 20) List<@NotNull NotificationRow> events, boolean dailyDigest) {}

  private final MySettingsService settings;

  public MySettingsController(MySettingsService settings) {
    this.settings = settings;
  }

  @GetMapping("/profile")
  ProfileResponse profile(AccessScope scope) {
    return ProfileResponse.of(settings.profile(scope));
  }

  @PutMapping("/profile")
  ProfileResponse saveProfile(
      AccessScope scope, @Validated @RequestBody ProfileRequest body, HttpServletRequest request) {
    return ProfileResponse.of(settings.saveProfile(scope, body.input(), request.getRemoteAddr()));
  }

  /** The saved signature: full and short, HTML and plain text. */
  @GetMapping("/signature")
  SignatureRenderer.Rendered signature(AccessScope scope) {
    return settings.savedSignature(scope);
  }

  /** Renders unsaved fields (validated like Save) so the preview follows typing. */
  @PostMapping("/signature-preview")
  SignatureRenderer.Rendered preview(
      AccessScope scope, @Validated @RequestBody ProfileRequest body) {
    return settings.preview(scope, body.input());
  }

  @GetMapping("/photo")
  ResponseEntity<byte[]> photo(AccessScope scope) {
    return settings
        .photo(scope)
        .map(
            png ->
                ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Disposition", "inline; filename=\"photo.png\"")
                    .body(png))
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No photo uploaded."));
  }

  /**
   * Uploads a photo as the raw request body (no multipart). The type is detected from the bytes;
   * the Content-Type header and any file name are ignored. Read with a hard cap of 5 MB.
   */
  @PutMapping("/photo")
  ResponseEntity<Void> uploadPhoto(AccessScope scope, HttpServletRequest request)
      throws IOException {
    byte[] body;
    try (InputStream in = request.getInputStream()) {
      body = in.readNBytes(PhotoProcessor.MAX_BYTES + 1);
    }
    settings.savePhoto(scope, body, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/photo")
  ResponseEntity<Void> deletePhoto(AccessScope scope, HttpServletRequest request) {
    settings.deletePhoto(scope, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/preferences")
  PreferencesResponse preferences(AccessScope scope) {
    return PreferencesResponse.of(settings.preferences(scope));
  }

  @PutMapping("/preferences")
  PreferencesResponse savePreferences(
      AccessScope scope, @Validated @RequestBody PreferencesRequest body) {
    return PreferencesResponse.of(
        settings.savePreferences(
            scope,
            body.composerTab(),
            body.queueView(),
            body.timezone(),
            body.dateFormat(),
            body.theme()));
  }

  @GetMapping("/out-of-office")
  OutOfOffice outOfOffice(AccessScope scope) {
    return OutOfOffice.of(settings.preferences(scope));
  }

  @PutMapping("/out-of-office")
  OutOfOffice saveOutOfOffice(AccessScope scope, @RequestBody OutOfOffice body) {
    return OutOfOffice.of(
        settings.saveOutOfOffice(scope, body.enabled(), body.from(), body.until()));
  }

  @GetMapping("/notifications")
  Notifications notifications(AccessScope scope) {
    return settings.notifications(scope);
  }

  @PutMapping("/notifications")
  Notifications saveNotifications(
      AccessScope scope, @Validated @RequestBody NotificationsRequest body) {
    return settings.saveNotifications(scope, body.events(), body.dailyDigest());
  }
}
