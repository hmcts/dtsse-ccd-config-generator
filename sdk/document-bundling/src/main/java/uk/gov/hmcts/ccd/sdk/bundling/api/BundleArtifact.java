package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.io.IOException;
import java.io.InputStream;

/**
 * The finished, validated bundle handed to a {@link BundleDestination} for publication.
 */
public interface BundleArtifact {

  /**
   * The output file name from the request.
   *
   * @return the file name
   */
  String fileName();

  /**
   * The artifact media type, always {@code application/pdf}.
   *
   * @return the media type
   */
  String mediaType();

  /**
   * The artifact size in bytes.
   *
   * @return the size in bytes
   */
  long size();

  /**
   * The SHA-256 checksum of the artifact, also recorded in the generation report.
   *
   * @return the hex-encoded checksum
   */
  String sha256();

  /**
   * The total page count of the artifact.
   *
   * @return the page count
   */
  int pageCount();

  /**
   * Opens the artifact content for reading. May be called more than once; each call returns a
   * fresh stream the caller must close.
   *
   * @return a fresh content stream
   * @throws IOException if the artifact cannot be opened
   */
  InputStream open() throws IOException;
}
