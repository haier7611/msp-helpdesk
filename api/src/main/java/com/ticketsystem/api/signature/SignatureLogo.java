package com.ticketsystem.api.signature;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * The company logo in signatures. Until an admin uploads one ({@link LogoProcessor}) a built-in
 * placeholder is used: a plain shape in the brand colour, drawn once per colour (no text, so no
 * font is needed on the server). 300 x 80 pixels, shown at 150 x 40 so it stays sharp on
 * high-density screens and well under the 30 KB email budget.
 */
@Component
public class SignatureLogo {

  public static final int DISPLAY_WIDTH = 150;
  public static final int DISPLAY_HEIGHT = 40;

  private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

  /** The PNG bytes for a brand colour ({@code #RRGGBB}). */
  public byte[] png(String brandColour) {
    return cache.computeIfAbsent(brandColour, SignatureLogo::draw);
  }

  /** The PNG as a {@code data:} URI for the in-app preview (the CSP allows data: images). */
  public String dataUri(String brandColour) {
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(png(brandColour));
  }

  /** The logo the template uses: the admin's upload, else the placeholder in the brand colour. */
  public byte[] png(SignatureTemplate template) {
    return template.logo() != null ? template.logo().png() : png(template.brandColour());
  }

  /** {@link #png(SignatureTemplate)} as a {@code data:} URI for the in-app preview. */
  public String dataUri(SignatureTemplate template) {
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(png(template));
  }

  private static byte[] draw(String brandColour) {
    Color brand;
    try {
      brand = Color.decode(brandColour);
    } catch (NumberFormatException e) {
      brand = new Color(0x2B5797);
    }
    BufferedImage img =
        new BufferedImage(DISPLAY_WIDTH * 2, DISPLAY_HEIGHT * 2, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(brand);
      g.fillRoundRect(0, 8, 64, 64, 16, 16);
      g.setColor(Color.WHITE);
      g.fillRoundRect(16, 24, 32, 8, 4, 4);
      g.fillRoundRect(16, 40, 22, 8, 4, 4);
      g.setColor(brand);
      g.fillRoundRect(80, 20, 200, 16, 8, 8);
      g.setColor(new Color(0xA6A6A6));
      g.fillRoundRect(80, 46, 140, 12, 6, 6);
    } finally {
      g.dispose();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(img, "png", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
