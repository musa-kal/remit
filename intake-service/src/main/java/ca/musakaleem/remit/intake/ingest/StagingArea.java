package ca.musakaleem.remit.intake.ingest;

import ca.musakaleem.remit.intake.batch.FileHashing;
import ca.musakaleem.remit.intake.config.IntakeProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Local directory where downloaded files wait while their job runs. Each file lives under a
 * directory named after its content hash, so two different files with the same name never overwrite
 * each other and a failed job's input stays untouched until it is restarted.
 */
@Component
public class StagingArea {

  private final Path root;

  /**
   * Creates the staging area.
   *
   * @param properties provides {@code remit.intake.staging-dir}
   */
  public StagingArea(IntakeProperties properties) {
    this.root = properties.stagingDir().toAbsolutePath().normalize();
  }

  /**
   * Downloads a file into the staging area and hashes it.
   *
   * @param fileName file name
   * @param download writes the file content to the given path
   * @return the staged file
   */
  public StagedFile stage(String fileName, Consumer<Path> download) {
    try {
      Files.createDirectories(root);
      Path part = root.resolve(fileName + ".part");
      download.accept(part);
      String hash = FileHashing.sha256(part);
      Path directory = Files.createDirectories(root.resolve(hash));
      Path target = directory.resolve(fileName);
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
      return new StagedFile(fileName, hash, target);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot stage " + fileName, e);
    }
  }

  /**
   * Deletes a staged file and its hash directory once the job no longer needs it.
   *
   * @param stagedPath path returned by {@link #stage}
   */
  public void discard(Path stagedPath) {
    try {
      Files.deleteIfExists(stagedPath);
      Path directory = stagedPath.getParent();
      if (directory != null && !directory.equals(root)) {
        Files.deleteIfExists(directory);
      }
    } catch (DirectoryNotEmptyException e) {
      // Another file with the same content is staged; leave the directory.
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot discard " + stagedPath, e);
    }
  }

  /**
   * Returns the staging root.
   *
   * @return absolute path
   */
  public Path root() {
    return root;
  }
}
