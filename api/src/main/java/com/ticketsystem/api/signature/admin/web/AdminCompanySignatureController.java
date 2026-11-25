package com.ticketsystem.api.signature.admin.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.session.RequiresStepUp;
import com.ticketsystem.api.signature.LogoProcessor;
import com.ticketsystem.api.signature.SignatureRenderer.Rendered;
import com.ticketsystem.api.signature.admin.CompanySignatureService;
import com.ticketsystem.api.signature.admin.CompanySignatureService.BrandingInput;
import com.ticketsystem.api.signature.admin.CompanySignatureService.BrandingView;
import com.ticketsystem.api.signature.admin.CompanySignatureService.TemplateInput;
import com.ticketsystem.api.signature.admin.CompanySignatureService.TemplateView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Email - Company signature template and Admin - Branding. The {@code /api/v1/admin} tree
 * is admin-only; the service checks the admin scope again. Saving the template, the logo and
 * branding needs step-up; the preview does not (it stores nothing).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminCompanySignatureController {

  public record TemplateBody(
      @Size(max = 400) String companyName,
      @Size(max = 600) String address,
      @Size(max = 20) String brandColour,
      @Size(max = 20) String emailMode,
      @Size(max = 400) String supportEmail,
      @Size(max = 400) String logoAlt,
      @Size(max = 900) String confidentialityLine) {

    TemplateInput toInput() {
      return new TemplateInput(
          companyName, address, brandColour, emailMode, supportEmail, logoAlt, confidentialityLine);
    }
  }

  public record BrandingBody(
      @Size(max = 400) String supportName, @Size(max = 20) String brandColour) {}

  private final CompanySignatureService service;

  public AdminCompanySignatureController(CompanySignatureService service) {
    this.service = service;
  }

  @GetMapping("/signature-template")
  TemplateView template(AccessScope scope) {
    return service.template(scope);
  }

  @PutMapping("/signature-template")
  @RequiresStepUp
  TemplateView saveTemplate(
      AccessScope scope, @Validated @RequestBody TemplateBody body, HttpServletRequest request) {
    return service.saveTemplate(scope, body.toInput(), request.getRemoteAddr());
  }

  @PostMapping("/signature-template/preview")
  Rendered preview(AccessScope scope, @Validated @RequestBody TemplateBody body) {
    return service.preview(scope, body.toInput());
  }

  /**
   * Uploads the logo as the raw request body (no multipart), read with a hard cap of 2 MB. The type
   * is detected from the bytes; the Content-Type header and any file name are ignored.
   */
  @PutMapping("/signature-template/logo")
  @RequiresStepUp
  TemplateView uploadLogo(AccessScope scope, HttpServletRequest request) throws IOException {
    byte[] body;
    try (InputStream in = request.getInputStream()) {
      body = in.readNBytes(LogoProcessor.MAX_UPLOAD_BYTES + 1);
    }
    return service.uploadLogo(scope, body, request.getRemoteAddr());
  }

  @DeleteMapping("/signature-template/logo")
  @RequiresStepUp
  TemplateView removeLogo(AccessScope scope, HttpServletRequest request) {
    return service.removeLogo(scope, request.getRemoteAddr());
  }

  @GetMapping("/branding")
  BrandingView branding(AccessScope scope) {
    return service.branding(scope);
  }

  @PutMapping("/branding")
  @RequiresStepUp
  BrandingView saveBranding(
      AccessScope scope, @Validated @RequestBody BrandingBody body, HttpServletRequest request) {
    return service.saveBranding(
        scope, new BrandingInput(body.supportName(), body.brandColour()), request.getRemoteAddr());
  }
}
