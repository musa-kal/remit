package ca.musakaleem.remit.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IdempotencyKeysTest {

  // Known vectors computed independently with: printf 'ACME01|INV-1' | sha256sum
  @ParameterizedTest(name = "{0}|{1}")
  @CsvSource({
    "ACME01, INV-1, 61586de4c2c1f17fab819abcdd93b1b38b4a3d1e386f1aede8150dbb4d6b4535",
    "BETA02, INV-1, 36943384a73fe1510afa61e8f1dcea7bb0a70ea8947d975f49e553eb6305ed62",
    "ACME01, ACME-0001, 35999b8c0e7bd8a776787238321233c0f22f889e29496c1e9cf32cdbbd19d8ae",
  })
  void instructionKeyMatchesKnownVectors(String clientId, String ref, String expected) {
    assertThat(IdempotencyKeys.forInstruction(clientId, ref)).isEqualTo(expected);
  }

  @Test
  void instructionKeyIsScopedToTheClient() {
    assertThat(IdempotencyKeys.forInstruction("ACME01", "INV-1"))
        .isNotEqualTo(IdempotencyKeys.forInstruction("BETA02", "INV-1"));
  }

  @Test
  void instructionKeyUsesTheSeparatorSoFieldsCannotBleedTogether() {
    assertThat(IdempotencyKeys.forInstruction("ACME0", "1INV"))
        .isNotEqualTo(IdempotencyKeys.forInstruction("ACME01", "INV"));
  }

  @Test
  void instructionKeyIsLowerCaseHexOf64Chars() {
    assertThat(IdempotencyKeys.forInstruction("ACME01", "X")).matches("[0-9a-f]{64}");
  }

  @Test
  void fileKeyMatchesKnownVectors() {
    assertThat(IdempotencyKeys.forFile(new byte[0]))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    assertThat(IdempotencyKeys.forFile("abc".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void incrementalDigestMatchesOneShotFileKey() {
    MessageDigest digest = IdempotencyKeys.newDigest();
    digest.update("a".getBytes(StandardCharsets.US_ASCII));
    digest.update("bc".getBytes(StandardCharsets.US_ASCII));

    assertThat(IdempotencyKeys.toHex(digest.digest()))
        .isEqualTo(IdempotencyKeys.forFile("abc".getBytes(StandardCharsets.US_ASCII)));
  }

  @Test
  void rejectsNullInputs() {
    assertThatNullPointerException().isThrownBy(() -> IdempotencyKeys.forInstruction(null, "r"));
    assertThatNullPointerException().isThrownBy(() -> IdempotencyKeys.forInstruction("c", null));
    assertThatNullPointerException().isThrownBy(() -> IdempotencyKeys.forFile(null));
  }
}
