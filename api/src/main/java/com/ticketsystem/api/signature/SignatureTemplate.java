package com.ticketsystem.api.signature;

/**
 * The company signature template. One row in {@code signature_template}, seeded with synthetic
 * "Example MSP" values; the admin edits it in Admin - Email - Company signature.
 *
 * @param emailMode which address the E: line shows (the supporter's own by default)
 * @param confidentialityLine optional small print under the logo ("" = none)
 * @param logo the admin's uploaded logo, or null for the built-in placeholder
 */
public record SignatureTemplate(
    String companyName,
    String address,
    String brandColour,
    EmailMode emailMode,
    String supportEmail,
    String logoAlt,
    String confidentialityLine,
    Logo logo) {

  /** The template without small print or an uploaded logo (the seeded shape). */
  public SignatureTemplate(
      String companyName,
      String address,
      String brandColour,
      EmailMode emailMode,
      String supportEmail,
      String logoAlt) {
    this(companyName, address, brandColour, emailMode, supportEmail, logoAlt, "", null);
  }

  public SignatureTemplate {
    confidentialityLine = confidentialityLine == null ? "" : confidentialityLine;
  }

  /** What the E: line shows. */
  public enum EmailMode {
    /** The supporter's own address (default). */
    SUPPORTER,
    /** The support mailbox, so client replies stay in the ticket. */
    SUPPORT_MAILBOX
  }

  /**
   * An uploaded logo: a PNG re-encoded by {@link LogoProcessor} (at most 30 KB) and its display
   * size in the email (about 150 px wide).
   */
  public record Logo(byte[] png, int width, int height) {
    @Override
    public String toString() {
      return "Logo[" + width + "x" + height + ", " + png.length + " bytes]";
    }
  }
}
