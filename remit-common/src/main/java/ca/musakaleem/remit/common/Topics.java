package ca.musakaleem.remit.common;

import ca.musakaleem.remit.common.event.Channel;
import java.util.List;
import java.util.Objects;

/** Kafka topic names shared by every Remit service (spec section 5). */
public final class Topics {

  public static final String INSTRUCTIONS = "payments.instructions";
  public static final String REJECTED = "payments.rejected";
  public static final String ROUTED_EFT = "payments.routed.eft";
  public static final String ROUTED_WIRE = "payments.routed.wire";
  public static final String ROUTED_INTERAC = "payments.routed.interac";
  public static final String STATUS_EVENTS = "payments.status-events";
  public static final String BATCH_EVENTS = "payments.batch-events";
  public static final String INSTRUCTIONS_DLT = "payments.instructions.DLT";

  private Topics() {}

  /**
   * Returns the topic routing-service publishes an instruction of the given channel to.
   *
   * @param channel payment channel
   * @return {@code payments.routed.eft}, {@code payments.routed.wire} or {@code
   *     payments.routed.interac}
   */
  public static String routed(Channel channel) {
    return switch (Objects.requireNonNull(channel, "channel")) {
      case EFT -> ROUTED_EFT;
      case WIRE -> ROUTED_WIRE;
      case INTERAC -> ROUTED_INTERAC;
    };
  }

  /**
   * Returns every topic name, in the order they appear in the spec.
   *
   * @return immutable list of all topic names
   */
  public static List<String> all() {
    return List.of(
        INSTRUCTIONS,
        REJECTED,
        ROUTED_EFT,
        ROUTED_WIRE,
        ROUTED_INTERAC,
        STATUS_EVENTS,
        BATCH_EVENTS,
        INSTRUCTIONS_DLT);
  }
}
