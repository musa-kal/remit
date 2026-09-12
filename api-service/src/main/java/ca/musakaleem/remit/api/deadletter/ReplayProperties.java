package ca.musakaleem.remit.api.deadletter;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Replay settings bound from {@code remit.replay}.
 *
 * @param maxReplays how often one dead letter may be replayed (spec 7.3: 3)
 * @param publishTimeout how long to wait for Kafka to acknowledge a replay
 */
@ConfigurationProperties("remit.replay")
public record ReplayProperties(
    @DefaultValue("3") int maxReplays, @DefaultValue("10s") Duration publishTimeout) {}
