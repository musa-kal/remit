package ca.musakaleem.remit.routing.support;

import ca.musakaleem.remit.common.event.EventHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Reads a whole topic from the beginning up to its current end offsets, with raw string values (a
 * dead-lettered poison pill is not JSON). Tests combine it with Awaitility to wait for the
 * asynchronous consumer instead of sleeping.
 */
public final class TopicReader {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private TopicReader() {}

  /**
   * Returns the records of a topic with the given key.
   *
   * @param topic topic name
   * @param key record key
   * @return matching records in partition then offset order
   */
  public static List<ConsumerRecord<String, String>> byKey(String topic, String key) {
    return readAll(topic).stream().filter(r -> Objects.equals(r.key(), key)).toList();
  }

  /**
   * Returns the records of a topic whose correlation id header matches.
   *
   * @param topic topic name
   * @param correlationId batch id
   * @return matching records in partition then offset order
   */
  public static List<ConsumerRecord<String, String>> byCorrelationId(
      String topic, String correlationId) {
    return readAll(topic).stream()
        .filter(
            r ->
                Objects.equals(
                    EventHeaders.lastValue(r.headers(), EventHeaders.CORRELATION_ID).orElse(null),
                    correlationId))
        .toList();
  }

  /**
   * Returns every record currently in a topic.
   *
   * @param topic topic name
   * @return all records
   */
  public static List<ConsumerRecord<String, String>> readAll(String topic) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            RoutingContainers.KAFKA.getBootstrapServers(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
            false);
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic).stream()
              .map(p -> new TopicPartition(p.topic(), p.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      List<ConsumerRecord<String, String>> records = new ArrayList<>();
      Instant deadline = Instant.now().plus(TIMEOUT);
      while (!caughtUp(consumer, end)) {
        if (Instant.now().isAfter(deadline)) {
          throw new IllegalStateException("Timed out reading " + topic);
        }
        consumer.poll(Duration.ofMillis(200)).forEach(records::add);
      }
      return records;
    }
  }

  private static boolean caughtUp(
      KafkaConsumer<String, String> consumer, Map<TopicPartition, Long> end) {
    return end.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue());
  }
}
