package ca.musakaleem.remit.routing.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeadLetterDestinationTest {

  @ParameterizedTest
  @CsvSource({"0, 0", "1, 1", "2, 2", "3, 0", "7, 0"})
  void keepsThePartitionWhenTheDltHasItOtherwiseUsesZero(int source, int expected) {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("payments.instructions", source, 42L, "key", "value");

    TopicPartition destination =
        new DeadLetterDestination(3).apply(record, new IllegalStateException("boom"));

    assertThat(destination.topic()).isEqualTo("payments.instructions.DLT");
    assertThat(destination.partition()).isEqualTo(expected);
  }

  @Test
  void needsAtLeastOnePartition() {
    assertThatIllegalArgumentException().isThrownBy(() -> new DeadLetterDestination(0));
  }
}
