package ca.musakaleem.remit.intake.sftp;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.sshd.sftp.client.SftpClient;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;

/** {@link SftpFileGateway} on Spring Integration SFTP (Apache MINA SSHD). */
public class SpringIntegrationSftpFileGateway implements SftpFileGateway {

  private final SftpRemoteFileTemplate template;

  /**
   * Creates the gateway.
   *
   * @param template template over the caching session factory
   */
  public SpringIntegrationSftpFileGateway(SftpRemoteFileTemplate template) {
    this.template = template;
  }

  @Override
  public List<String> listCsvFiles(String directory) {
    SftpClient.DirEntry[] entries = template.execute(session -> session.list(directory));
    return Arrays.stream(entries)
        .filter(entry -> entry.getAttributes().isRegularFile())
        .map(SftpClient.DirEntry::getFilename)
        .filter(name -> name.endsWith(".csv"))
        .sorted()
        .toList();
  }

  @Override
  public void download(String directory, String fileName, Path target) {
    template.execute(
        session -> {
          try (OutputStream out = Files.newOutputStream(target)) {
            session.read(path(directory, fileName), out);
          }
          return null;
        });
  }

  @Override
  public void move(String fromDirectory, String toDirectory, String fileName) {
    String from = path(fromDirectory, fileName);
    String to = path(toDirectory, fileName);
    template.execute(
        session -> {
          // SFTP v3 rename fails when the target exists, so clear it first.
          if (session.exists(to)) {
            session.remove(to);
          }
          session.rename(from, to);
          return null;
        });
  }

  @Override
  public void writeText(String directory, String fileName, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    template.execute(
        session -> {
          session.write(new ByteArrayInputStream(bytes), path(directory, fileName));
          return null;
        });
  }

  static String path(String directory, String fileName) {
    if (fileName.contains("/") || fileName.contains("\\")) {
      throw new IllegalArgumentException("Unsafe remote file name: " + fileName);
    }
    return directory.endsWith("/") ? directory + fileName : directory + "/" + fileName;
  }
}
