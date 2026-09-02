package ca.musakaleem.remit.intake.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * SFTP drop zone settings bound from {@code remit.sftp}. Credentials come from environment
 * variables only (FR-INT-01).
 *
 * @param host SFTP host
 * @param port SFTP port
 * @param user login user
 * @param password login password
 * @param pollInterval delay between two polls of the inbound directory
 * @param pollerEnabled whether the scheduled poller runs
 * @param allowUnknownKeys accept any host key; the local SFTP container regenerates its key
 * @param inboundDir directory clients drop files into
 * @param processingDir directory a file sits in while its job runs
 * @param processedDir directory for files whose batch completed
 * @param errorDir directory for rejected files and their {@code .error.txt} notes
 */
@Validated
@ConfigurationProperties("remit.sftp")
public record SftpProperties(
    @NotBlank String host,
    @DefaultValue("22") int port,
    @NotBlank String user,
    @NotBlank String password,
    @DefaultValue("30s") @NotNull Duration pollInterval,
    @DefaultValue("true") boolean pollerEnabled,
    @DefaultValue("false") boolean allowUnknownKeys,
    @DefaultValue("/inbound") @NotBlank String inboundDir,
    @DefaultValue("/processing") @NotBlank String processingDir,
    @DefaultValue("/processed") @NotBlank String processedDir,
    @DefaultValue("/error") @NotBlank String errorDir) {

  /** Hides the password from logs and actuator output. */
  @Override
  public String toString() {
    return "SftpProperties[host=%s, port=%d, user=%s, password=****, pollInterval=%s]"
        .formatted(host, port, user, pollInterval);
  }
}
