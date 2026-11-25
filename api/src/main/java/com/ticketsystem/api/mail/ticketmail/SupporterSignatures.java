package com.ticketsystem.api.mail.ticketmail;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.MailPart;
import com.ticketsystem.api.signature.SignatureRenderer;
import java.util.List;

/**
 * The supporter signature in outbound public replies: the full signature on every public reply (no
 * short signature on later replies), with the company logo as an inline {@code cid:} part.
 *
 * <p>Implemented by {@link RendererSupporterSignatures} on the one server-side {@link
 * SignatureRenderer}, so the My settings preview and the sent mail are identical apart from the
 * logo source ({@code data:} in the preview, {@code cid:} in mail).
 */
public interface SupporterSignatures {

  /** Content-ID of the logo part (the same id the renderer uses). */
  String LOGO_CID = SignatureRenderer.LOGO_CID;

  /**
   * @param scope the caller's scope (SYSTEM for the mail listener)
   * @param supporterUserId the author of the public reply
   * @return the signature, or empty parts and plain name when the supporter is unknown
   */
  Signature render(AccessScope scope, long supporterUserId);

  /** A rendered signature and the inline parts its HTML refers to. */
  record Signature(String html, String text, List<MailPart> parts) {
    public Signature {
      parts = List.copyOf(parts);
    }
  }
}
