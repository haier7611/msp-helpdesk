package com.ticketsystem.api.auth.mfa;

import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;

/**
 * A QR code as SVG path data, so the web app draws it with a plain {@code <path d=...>} (no server
 * HTML or image injected into the page, and no QR library in the browser). ZXing core only - no
 * AWT. The size includes a 4-module quiet zone.
 *
 * @param size width and height in modules
 * @param path SVG path data: one {@code M x y h1 v1 h-1 z} square per dark module
 */
public record QrCode(int size, String path) {

  private static final int QUIET = 4;

  public static QrCode of(String text) {
    try {
      ByteMatrix m = Encoder.encode(text, ErrorCorrectionLevel.M).getMatrix();
      StringBuilder d = new StringBuilder();
      for (int y = 0; y < m.getHeight(); y++) {
        for (int x = 0; x < m.getWidth(); x++) {
          if (m.get(x, y) == 1) {
            d.append('M').append(x + QUIET).append(' ').append(y + QUIET).append("h1v1h-1z");
          }
        }
      }
      return new QrCode(m.getWidth() + 2 * QUIET, d.toString());
    } catch (WriterException e) {
      throw new IllegalStateException("QR encoding failed", e);
    }
  }
}
