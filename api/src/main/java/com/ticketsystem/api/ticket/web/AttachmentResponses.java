package com.ticketsystem.api.ticket.web;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.ticket.attachment.AttachmentProcessor;
import com.ticketsystem.api.ticket.attachment.AttachmentService.Download;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * HTTP details of attachments: downloads carry {@code nosniff}, a sandboxing CSP and no caching;
 * non-images are always {@code application/octet-stream} with {@code Content-Disposition:
 * attachment}, so an uploaded HTML or disguised file can never render in the app's origin;
 * re-encoded images are served inline with their real type.
 */
public final class AttachmentResponses {

  static final String CSP = "sandbox; default-src 'none'; img-src 'self'";

  private AttachmentResponses() {}

  public static ResponseEntity<InputStreamResource> serve(Download d) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Content-Security-Policy", CSP);
    headers.setCacheControl("private, no-store");
    ContentDisposition.Builder disposition =
        d.image() ? ContentDisposition.inline() : ContentDisposition.attachment();
    headers.setContentDisposition(
        disposition.filename(d.filename(), StandardCharsets.UTF_8).build());
    headers.setContentType(
        d.image() ? MediaType.parseMediaType(d.contentType()) : MediaType.APPLICATION_OCTET_STREAM);
    if (d.size() >= 0) {
      headers.setContentLength(d.size());
    }
    return new ResponseEntity<>(new InputStreamResource(d.content()), headers, HttpStatus.OK);
  }

  /** Reads an upload, refusing anything over 20 MB without reading it all. */
  public static byte[] read(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw ApiException.invalidRequest("Choose a file.");
    }
    if (file.getSize() > AttachmentProcessor.MAX_BYTES) {
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "Files can be at most 20 MB each.");
    }
    try (InputStream in = file.getInputStream()) {
      byte[] bytes = in.readNBytes((int) AttachmentProcessor.MAX_BYTES + 1);
      if (bytes.length > AttachmentProcessor.MAX_BYTES) {
        throw new ApiException(
            HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "Files can be at most 20 MB each.");
      }
      return bytes;
    } catch (IOException e) {
      throw ApiException.invalidRequest("We could not read that file. Please try again.");
    }
  }
}
