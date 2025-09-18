package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * Representation of a CCD Dynamic List which is then converted to a select dropdown list.
 */
@NoArgsConstructor
@Builder
@Data
@ComplexType(generate = false)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicMultiSelectList {

  /**
   * The selected value for the multiselect options.
   */
  @JsonProperty("value")
  private List<DynamicListElement> value;

  /**
   * List of options for the multiselect options.
   */
  @JsonProperty("list_items")
  private List<DynamicListElement> listItems;

  @JsonCreator
  public DynamicMultiSelectList(
      @JsonProperty("value")  List<DynamicListElement>  value,
      @JsonProperty("list_items") List<DynamicListElement> listItems
  ) {
    this.value = value;
    this.listItems = listItems;
  }

  /**
   * Converts a list of elements to the appropriate structure to then be handled correctly by CCD.
   *
   * @param elements elements to convert into options for the dropdown
   * @param selected a {@link DynamicListElement} that will be the default selected element of the list
   * @param <T>      a class that implements {@link DynamicElementIndicator#toDynamicElement()}
   * @return a {@link DynamicMultiSelectList} to be sent to CCD
   */
  public static <T extends DynamicElementIndicator> DynamicMultiSelectList toDynamicMultiSelectList(
      List<T> elements,
      List<DynamicListElement> selected) {
    List<DynamicListElement> items = elements.stream()
        .map(DynamicElementIndicator::toDynamicElement).collect(Collectors.toList());
    return DynamicMultiSelectList.builder().listItems(items).value(selected).build();
  }

  public String getValueLabel(DynamicListElement element) {
    return element == null ? null : element.getLabel();
  }

  public UUID getValueCode(DynamicListElement element) {
    return element == null ? null : element.getCode();
  }

}
