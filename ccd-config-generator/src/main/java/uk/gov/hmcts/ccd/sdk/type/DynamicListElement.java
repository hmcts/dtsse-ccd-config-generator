package uk.gov.hmcts.ccd.sdk.type;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * An element of the {@link DynamicList}.
 *
 * <p>There are two properties which map to the relevant items of an option html tag.
 */
@AllArgsConstructor
@Builder
@Data
@Jacksonized
@ComplexType(generate = false)
public class DynamicListElement {
  public static final String DEFAULT_CODE = "-1";
  public static final String DEFAULT_LABEL = "";
  public static final DynamicListElement EMPTY = DynamicListElement.builder().build();

  /**
   * Property that maps to the value attribute of the option tag.
   */
  private final UUID code;

  /**
   * Property that maps to the label attribute of the option tag.
   */
  private final String label;
}
