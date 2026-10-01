package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.InputStream;
import java.util.Optional;
import java.util.OptionalLong;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;

record SyntheticMediaSource(String mediaType, String fileName) implements ResolvedDocument {
  @Override
  public InputStream content() {
    throw new UnsupportedOperationException(
        "Media documents are never fetched; build the generated page from "
            + "HandlerContext.document() metadata instead");
  }

  @Override
  public OptionalLong contentLength() {
    return OptionalLong.empty();
  }

  @Override
  public Optional<String> checksum() {
    return Optional.empty();
  }

  @Override
  public void close() {
    // Nothing to close: there is no content.
  }
}
