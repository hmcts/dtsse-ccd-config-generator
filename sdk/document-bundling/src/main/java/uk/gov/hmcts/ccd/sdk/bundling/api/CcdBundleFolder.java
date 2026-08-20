package uk.gov.hmcts.ccd.sdk.bundling.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.type.ListValue;

/**
 * One folder entry in a {@link CcdBundle}, JSON-compatible with the orchestrator's
 * {@code CcdBundleFolderDTO}. Folders nest and carry their documents in render order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CcdBundleFolder {

  private String name;

  private List<ListValue<CcdBundleDocument>> documents;

  private List<ListValue<CcdBundleFolder>> folders;

  private int sortIndex;
}
