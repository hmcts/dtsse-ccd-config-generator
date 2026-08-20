package uk.gov.hmcts.ccd.sdk.bundling.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * One document entry in a {@link CcdBundle}, JSON-compatible with the orchestrator's
 * {@code CcdBundleDocumentDTO}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CcdBundleDocument {

  private String name;

  private String description;

  private int sortIndex;

  /** The source document's CCD links, when the source lives in CDAM. */
  private Document sourceDocument;
}
