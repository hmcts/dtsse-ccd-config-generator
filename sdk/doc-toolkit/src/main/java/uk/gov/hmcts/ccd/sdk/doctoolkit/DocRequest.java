package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.Optional;

/**
 * What to render: a {@link BundleRequest} (sections, contents, page numbers) or a
 * {@link DocConversionRequest} (one or more documents, joined in order).
 */
public sealed interface DocRequest permits BundleRequest, DocConversionRequest {

  /** The file name of the result. */
  String fileName();

  /** The title written to the PDF. */
  String title();

  /** The print layout, when the result is for bulk print. */
  Optional<PrintOptions> print();

  /** The watermark on each document's pages. */
  Optional<Watermark> watermark();

  /**
   * A file name for {@code title}: unsafe characters replaced, {@code .pdf} added.
   */
  static String fileNameFor(String title) {
    String base = title.trim().replaceAll("[^A-Za-z0-9._ -]", "").replaceAll("\\s+", "-");
    return (base.isEmpty() ? "document" : base) + ".pdf";
  }
}
