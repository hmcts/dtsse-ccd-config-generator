package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One successfully resolved source document. The SDK closes it after spooling its content to a
 * job-scoped temporary file.
 */
public interface ResolvedDocument extends AutoCloseable {

  /**
   * The document content. Read once by the SDK; implementations need not support re-reading.
   *
   * @return the content stream
   */
  InputStream content();

  /**
   * The declared media type, validated against content-based detection by the pipeline.
   *
   * @return the media type
   */
  String mediaType();

  /**
   * The source file name.
   *
   * @return the file name
   */
  String fileName();

  /**
   * The declared content length in bytes, when known, used to enforce size limits before
   * reading.
   *
   * @return the optional content length
   */
  OptionalLong contentLength();

  /**
   * The provider's checksum of the content, when known, recorded in the generation report.
   *
   * @return the optional checksum
   */
  Optional<String> checksum();

  @Override
  void close() throws IOException;
}
