package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * Representation of a CCD Dynamic List which is then converted to a select dropdown list.
 */
@NoArgsConstructor
@Builder
@Data
@ComplexType(generate = false)
@JsonIgnoreProperties(ignoreUnknown = true)
@Jacksonized
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

  public static <T extends DynamicElementIndicator> DynamicMultiSelectList toDynamicMultiSelectList(
      List<T> elements,
      List<DynamicListElement> selected) {
    List<DynamicListElement> items = elements.stream()
        .map(DynamicElementIndicator::toDynamicElement).collect(Collectors.toList());
    return DynamicMultiSelectList.builder().listItems(items).value(selected).build();
  }

  @JsonIgnore
  public String getValueLabel() {
    return value == null ? null : value.toString();
  }

  @JsonIgnore
  public UUID getValueCodeAsUuid() {
    return Optional.ofNullable(getValueCode()).map(UUID::fromString).orElse(null);
  }

  @JsonIgnore
  public String getValueCode() {
    return value == null ? null : value.toString();
  }
}
