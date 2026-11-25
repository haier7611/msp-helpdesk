package com.ticketsystem.api.signature.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.signature.SignatureTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The one company signature template and branding row (11a "From name"). It belongs to the MSP, not
 * to a client organisation; supporters (preview) and system jobs (outbound mail) may read it, only
 * admins change it.
 */
@Repository
public class SignatureTemplateRepository {

  private final JdbcClient jdbc;

  public SignatureTemplateRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public SignatureTemplate get(AccessScope scope) {
    scope.requireSupporterOrSystem();
    return jdbc.sql(
            "SELECT company_name, address, brand_colour, email_mode, support_email, logo_alt,"
                + " confidentiality_line, logo_png, logo_width, logo_height"
                + " FROM signature_template WHERE id = 1")
        .query(
            (rs, n) -> {
              byte[] png = rs.getBytes("logo_png");
              SignatureTemplate.Logo logo =
                  png == null
                      ? null
                      : new SignatureTemplate.Logo(
                          png, rs.getInt("logo_width"), rs.getInt("logo_height"));
              return new SignatureTemplate(
                  rs.getString("company_name"),
                  rs.getString("address"),
                  rs.getString("brand_colour"),
                  SignatureTemplate.EmailMode.valueOf(rs.getString("email_mode")),
                  rs.getString("support_email"),
                  rs.getString("logo_alt"),
                  rs.getString("confidentiality_line"),
                  logo);
            })
        .single();
  }

  /** Saves the template's text fields and E: line (the logo is saved separately). */
  public void saveTemplate(AccessScope scope, SignatureTemplate t, Instant now) {
    scope.requireAdmin();
    jdbc.sql(
            "UPDATE signature_template SET company_name = :company, address = :address,"
                + " brand_colour = :brand, email_mode = :mode, support_email = :support,"
                + " logo_alt = :alt, confidentiality_line = :line, updated_at = :now"
                + " WHERE id = 1")
        .param("company", t.companyName())
        .param("address", t.address())
        .param("brand", t.brandColour())
        .param("mode", t.emailMode().name())
        .param("support", t.supportEmail())
        .param("alt", t.logoAlt())
        .param("line", t.confidentialityLine())
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Stores an uploaded logo (already re-encoded), or removes it when {@code logo} is null. */
  public void saveLogo(AccessScope scope, SignatureTemplate.Logo logo, Instant now) {
    scope.requireAdmin();
    jdbc.sql(
            "UPDATE signature_template SET logo_png = :png, logo_width = :w, logo_height = :h,"
                + " updated_at = :now WHERE id = 1")
        .param("png", logo == null ? null : logo.png())
        .param("w", logo == null ? null : logo.width())
        .param("h", logo == null ? null : logo.height())
        .param("now", Timestamp.from(now))
        .update();
  }

  /** The admin-set support name, if any (null means the environment default). */
  public Optional<String> supportName(AccessScope scope) {
    scope.requireSupporterOrSystem();
    return jdbc.sql("SELECT support_name FROM signature_template WHERE id = 1")
        .query((rs, n) -> Optional.ofNullable(rs.getString("support_name")))
        .single();
  }

  /** Admin - Branding: the support name (null = environment default) and brand colour. */
  public void saveBranding(AccessScope scope, String supportName, String brandColour, Instant now) {
    scope.requireAdmin();
    jdbc.sql(
            "UPDATE signature_template SET support_name = :name, brand_colour = :brand,"
                + " updated_at = :now WHERE id = 1")
        .param("name", supportName)
        .param("brand", brandColour)
        .param("now", Timestamp.from(now))
        .update();
  }
}
