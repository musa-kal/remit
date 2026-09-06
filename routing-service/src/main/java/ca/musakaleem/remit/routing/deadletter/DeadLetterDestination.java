package ca.musakaleem.remit.routing.deadletter;

import ca.musakaleem.remit.common.Topics;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;

/**
 * Chooses where a failed record goes: {@code payments.instructions.DLT}, on the same partition
 * number as the source record, or partition 0 when the dead-letter topic has fewer partitions.
 * Spring Kafka's default would be {@code <topic>-dlt}, so the name is resolved here explicitly.
 */
public final class DeadLetterDestination
    implements BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> {

  private final int deadLetterPartitions;

  /**
   * Creates the resolver.
   *
   * @param deadLetterPartitions number of partitions of the dead-letter topic
   */
  public DeadLetterDestination(int deadLetterPartitions) {
    if (deadLetterPartitions < 1) {
      throw new IllegalArgumentException("The dead-letter topic needs at least one partition");
    }
    this.deadLetterPartitions = deadLetterPartitions;
  }

  @Override
  public TopicPartition apply(ConsumerRecord<?, ?> record, Exception exception) {
    int partition = record.partition() < deadLetterPartitions ? record.partition() : 0;
    return new TopicPartition(Topics.INSTRUCTIONS_DLT, partition);
  }
}
