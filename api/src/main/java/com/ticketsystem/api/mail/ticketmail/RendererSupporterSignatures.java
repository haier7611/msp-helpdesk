package com.ticketsystem.api.mail.ticketmail;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.MailPart;
import com.ticketsystem.api.mail.ticketmail.clientdata.TicketMailRepository;
import com.ticketsystem.api.signature.SignatureLogo;
import com.ticketsystem.api.signature.SignatureProfile;
import com.ticketsystem.api.signature.SignatureRenderer;
import com.ticketsystem.api.signature.SignatureTemplate;
import com.ticketsystem.api.signature.clientdata.SignatureTemplateRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Outbound signatures through the {@link SignatureRenderer}: the supporter's saved profile fields,
 * the company template, {@link SignatureRenderer.LogoMode#EMAIL} so the logo is {@code
 * cid:signature-logo}, and the logo bytes attached as that inline part. The E: line is the
 * supporter's own email by default; when the template is switched to the support mailbox but names
 * none, the current support address is used.
 */
@Component
public class RendererSupporterSignatures implements SupporterSignatures {

  private final SignatureRenderer renderer;
  private final SignatureTemplateRepository templates;
  private final SignatureLogo logo;
  private final TicketMailRepository mailData;
  private final SupportAddresses addresses;

  public RendererSupporterSignatures(
      SignatureRenderer renderer,
      SignatureTemplateRepository templates,
      SignatureLogo logo,
      TicketMailRepository mailData,
      SupportAddresses addresses) {
    this.renderer = renderer;
    this.templates = templates;
    this.logo = logo;
    this.mailData = mailData;
    this.addresses = addresses;
  }

  @Override
  public Signature render(AccessScope scope, long supporterUserId) {
    SignatureProfile profile = mailData.signatureProfile(scope, supporterUserId).orElse(null);
    if (profile == null) {
      return new Signature("", "", List.of());
    }
    SignatureTemplate t = template(scope);
    SignatureRenderer.Rendered r = renderer.render(profile, t, SignatureRenderer.LogoMode.EMAIL);
    return new Signature(
        r.fullHtml(),
        r.fullText(),
        List.of(new MailPart(LOGO_CID, "logo.png", "image/png", true, logo.png(t))));
  }

  /** The company template, with the support address filled in for the support-mailbox E: mode. */
  SignatureTemplate template(AccessScope scope) {
    SignatureTemplate t = templates.get(scope);
    if (t.emailMode() == SignatureTemplate.EmailMode.SUPPORT_MAILBOX
        && (t.supportEmail() == null || t.supportEmail().isBlank())) {
      String support = addresses.support(scope).orElse("");
      return new SignatureTemplate(
          t.companyName(),
          t.address(),
          t.brandColour(),
          t.emailMode(),
          support,
          t.logoAlt(),
          t.confidentialityLine(),
          t.logo());
    }
    return t;
  }
}
