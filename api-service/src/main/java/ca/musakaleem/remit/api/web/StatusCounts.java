package ca.musakaleem.remit.api.web;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns {@code select status, count(...) group by status} rows into a map with every status. */
public final class StatusCounts {

  private StatusCounts() {}

  /**
   * Builds a map listing every constant of the enum, zero when absent from the rows.
   *
   * @param type status enum
   * @param rows rows of {@code [status, count]}
   * @param <E> status enum
   * @return counts in declaration order
   */
  public static <E extends Enum<E>> Map<E, Long> of(Class<E> type, List<Object[]> rows) {
    Map<E, Long> counts = new EnumMap<>(type);
    for (E constant : type.getEnumConstants()) {
      counts.put(constant, 0L);
    }
    for (Object[] row : rows) {
      counts.put(type.cast(row[0]), ((Number) row[1]).longValue());
    }
    return new LinkedHashMap<>(counts);
  }
}
