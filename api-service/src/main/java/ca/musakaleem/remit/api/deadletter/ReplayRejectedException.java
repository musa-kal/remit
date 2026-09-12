package ca.musakaleem.remit.api.deadletter;

import ca.musakaleem.remit.api.web.ConflictException;

/** A replay the dead letter's state does not allow; answered with 409. */
public class ReplayRejectedException extends ConflictException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param detail why the replay is refused
   */
  public ReplayRejectedException(String detail) {
    super(detail);
  }
}
