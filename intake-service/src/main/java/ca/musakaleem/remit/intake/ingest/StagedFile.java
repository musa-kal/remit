package ca.musakaleem.remit.intake.ingest;

import java.nio.file.Path;

/**
 * A file downloaded into the local staging area.
 *
 * @param fileName original file name
 * @param fileHash SHA-256 hex of the content
 * @param path absolute local path, {@code <staging-dir>/<fileHash>/<fileName>}
 */
public record StagedFile(String fileName, String fileHash, Path path) {}
