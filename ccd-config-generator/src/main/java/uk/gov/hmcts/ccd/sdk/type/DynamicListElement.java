package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * An element of the {@link DynamicList}.
 *
 * <p>There are two properties which map to the relevant items of an option html tag.
 */
@NoArgsConstructor
@Builder
@Data
@ComplexType(generate = false)
public class DynamicListElement {
  public static final String DEFAULT_CODE = "-1";
  public static final String DEFAULT_LABEL = "";
  public static final DynamicListElement EMPTY = DynamicListElement.builder().build();

  /**
   * Property that maps to the value attribute of the option tag.
   */
  private UUID code;

  /**
   * Property that maps to the label attribute of the option tag.
   */
  private String label;

  @JsonCreator
  public DynamicListElement(
      @JsonProperty("code") UUID code,
      @JsonProperty("label") String label
  ) {
    this.code = code;
    this.label = label;
  }
}
