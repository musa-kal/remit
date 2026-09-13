package ca.musakaleem.remit.api.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The page envelope of every list endpoint (spec 7.3).
 *
 * @param content items of this page
 * @param page zero-based page number
 * @param size page size used (at most 100)
 * @param totalElements items across all pages
 * @param totalPages number of pages
 * @param <T> item type
 */
public record PageResponse<T>(
    List<T> content, int page, int size, long totalElements, int totalPages) {

  /**
   * Maps a Spring Data page.
   *
   * @param page the page
   * @param mapper entity to response item
   * @param <E> entity type
   * @param <T> item type
   * @return the envelope
   */
  public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, T> mapper) {
    return new PageResponse<>(
        page.getContent().stream().<T>map(mapper).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
