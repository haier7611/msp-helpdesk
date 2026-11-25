package com.ticketsystem.api.ticket.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link BlobStore} on local disk, under {@code app.attachments-dir} ({@code ATTACHMENTS_DIR},
 * {@code /data/attachments} in the container - a volume outside any web root, never served by
 * Caddy). Files are {@code <dir>/<first two hex digits>/<uuid>}, written to a temporary file in the
 * same directory and then moved into place atomically, so a half-written file is never visible.
 */
@Component
public class DiskBlobStore implements BlobStore {

  private final Path root;

  public DiskBlobStore(@Value("${app.attachments-dir}") String dir) {
    this.root = Path.of(dir).toAbsolutePath().normalize();
  }

  @Override
  public void put(UUID key, byte[] bytes) throws IOException {
    Path target = path(key);
    Files.createDirectories(target.getParent());
    Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
    try {
      Files.write(tmp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Override
  public InputStream open(UUID key) throws IOException {
    return Files.newInputStream(path(key));
  }

  @Override
  public void delete(UUID key) throws IOException {
    Files.deleteIfExists(path(key));
  }

  private Path path(UUID key) {
    String name = key.toString();
    return root.resolve(name.substring(0, 2)).resolve(name);
  }
}
