package ca.musakaleem.remit.api.web;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Checks for query parameters that Bean Validation cannot express: allowed sort properties and
 * ranges. Page sizes above 100 need no check here; Spring clamps them to 100 ({@code
 * spring.data.web.pageable.max-page-size}).
 */
public final class Requests {

  private Requests() {}

  /**
   * Refuses a sort on a property that is not in the list.
   *
   * @param pageable requested page
   * @param allowed sortable properties
   * @return the same pageable
   * @throws InvalidRequestException for any other property
   */
  public static Pageable sortableBy(Pageable pageable, Set<String> allowed) {
    for (Sort.Order order : pageable.getSort()) {
      if (!allowed.contains(order.getProperty())) {
        throw new InvalidRequestException(
            "sort",
            "cannot sort by '"
                + order.getProperty()
                + "'; allowed: "
                + String.join(", ", new TreeSet<>(allowed)));
      }
    }
    return pageable;
  }

  /**
   * Refuses a range whose start is not before its end.
   *
   * @param from start, inclusive, may be {@code null}
   * @param to end, exclusive, may be {@code null}
   * @throws InvalidRequestException when both are set and from is not before to
   */
  public static void range(Instant from, Instant to) {
    if (from != null && to != null && !from.isBefore(to)) {
      throw new InvalidRequestException("from", "must be before 'to'");
    }
  }
}
