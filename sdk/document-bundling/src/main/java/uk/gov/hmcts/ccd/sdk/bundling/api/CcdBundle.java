package uk.gov.hmcts.ccd.sdk.bundling.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;

/**
 * The render output format: the platform-standard bundle shape, JSON-compatible with
 * {@code em-ccd-orchestrator}'s {@code CcdBundleDTO} and with the bundle models consumer services
 * already hold in their {@code caseBundles} case fields (for example {@code sptribs-case-api}'s
 * {@code Bundle}). All parties keep the contract tolerant with
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}, so a consumer can attach this type
 * directly or convert it into its existing bundle model with Jackson and change nothing else —
 * including the XUI bundle presentation, which already reads this shape.
 *
 * <p>{@code paginationStyle} and {@code pageNumberFormat} carry the wire values the current
 * services exchange (for example {@code off}, {@code bottomCenter}; {@code numberOfPages},
 * {@code pageRange}); the SDK populates them from the requested {@link BundlePresentation}.
 *
 * <p>This is a JSON wire shape, not an importable CCD complex type: definition stores reject the
 * recursive folder type, so case definitions keep their own bounded bundle model (as
 * {@code sptribs-case-api} does) and persist this object's JSON into it — the consumer usage
 * document shows the pattern.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CcdBundle {

  private String id;

  private String title;

  private String description;

  /** The completed PDF in CDAM, as the standard CCD document type. */
  private Document stitchedDocument;

  /** Documents outside any folder, in render order. */
  private List<ListValue<CcdBundleDocument>> documents;

  /** Top-level folders, in render order. */
  private List<ListValue<CcdBundleFolder>> folders;

  private String fileName;

  private String fileNameIdentifier;

  private String coverpageTemplate;

  private YesOrNo hasTableOfContents;

  private YesOrNo hasCoversheets;

  private YesOrNo hasFolderCoversheets;

  /**
   * The terminal status the current services publish, for example {@code DONE}.
   */
  private String stitchStatus;

  private String paginationStyle;

  private String pageNumberFormat;

  private String stitchingFailureMessage;

  private YesOrNo eligibleForStitching;

  private YesOrNo eligibleForCloning;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private YesOrNo enableEmailNotification;

  /** When generation completed. */
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
  private LocalDateTime dateAndTime;
}
