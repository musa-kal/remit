package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.common.IdempotencyKeys;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Streams a file through SHA-256 so large files are never loaded into memory at once. */
public final class FileHashing {

  private static final int BUFFER_SIZE = 64 * 1024;

  private FileHashing() {}

  /**
   * Computes the file idempotency key (spec 3.6).
   *
   * @param path file to hash
   * @return SHA-256 lower-case hex, identical to {@link IdempotencyKeys#forFile(byte[])}
   */
  public static String sha256(Path path) {
    MessageDigest digest = IdempotencyKeys.newDigest();
    byte[] buffer = new byte[BUFFER_SIZE];
    try (InputStream in = Files.newInputStream(path)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot hash " + path, e);
    }
    return IdempotencyKeys.toHex(digest.digest());
  }
}
