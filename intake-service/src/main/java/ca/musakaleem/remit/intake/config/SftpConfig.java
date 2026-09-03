package ca.musakaleem.remit.intake.config;

import ca.musakaleem.remit.intake.sftp.SftpFileGateway;
import ca.musakaleem.remit.intake.sftp.SpringIntegrationSftpFileGateway;
import org.apache.sshd.sftp.client.SftpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.file.remote.session.CachingSessionFactory;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** SFTP session factory and gateway (FR-INT-01); also turns on the scheduled poller. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SftpConfig {

  private static final int SESSION_CACHE_SIZE = 4;

  /**
   * Password-authenticated session factory, wrapped in a small cache so each poll reuses a
   * connection. Sessions are tested before reuse because the server may have dropped them.
   *
   * @param properties host, port and credentials (credentials from environment variables)
   * @return the caching session factory
   */
  @Bean
  public SessionFactory<SftpClient.DirEntry> sftpSessionFactory(SftpProperties properties) {
    DefaultSftpSessionFactory factory = new DefaultSftpSessionFactory(false);
    factory.setHost(properties.host());
    factory.setPort(properties.port());
    factory.setUser(properties.user());
    factory.setPassword(properties.password());
    factory.setAllowUnknownKeys(properties.allowUnknownKeys());
    CachingSessionFactory<SftpClient.DirEntry> caching =
        new CachingSessionFactory<>(factory, SESSION_CACHE_SIZE);
    caching.setTestSession(true);
    return caching;
  }

  /**
   * Template used by the gateway.
   *
   * @param sftpSessionFactory session factory
   * @return the template
   */
  @Bean
  public SftpRemoteFileTemplate sftpRemoteFileTemplate(
      SessionFactory<SftpClient.DirEntry> sftpSessionFactory) {
    return new SftpRemoteFileTemplate(sftpSessionFactory);
  }

  /**
   * The gateway the poller and job disposition use.
   *
   * @param sftpRemoteFileTemplate template
   * @return the gateway
   */
  @Bean
  public SftpFileGateway sftpFileGateway(SftpRemoteFileTemplate sftpRemoteFileTemplate) {
    return new SpringIntegrationSftpFileGateway(sftpRemoteFileTemplate);
  }
}
