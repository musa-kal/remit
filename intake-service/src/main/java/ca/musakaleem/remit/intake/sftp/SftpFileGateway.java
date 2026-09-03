package ca.musakaleem.remit.intake.sftp;

import java.nio.file.Path;
import java.util.List;

/** The few SFTP operations intake needs, behind an interface so tests can replace them. */
public interface SftpFileGateway {

  /**
   * Lists regular files ending in {@code .csv}, sorted by name (names embed a timestamp, so this is
   * also arrival order for well-named files).
   *
   * @param directory remote directory
   * @return file names without directory
   */
  List<String> listCsvFiles(String directory);

  /**
   * Downloads a remote file, replacing the local target.
   *
   * @param directory remote directory
   * @param fileName remote file name
   * @param target local path to write
   */
  void download(String directory, String fileName, Path target);

  /**
   * Moves a remote file to another directory, replacing a file of the same name there.
   *
   * @param fromDirectory current directory
   * @param toDirectory target directory
   * @param fileName file name, kept unchanged
   */
  void move(String fromDirectory, String toDirectory, String fileName);

  /**
   * Writes a small UTF-8 text file, replacing any existing file.
   *
   * @param directory remote directory
   * @param fileName file name
   * @param content text content
   */
  void writeText(String directory, String fileName, String content);
}
