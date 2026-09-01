package ca.musakaleem.remit.common.event;

/** Lifecycle status of an ingested batch file. */
public enum BatchStatus {
  RECEIVED,
  COMPLETED,
  COMPLETED_WITH_ERRORS,
  FAILED
}
