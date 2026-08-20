package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.InputStream;
import java.util.Optional;
import java.util.OptionalLong;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;

/**
 * The metadata-only source the pipeline synthesises for a media document, which is never fetched
 * by design: the handler SPI takes a {@link ResolvedDocument}, so media documents flow through
 * the same registry as everything else, but this one has no content — a handler builds the
 * generated page from {@code HandlerContext.document()} instead.
 *
 * @param mediaType the media type declared on the request's media placeholder, normalised
 * @param fileName a safe stand-in name (the document id); there is no source file
 */
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
