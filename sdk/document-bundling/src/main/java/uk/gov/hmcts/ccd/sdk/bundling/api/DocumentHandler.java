package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Produces the PDF representation of one resolved source document for one media type.
 *
 * <p>Handlers receive bounded services through the {@link HandlerContext}, not raw access to the
 * assembler, so a custom handler cannot break bundle-wide invariants. A handler must not mutate
 * the source.
 */
@FunctionalInterface
public interface DocumentHandler {

  /** Produces the PDF representation of one resolved source document. */
  HandledDocument handle(ResolvedDocument source, HandlerContext context)
      throws DocumentHandlingException;
}
