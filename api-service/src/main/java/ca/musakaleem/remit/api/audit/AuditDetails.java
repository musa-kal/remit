package ca.musakaleem.remit.api.audit;

import ca.musakaleem.remit.common.MaskingUtil;
import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Makes audit details safe to store: a value under a key containing "account" is masked to its last
 * four characters, account-like digit runs inside any other text are masked, null values are
 * dropped, and numbers and times become plain strings so the document reads the same everywhere.
 */
public final class AuditDetails {

  private AuditDetails() {}

  /**
   * Returns a sanitized copy.
   *
   * @param details raw details, may be {@code null}
   * @return a new map in the same key order
   */
  public static Map<String, Object> sanitize(Map<String, ?> details) {
    Map<String, Object> safe = new LinkedHashMap<>();
    if (details == null) {
      return safe;
    }
    details.forEach(
        (key, value) -> {
          Object clean = clean(key, value);
          if (clean != null) {
            safe.put(key, clean);
          }
        });
    return safe;
  }

  private static Object clean(String key, Object value) {
    if (value == null) {
      return null;
    }
    boolean account = key != null && key.toLowerCase(Locale.ROOT).contains("account");
    if (value instanceof Map<?, ?> nested) {
      Map<String, Object> copy = new LinkedHashMap<>();
      nested.forEach((k, v) -> copy.put(String.valueOf(k), v));
      return sanitize(copy);
    }
    if (value instanceof Collection<?> items) {
      List<Object> copy = new ArrayList<>();
      items.forEach(item -> copy.add(clean(key, item)));
      return copy;
    }
    if (value instanceof BigDecimal amount) {
      return amount.toPlainString();
    }
    if (value instanceof TemporalAccessor || value instanceof Enum<?>) {
      return value.toString();
    }
    if (value instanceof Number || value instanceof Boolean) {
      return account ? MaskingUtil.maskAccount(value.toString()) : value;
    }
    String text = value.toString();
    return account ? MaskingUtil.maskAccount(text) : MaskingUtil.maskAccountNumbers(text);
  }
}
