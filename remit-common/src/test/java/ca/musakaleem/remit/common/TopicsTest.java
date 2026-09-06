package ca.musakaleem.remit.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import ca.musakaleem.remit.common.event.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TopicsTest {

  @Test
  void listsAllEightDistinctTopics() {
    assertThat(Topics.all()).hasSize(8).doesNotHaveDuplicates();
  }

  @Test
  void deadLetterTopicAppendsDotDltToTheSourceTopic() {
    // Spring Kafka 3.3 defaults to a "-dlt" suffix, so routing-service names this topic itself.
    assertThat(Topics.INSTRUCTIONS_DLT).isEqualTo(Topics.INSTRUCTIONS + ".DLT");
  }

  @Test
  void everyTopicSharesThePaymentsPrefix() {
    assertThat(Topics.all()).allSatisfy(topic -> assertThat(topic).startsWith("payments."));
  }

  @ParameterizedTest
  @CsvSource({
    "EFT, payments.routed.eft",
    "WIRE, payments.routed.wire",
    "INTERAC, payments.routed.interac"
  })
  void mapsEachChannelToItsRoutedTopic(Channel channel, String topic) {
    assertThat(Topics.routed(channel)).isEqualTo(topic);
  }

  @Test
  void routedRejectsAMissingChannel() {
    assertThatNullPointerException().isThrownBy(() -> Topics.routed(null));
  }
}
