package ca.musakaleem.remit.intake.support;

import ca.musakaleem.remit.common.KafkaJson;
import ca.musakaleem.remit.common.RemitJson;
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
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * Reads a whole topic from the beginning up to its current end offsets and returns the records of
 * one batch (matched on the {@code correlationId} header). Because the job waits for every publish
 * to be acknowledged, the end offsets taken after the job cover everything it published.
 */
public final class TopicReader {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private TopicReader() {}

  /**
   * Returns the records of a topic whose correlation id header matches.
   *
   * @param topic topic name
   * @param correlationId batch id
   * @return matching records in partition then offset order
   */
  public static List<ConsumerRecord<String, Object>> read(String topic, String correlationId) {
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
  public static List<ConsumerRecord<String, Object>> readAll(String topic) {
    JsonDeserializer<Object> values =
        new JsonDeserializer<>(Object.class, RemitJson.objectMapper());
    values.addTrustedPackages(KafkaJson.TRUSTED_PACKAGES);
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            IntakeContainers.KAFKA.getBootstrapServers(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
            false);
    try (KafkaConsumer<String, Object> consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), values)) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic).stream()
              .map(p -> new TopicPartition(p.topic(), p.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      List<ConsumerRecord<String, Object>> records = new ArrayList<>();
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
      KafkaConsumer<String, Object> consumer, Map<TopicPartition, Long> end) {
    return end.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue());
  }
}
