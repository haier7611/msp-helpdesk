package com.ticketsystem.api.ticket.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Where attachment bytes live. Keys are random UUIDs chosen by the server, never derived from a
 * file name or any other input, so a key cannot address anything outside the store. Authorisation
 * is not done here: callers go through {@code AttachmentService}, which checks the attachment ->
 * comment -> ticket -> organisation chain first.
 */
public interface BlobStore {

  /** Stores {@code bytes} under {@code key} (a fresh random UUID). */
  void put(UUID key, byte[] bytes) throws IOException;

  /** Opens the bytes under {@code key}; the caller closes the stream. */
  InputStream open(UUID key) throws IOException;

  /** Deletes {@code key}; no error when it is already gone. */
  void delete(UUID key) throws IOException;
}
