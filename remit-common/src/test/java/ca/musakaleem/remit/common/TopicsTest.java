package ca.musakaleem.remit.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TopicsTest {

  @Test
  void listsAllEightDistinctTopics() {
    assertThat(Topics.all()).hasSize(8).doesNotHaveDuplicates();
  }

  @Test
  void deadLetterTopicUsesSpringKafkaDefaultSuffix() {
    assertThat(Topics.INSTRUCTIONS_DLT).isEqualTo(Topics.INSTRUCTIONS + ".DLT");
  }

  @Test
  void everyTopicSharesThePaymentsPrefix() {
    assertThat(Topics.all()).allSatisfy(topic -> assertThat(topic).startsWith("payments."));
  }
}
