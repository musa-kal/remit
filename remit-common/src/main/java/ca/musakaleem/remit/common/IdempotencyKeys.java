package ca.musakaleem.remit.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Derives the file and instruction idempotency keys defined in spec section 3.6. */
public final class IdempotencyKeys {

  private static final String ALGORITHM = "SHA-256";
  private static final String SEPARATOR = "|";

  private IdempotencyKeys() {}

  /**
   * Instruction key: SHA-256 of {@code clientId + "|" + instructionRef}, lower-case hex. The same
   * reference from the same client always yields the same key, whatever file it arrives in.
   *
   * @param clientId client identifier from the file name
   * @param instructionRef the row's instruction_ref
   * @return 64 character lower-case hex digest
   */
  public static String forInstruction(String clientId, String instructionRef) {
    Objects.requireNonNull(clientId, "clientId");
    Objects.requireNonNull(instructionRef, "instructionRef");
    return sha256Hex((clientId + SEPARATOR + instructionRef).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * File key: SHA-256 of the raw file bytes, lower-case hex.
   *
   * @param content file content
   * @return 64 character lower-case hex digest
   */
  public static String forFile(byte[] content) {
    Objects.requireNonNull(content, "content");
    return sha256Hex(content);
  }

  /**
   * Creates a new SHA-256 digest, for callers that hash large content incrementally.
   *
   * @return a fresh digest instance
   */
  public static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance(ALGORITHM);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
  }

  /**
   * Formats a finished digest as lower-case hex.
   *
   * @param digest digest bytes
   * @return lower-case hex string
   */
  public static String toHex(byte[] digest) {
    return HexFormat.of().formatHex(digest);
  }

  private static String sha256Hex(byte[] bytes) {
    return toHex(newDigest().digest(bytes));
  }
}
