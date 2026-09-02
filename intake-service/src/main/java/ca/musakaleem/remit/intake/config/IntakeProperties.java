package ca.musakaleem.remit.intake.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Intake job settings bound from {@code remit.intake}.
 *
 * @param stagingDir local directory downloaded files are staged in until their job finishes
 * @param chunkSize rows per chunk transaction in the instruction step
 * @param skipLimit most rejected rows a batch may have before it is FAILED (spec 3.5)
 * @param publishTimeout how long to wait for Kafka to acknowledge a publish
 */
@Validated
@ConfigurationProperties("remit.intake")
public record IntakeProperties(
    @NotNull Path stagingDir,
    @DefaultValue("100") @Min(1) int chunkSize,
    @DefaultValue("1000") @Min(0) int skipLimit,
    @DefaultValue("30s") @NotNull Duration publishTimeout) {}
