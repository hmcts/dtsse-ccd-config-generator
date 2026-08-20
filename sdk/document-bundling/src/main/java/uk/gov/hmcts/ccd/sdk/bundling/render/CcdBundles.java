package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.CcdBundle;
import uk.gov.hmcts.ccd.sdk.bundling.api.CcdBundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.CcdBundleFolder;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;
import uk.gov.hmcts.ccd.sdk.bundling.api.StoredBundle;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;

/**
 * Step 9's output mapping: the {@code CcdBundleDTO}-compatible {@link CcdBundle} the consumer
 * attaches to its case.
 *
 * <p>Population choices, pinned by tests:
 *
 * <ul>
 * <li>{@code id} is the request's {@code externalId}; {@code title} and {@code fileName} echo
 * the request; {@code stitchStatus} is {@code "DONE"} (the current services' terminal value) and
 * {@code dateAndTime} is generation completion time.
 * <li>{@code documents} echoes the root section's direct documents (outside any folder);
 * {@code folders} echoes the root's child sections recursively — the same split the assembly
 * renders. {@code sortIndex} is the position within its containing list.
 * <li>{@code hasTableOfContents}/{@code hasCoversheets}/{@code hasFolderCoversheets} echo the
 * presentation (document cover sheets map to {@code hasCoversheets}, section cover sheets to
 * {@code hasFolderCoversheets}, matching the current services' field meanings).
 * <li>{@code paginationStyle} carries the wire position values from em-stitching's
 * {@code PaginationStyle} enum: {@code off}, {@code bottomCenter}, {@code bottomRight},
 * {@code topRight}. {@code pageNumberFormat} carries em-stitching's {@code PageNumberFormat}
 * wire values, where the mapping is a judgement call because the models differ: em-stitching's
 * format chooses the table-of-contents page column ({@code numberOfPages} prints each entry's
 * total pages, {@code pageRange} prints its start–end range), while this module's
 * {@link PageNumbers} presets choose the page stamp ({@code N} versus {@code N of M}). The
 * mapping used: {@code N of M} presets → {@code numberOfPages} (the variant whose meaning is
 * "surface page totals"), plain {@code N} presets and {@code NONE} → {@code pageRange} (the
 * variant whose meaning is "surface page positions"; also the closest description of this
 * module's contents column, which prints each entry's start page).
 * </ul>
 */
final class CcdBundles {

  private CcdBundles() {
  }

  /**
   * Builds the bundle output.
   *
   * @param request the rendered request
   * @param stored the stored artifact's links
   * @param completedAt when generation completed
   * @return the CCD-shaped bundle
   */
  static CcdBundle build(BundleRequest request, StoredBundle stored, LocalDateTime completedAt) {
    BundlePresentation presentation = request.presentation();
    return CcdBundle.builder()
        .id(request.externalId().toString())
        .title(request.title())
        .fileName(request.fileName())
        .stitchedDocument(stored.toDocument())
        .documents(mapDocuments(request.root().documents()))
        .folders(mapFolders(request.root().sections()))
        .hasTableOfContents(YesOrNo.from(presentation.tableOfContents()))
        .hasCoversheets(YesOrNo.from(presentation.documentCoverSheets()))
        .hasFolderCoversheets(YesOrNo.from(presentation.sectionCoverSheets()))
        .paginationStyle(paginationStyle(presentation.pageNumbers()))
        .pageNumberFormat(pageNumberFormat(presentation.pageNumbers()))
        .stitchStatus("DONE")
        .dateAndTime(completedAt)
        .build();
  }

  private static List<ListValue<CcdBundleDocument>> mapDocuments(List<BundleDocument> documents) {
    List<ListValue<CcdBundleDocument>> values = new ArrayList<>();
    for (int i = 0; i < documents.size(); i++) {
      BundleDocument document = documents.get(i);
      values.add(new ListValue<>(String.valueOf(i + 1), CcdBundleDocument.builder()
          .name(document.title())
          .sortIndex(i)
          .sourceDocument(sourceDocumentFor(document.reference()))
          .build()));
    }
    return values;
  }

  private static List<ListValue<CcdBundleFolder>> mapFolders(List<BundleSection> sections) {
    List<ListValue<CcdBundleFolder>> values = new ArrayList<>();
    for (int i = 0; i < sections.size(); i++) {
      BundleSection section = sections.get(i);
      values.add(new ListValue<>(String.valueOf(i + 1), CcdBundleFolder.builder()
          .name(section.title())
          .sortIndex(i)
          .documents(mapDocuments(section.documents()))
          .folders(mapFolders(section.sections()))
          .build()));
    }
    return values;
  }

  private static Document sourceDocumentFor(DocumentReference reference) {
    // TODO(document-bundling): when the reference is CDAM-shaped (provider "cdam", id a document
    // UUID), the source document's CCD links can be reconstructed so consumers get a populated
    // sourceDocument, as the orchestrator's DTO carries today. That needs the CDAM base URL,
    // which only the cdam package's configuration knows — wire a seam from the builder when the
    // CDAM auto-configuration lands. Until then the echo carries names and order only.
    return null;
  }

  private static String paginationStyle(PageNumbers preset) {
    return switch (preset) {
      case NONE -> "off";
      case BOTTOM_CENTRE_N, BOTTOM_CENTRE_N_OF_M -> "bottomCenter";
      case BOTTOM_RIGHT_N, BOTTOM_RIGHT_N_OF_M -> "bottomRight";
      case TOP_RIGHT_N, TOP_RIGHT_N_OF_M -> "topRight";
    };
  }

  /**
   * The wire value for the page-number format (mapping documented on the class). Caveat: if a
   * consumer echoes an {@code N of M} bundle's output back into a legacy em-stitching re-stitch,
   * the {@code numberOfPages} value renders that service's table-of-contents page column as
   * per-document totals, where for example sptribs' active configuration uses {@code pageRange}.
   *
   * @param preset the requested page-number preset
   * @return the {@code PageNumberFormat} wire value
   */
  private static String pageNumberFormat(PageNumbers preset) {
    return switch (preset) {
      case BOTTOM_CENTRE_N_OF_M, BOTTOM_RIGHT_N_OF_M, TOP_RIGHT_N_OF_M -> "numberOfPages";
      case NONE, BOTTOM_CENTRE_N, BOTTOM_RIGHT_N, TOP_RIGHT_N -> "pageRange";
    };
  }
}
