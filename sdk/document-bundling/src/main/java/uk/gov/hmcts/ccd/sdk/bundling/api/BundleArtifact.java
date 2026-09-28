package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.io.IOException;
import java.io.InputStream;

/**
 * The finished, validated bundle PDF, readable until its {@link BundleResult} is closed.
 */
public interface BundleArtifact {

  /** The output file name from the request. */
  String fileName();

  /**
   * The artifact media type, always {@code application/pdf}.
   */
  String mediaType();

  /** The artifact size in bytes. */
  long size();

  /** The SHA-256 checksum of the artifact, also recorded in the generation report. */
  String sha256();

  /** The total page count of the artifact. */
  int pageCount();

  /**
   * Opens the artifact content for reading. May be called more than once; each call returns a
   * fresh stream the caller must close.
   */
  InputStream open() throws IOException;
}
