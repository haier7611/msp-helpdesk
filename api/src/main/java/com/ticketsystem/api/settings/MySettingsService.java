package com.ticketsystem.api.settings;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.settings.clientdata.MySettingsRepository;
import com.ticketsystem.api.signature.ProfileFields;
import com.ticketsystem.api.signature.SignatureProfile;
import com.ticketsystem.api.signature.SignatureRenderer;
import com.ticketsystem.api.signature.SignatureTemplate;
import com.ticketsystem.api.signature.clientdata.SignatureTemplateRepository;
import com.ticketsystem.api.supporter.SupporterAuditEvent;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * My settings for every supporter: profile and signature fields, photo, signature preview,
 * notification choices (sending is done by the notification service), preferences and out of office
 * (auto-assignment uses it). Security (password, MFA, devices, sessions) is the {@code
 * /api/v1/auth} endpoints.
 */
@Service
public class MySettingsService {

  /** Unsaved or saved signature fields as the person typed them. */
  public record ProfileInput(
      String displayName, String jobTitle, String phone, String mobile, String freeLine) {}

  /** Notification events, in display order. */
  public static final List<String> NOTIFICATION_EVENTS =
      List.of(
          "TICKET_ASSIGNED",
          "CLIENT_REPLIED",
          "MENTIONED",
          "INTERNAL_NOTE_ADDED",
          "SLA_WARNING",
          "SLA_BREACHED");

  static final Set<String> COMPOSER_TABS = Set.of("REPLY", "INTERNAL_NOTE");
  static final Set<String> DATE_FORMATS = Set.of("DMY", "MDY", "YMD");
  static final Set<String> THEMES = Set.of("LIGHT", "DARK", "SYSTEM");
  private static final Pattern QUEUE_VIEW = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
  private static final Pattern REGION_ZONE = Pattern.compile("[A-Za-z]+(/[A-Za-z0-9_+-]+)+|UTC");

  private final MySettingsRepository repo;
  private final SignatureTemplateRepository templates;
  private final SignatureRenderer renderer;
  private final PhotoProcessor photos;
  private final AuditService audit;
  private final Clock clock;

  public MySettingsService(
      MySettingsRepository repo,
      SignatureTemplateRepository templates,
      SignatureRenderer renderer,
      PhotoProcessor photos,
      AuditService audit,
      Clock clock) {
    this.repo = repo;
    this.templates = templates;
    this.renderer = renderer;
    this.photos = photos;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ProfileRow profile(AccessScope scope) {
    return repo.profile(scope);
  }

  @Transactional
  public ProfileRow saveProfile(AccessScope scope, ProfileInput in, String clientIp) {
    SignatureProfile p = validate(in, "");
    ProfileRow before = repo.profile(scope);
    repo.saveProfile(
        scope, p.displayName(), p.jobTitle(), p.phone(), p.mobile(), p.freeLine(), clock.instant());
    List<String> changed = new ArrayList<>();
    if (!before.displayName().equals(p.displayName())) changed.add("display_name");
    if (!before.jobTitle().equals(p.jobTitle())) changed.add("job_title");
    if (!before.phone().equals(p.phone())) changed.add("phone");
    if (!before.mobile().equals(p.mobile())) changed.add("mobile");
    if (!before.freeLine().equals(p.freeLine())) changed.add("free_line");
    if (!changed.isEmpty()) {
      long id = scope.principalId().orElseThrow();
      audit.record(
          SupporterAuditEvent.PROFILE_UPDATED,
          id,
          id,
          clientIp,
          Map.of("fields", String.join(",", changed)));
    }
    return repo.profile(scope);
  }

  /** The saved signature, rendered as the preview shows it. */
  @Transactional(readOnly = true)
  public SignatureRenderer.Rendered savedSignature(AccessScope scope) {
    ProfileRow row = repo.profile(scope);
    SignatureProfile p =
        new SignatureProfile(
            row.displayName(),
            row.jobTitle(),
            row.phone(),
            row.mobile(),
            row.freeLine(),
            row.email());
    return renderer.render(p, templates.get(scope), SignatureRenderer.LogoMode.PREVIEW);
  }

  /** Unsaved fields (validated like Save), rendered with the caller's own email address. */
  @Transactional(readOnly = true)
  public SignatureRenderer.Rendered preview(AccessScope scope, ProfileInput in) {
    ProfileRow row = repo.profile(scope);
    SignatureProfile p = validate(in, row.email());
    return renderer.render(p, templates.get(scope), SignatureRenderer.LogoMode.PREVIEW);
  }

  @Transactional(readOnly = true)
  public SignatureTemplate template(AccessScope scope) {
    return templates.get(scope);
  }

  @Transactional(readOnly = true)
  public Optional<byte[]> photo(AccessScope scope) {
    return repo.photo(scope);
  }

  @Transactional
  public void savePhoto(AccessScope scope, byte[] input, String clientIp) {
    byte[] png = photos.process(input);
    repo.savePhoto(scope, png, clock.instant());
    long id = scope.principalId().orElseThrow();
    audit.record(
        SupporterAuditEvent.PROFILE_PHOTO_CHANGED, id, id, clientIp, Map.of("status", "uploaded"));
  }

  @Transactional
  public void deletePhoto(AccessScope scope, String clientIp) {
    if (repo.deletePhoto(scope)) {
      long id = scope.principalId().orElseThrow();
      audit.record(
          SupporterAuditEvent.PROFILE_PHOTO_CHANGED, id, id, clientIp, Map.of("status", "removed"));
    }
  }

  @Transactional(readOnly = true)
  public PreferencesRow preferences(AccessScope scope) {
    return repo.preferences(scope);
  }

  /** Saves the Preferences tab; out-of-office fields are kept as they are. */
  @Transactional
  public PreferencesRow savePreferences(
      AccessScope scope,
      String composerTab,
      String queueView,
      String timezone,
      String dateFormat,
      String theme) {
    PreferencesRow current = repo.preferences(scope);
    PreferencesRow next =
        new PreferencesRow(
            oneOf(composerTab, COMPOSER_TABS, "Choose a default composer tab."),
            queueView(queueView),
            zone(timezone),
            oneOf(dateFormat, DATE_FORMATS, "Choose a date format."),
            oneOf(theme, THEMES, "Choose a theme."),
            current.dailyDigest(),
            current.outOfOffice(),
            current.outOfOfficeFrom(),
            current.outOfOfficeUntil());
    repo.savePreferences(scope, next, clock.instant());
    return next;
  }

  /** Saves Out of office: on/off with optional dates (both or neither). */
  @Transactional
  public PreferencesRow saveOutOfOffice(
      AccessScope scope, boolean on, LocalDate from, LocalDate until) {
    if ((from == null) != (until == null)) {
      throw ApiException.invalidRequest("Enter both dates, or leave both empty.");
    }
    if (from != null && from.isAfter(until)) {
      throw ApiException.invalidRequest("The end date must be on or after the start date.");
    }
    if (on && until != null && until.isBefore(LocalDate.now(clock))) {
      throw ApiException.invalidRequest("The end date is in the past.");
    }
    PreferencesRow c = repo.preferences(scope);
    PreferencesRow next =
        new PreferencesRow(
            c.composerTab(),
            c.queueView(),
            c.timezone(),
            c.dateFormat(),
            c.theme(),
            c.dailyDigest(),
            on,
            from,
            until);
    repo.savePreferences(scope, next, clock.instant());
    return next;
  }

  /** Every event with its stored or default choice (both on), in display order. */
  @Transactional(readOnly = true)
  public Notifications notifications(AccessScope scope) {
    Map<String, NotificationRow> stored = repo.notifications(scope);
    List<NotificationRow> rows = new ArrayList<>();
    for (String event : NOTIFICATION_EVENTS) {
      rows.add(stored.getOrDefault(event, new NotificationRow(event, true, true)));
    }
    return new Notifications(rows, repo.preferences(scope).dailyDigest());
  }

  /** The Notifications tab: one row per event plus the daily digest switch. */
  public record Notifications(List<NotificationRow> events, boolean dailyDigest) {}

  @Transactional
  public Notifications saveNotifications(
      AccessScope scope, List<NotificationRow> events, boolean dailyDigest) {
    Map<String, NotificationRow> byEvent = new LinkedHashMap<>();
    for (NotificationRow r : events == null ? List.<NotificationRow>of() : events) {
      if (r == null || !NOTIFICATION_EVENTS.contains(r.event())) {
        throw ApiException.invalidRequest("Unknown notification event.");
      }
      byEvent.put(r.event(), r);
    }
    repo.saveNotifications(scope, List.copyOf(byEvent.values()));
    PreferencesRow c = repo.preferences(scope);
    repo.savePreferences(
        scope,
        new PreferencesRow(
            c.composerTab(),
            c.queueView(),
            c.timezone(),
            c.dateFormat(),
            c.theme(),
            dailyDigest,
            c.outOfOffice(),
            c.outOfOfficeFrom(),
            c.outOfOfficeUntil()),
        clock.instant());
    return notifications(scope);
  }

  static SignatureProfile validate(ProfileInput in, String email) {
    if (in == null) {
      throw ApiException.invalidRequest("Enter your profile details.");
    }
    return new SignatureProfile(
        ProfileFields.displayName(in.displayName()),
        ProfileFields.jobTitle(in.jobTitle()),
        ProfileFields.phone(in.phone(), "Phone"),
        ProfileFields.phone(in.mobile(), "Mobile"),
        ProfileFields.freeLine(in.freeLine()),
        email);
  }

  private static String oneOf(String value, Set<String> allowed, String message) {
    if (value == null || !allowed.contains(value)) {
      throw ApiException.invalidRequest(message);
    }
    return value;
  }

  private static String queueView(String value) {
    if (value == null || !QUEUE_VIEW.matcher(value).matches()) {
      throw ApiException.invalidRequest("Choose a default queue view.");
    }
    return value;
  }

  static String zone(String value) {
    if (value == null || !REGION_ZONE.matcher(value).matches()) {
      throw ApiException.invalidRequest("Choose a time zone.");
    }
    try {
      return ZoneId.of(value).getId();
    } catch (RuntimeException e) {
      throw ApiException.invalidRequest("Choose a time zone.");
    }
  }
}
