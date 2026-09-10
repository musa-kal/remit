package ca.musakaleem.remit.api.web;

/**
 * One entry of the {@code errors} list of a 400 ProblemDetail.
 *
 * @param field request body field or query parameter
 * @param message what is wrong with it
 */
public record FieldError(String field, String message) {}
