package com.ticketsystem.api.signature.admin;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.admin.AdminConfigAuditEvent;
import com.ticketsystem.api.admin.PlainText;
import com.ticketsystem.api.audit.AuditEvent;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.Emails;
import com.ticketsystem.api.mail.MailSettings;
import com.ticketsystem.api.signature.LogoProcessor;
import com.ticketsystem.api.signature.SignatureLogo;
import com.ticketsystem.api.signature.SignatureProfile;
import com.ticketsystem.api.signature.SignatureRenderer;
import com.ticketsystem.api.signature.SignatureRenderer.LogoMode;
import com.ticketsystem.api.signature.SignatureRenderer.Rendered;
import com.ticketsystem.api.signature.SignatureTemplate;
import com.ticketsystem.api.signature.SignatureTemplate.EmailMode;
import com.ticketsystem.api.signature.clientdata.SignatureTemplateRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin - Email - Company signature template and Admin - Branding (one MSP-wide row). The preview
 * is rendered by the {@link SignatureRenderer} - the one renderer outbound mail uses - with a
 * synthetic sample supporter, so the admin sees the layout exactly as clients will. Saving the
 * template, uploading or removing the logo, and changing branding need step-up (enforced on the
 * endpoints) and are audit-logged with the names of changed fields only, never their values or the
 * logo bytes.
 */
@Service
public class CompanySignatureService {

  static final int COMPANY_MAX = 100;
  static final int ADDRESS_MAX = 200;
  static final int ALT_MAX = 100;
  static final int LINE_MAX = 300;
  static final int SUPPORT_NAME_MAX = 100;
  private static final Pattern COLOUR = Pattern.compile("#[0-9A-Fa-f]{6}");

  /** A synthetic supporter for the admin preview. */
  static final SignatureProfile SAMPLE =
      new SignatureProfile(
          "Alex Tran",
          "Service Desk Engineer",
          "+00 000 000 000",
          "",
          "Microsoft 365 specialist",
          "alex.tran@helpdesk.example");

  public record TemplateInput(
      String companyName,
      String address,
      String brandColour,
      String emailMode,
      String supportEmail,
      String logoAlt,
      String confidentialityLine) {}

  public record TemplateView(
      String companyName,
      String address,
      String brandColour,
      EmailMode emailMode,
      String supportEmail,
      String logoAlt,
      String confidentialityLine,
      boolean logoUploaded,
      String logoDataUri,
      int logoWidth,
      int logoHeight) {}

  public record BrandingInput(String supportName, String brandColour) {}

  /**
   * @param supportName the name used in From and templates now
   * @param supportNameCustom whether an admin set it (else it is the environment default)
   * @param defaultSupportName the environment default ({@code MAIL_SUPPORT_NAME})
   */
  public record BrandingView(
      String supportName,
      boolean supportNameCustom,
      String defaultSupportName,
      String brandColour,
      boolean logoUploaded,
      String logoDataUri,
      int logoWidth,
      int logoHeight) {}

  private final SignatureTemplateRepository templates;
  private final SignatureRenderer renderer;
  private final SignatureLogo logo;
  private final LogoProcessor logos;
  private final MailSettings mail;
  private final AuditService audit;
  private final Clock clock;

  public CompanySignatureService(
      SignatureTemplateRepository templates,
      SignatureRenderer renderer,
      SignatureLogo logo,
      LogoProcessor logos,
      MailSettings mail,
      AuditService audit,
      Clock clock) {
    this.templates = templates;
    this.renderer = renderer;
    this.logo = logo;
    this.logos = logos;
    this.mail = mail;
    this.audit = audit;
    this.clock = clock;
  }

  // Template ------------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public TemplateView template(AccessScope scope) {
    scope.requireAdmin();
    return view(templates.get(scope));
  }

  @Transactional
  public TemplateView saveTemplate(AccessScope scope, TemplateInput in, String clientIp) {
    scope.requireAdmin();
    SignatureTemplate old = templates.get(scope);
    SignatureTemplate t = validate(in, old.logo());
    List<String> changed = new ArrayList<>();
    if (!old.companyName().equals(t.companyName())) changed.add("company_name");
    if (!old.address().equals(t.address())) changed.add("address");
    if (!old.brandColour().equalsIgnoreCase(t.brandColour())) changed.add("brand_colour");
    if (old.emailMode() != t.emailMode()) changed.add("email_mode");
    if (!old.supportEmail().equals(t.supportEmail())) changed.add("support_email");
    if (!old.logoAlt().equals(t.logoAlt())) changed.add("logo_alt");
    if (!Objects.equals(old.confidentialityLine(), t.confidentialityLine())) {
      changed.add("confidentiality_line");
    }
    if (!changed.isEmpty()) {
      templates.saveTemplate(scope, t, clock.instant());
      record(scope, AdminConfigAuditEvent.SIGNATURE_TEMPLATE_CHANGED, "changed", changed, clientIp);
    }
    return view(templates.get(scope));
  }

  /** Renders unsaved template values with the sample supporter (nothing is stored). */
  @Transactional(readOnly = true)
  public Rendered preview(AccessScope scope, TemplateInput in) {
    scope.requireAdmin();
    SignatureTemplate t = validate(in, templates.get(scope).logo());
    return renderer.render(SAMPLE, t, LogoMode.PREVIEW);
  }

  @Transactional
  public TemplateView uploadLogo(AccessScope scope, byte[] body, String clientIp) {
    scope.requireAdmin();
    SignatureTemplate.Logo processed = logos.process(body);
    templates.saveLogo(scope, processed, clock.instant());
    record(
        scope,
        AdminConfigAuditEvent.SIGNATURE_LOGO_CHANGED,
        "status",
        List.of("uploaded"),
        clientIp);
    return view(templates.get(scope));
  }

  @Transactional
  public TemplateView removeLogo(AccessScope scope, String clientIp) {
    scope.requireAdmin();
    if (templates.get(scope).logo() != null) {
      templates.saveLogo(scope, null, clock.instant());
      record(
          scope,
          AdminConfigAuditEvent.SIGNATURE_LOGO_CHANGED,
          "status",
          List.of("removed"),
          clientIp);
    }
    return view(templates.get(scope));
  }

  // Branding ------------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public BrandingView branding(AccessScope scope) {
    scope.requireAdmin();
    return brandingView(scope);
  }

  @Transactional
  public BrandingView saveBranding(AccessScope scope, BrandingInput in, String clientIp) {
    scope.requireAdmin();
    String name = PlainText.optional(in.supportName(), "the support name", SUPPORT_NAME_MAX);
    String stored = name.isEmpty() || name.equals(mail.supportName()) ? null : name;
    String colour = colour(in.brandColour());
    String oldName = templates.supportName(scope).orElse(null);
    String oldColour = templates.get(scope).brandColour();
    List<String> changed = new ArrayList<>();
    if (!Objects.equals(oldName, stored)) changed.add("support_name");
    if (!oldColour.equalsIgnoreCase(colour)) changed.add("brand_colour");
    if (!changed.isEmpty()) {
      templates.saveBranding(scope, stored, colour, clock.instant());
      record(scope, AdminConfigAuditEvent.BRANDING_CHANGED, "changed", changed, clientIp);
    }
    return brandingView(scope);
  }

  // ---------------------------------------------------------------------------------------------

  private BrandingView brandingView(AccessScope scope) {
    SignatureTemplate t = templates.get(scope);
    String custom = templates.supportName(scope).orElse(null);
    return new BrandingView(
        custom == null ? mail.supportName() : custom,
        custom != null,
        mail.supportName(),
        t.brandColour(),
        t.logo() != null,
        logo.dataUri(t),
        logoWidth(t),
        logoHeight(t));
  }

  private TemplateView view(SignatureTemplate t) {
    return new TemplateView(
        t.companyName(),
        t.address(),
        t.brandColour(),
        t.emailMode(),
        t.supportEmail(),
        t.logoAlt(),
        t.confidentialityLine(),
        t.logo() != null,
        logo.dataUri(t),
        logoWidth(t),
        logoHeight(t));
  }

  private static int logoWidth(SignatureTemplate t) {
    return t.logo() == null ? SignatureLogo.DISPLAY_WIDTH : t.logo().width();
  }

  private static int logoHeight(SignatureTemplate t) {
    return t.logo() == null ? SignatureLogo.DISPLAY_HEIGHT : t.logo().height();
  }

  static SignatureTemplate validate(TemplateInput in, SignatureTemplate.Logo currentLogo) {
    String company = PlainText.required(in.companyName(), "a company name", COMPANY_MAX);
    String address = PlainText.optional(in.address(), "the address", ADDRESS_MAX);
    String colour = colour(in.brandColour());
    EmailMode mode;
    try {
      mode =
          EmailMode.valueOf(
              in.emailMode() == null ? "" : in.emailMode().strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidRequest("Choose what the E: line shows.");
    }
    String rawEmail = in.supportEmail() == null ? "" : in.supportEmail().strip();
    String supportEmail = "";
    if (!rawEmail.isEmpty()) {
      supportEmail =
          Emails.normalise(rawEmail)
              .orElseThrow(
                  () ->
                      ApiException.invalidRequest(
                          "Enter the support mailbox address, such as support@helpdesk.example."));
    }
    if (mode == EmailMode.SUPPORT_MAILBOX && supportEmail.isEmpty()) {
      throw ApiException.invalidRequest(
          "Enter the support mailbox address to show it on the E: line.");
    }
    String alt = PlainText.required(in.logoAlt(), "the logo's alt text", ALT_MAX);
    String line =
        PlainText.optional(in.confidentialityLine(), "the confidentiality line", LINE_MAX);
    return new SignatureTemplate(
        company, address, colour, mode, supportEmail, alt, line, currentLogo);
  }

  static String colour(String raw) {
    String v = raw == null ? "" : raw.strip();
    if (!COLOUR.matcher(v).matches()) {
      throw ApiException.invalidRequest("Enter the brand colour as #RRGGBB, such as #2B5797.");
    }
    // The name and the A:/E:/P: labels are brand-colour text on a white email (WCAG AA 4.5:1).
    if (contrastWithWhite(v) < 4.5) {
      throw ApiException.invalidRequest(
          "This colour is too light to read on a white email. Choose a darker colour.");
    }
    return v.toUpperCase(Locale.ROOT);
  }

  /** WCAG 2 contrast ratio of a #RRGGBB colour against white. */
  static double contrastWithWhite(String hex) {
    int rgb = Integer.parseInt(hex.substring(1), 16);
    double l =
        0.2126 * channel((rgb >> 16) & 0xFF)
            + 0.7152 * channel((rgb >> 8) & 0xFF)
            + 0.0722 * channel(rgb & 0xFF);
    return 1.05 / (l + 0.05);
  }

  private static double channel(int c) {
    double s = c / 255.0;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  }

  private void record(
      AccessScope scope, AuditEvent type, String key, List<String> values, String clientIp) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put(key, String.join(",", values));
    audit.record(type, scope.principalId().orElse(null), null, clientIp, d);
  }
}
