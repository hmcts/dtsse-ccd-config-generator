package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCDAccessGroup;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

/**
 * The case role of a {@link ChangeOrganisationRequest}, held in the form CCD stores it.
 *
 * <p>CCD defines {@code ChangeOrganisationRequest.CaseRoleId} as a DynamicList whose codes are case
 * roles such as {@code [APPLICANTSOLICITOR]}, so declare the field as
 * {@code ChangeOrganisationRequest<DynamicListCaseRole>}. Only {@code value} and {@code list_items}
 * are read and written, and neither is written when null; the {@link HasRole} view is derived from
 * the selected item.</p>
 *
 * <p>It carries a role stored in case data, not a role a case type declares, so it is not for
 * grants, tab roles or permissions: {@link #getRole()} is null when nothing is selected and
 * {@link #getCaseTypePermissions()} is empty.</p>
 */
@NoArgsConstructor
@Builder
@Data
@ComplexType(generate = false)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DynamicListCaseRole implements HasRole {

  private DynamicListItem value;

  @JsonProperty("list_items")
  private List<DynamicListItem> listItems;

  @JsonCreator
  public DynamicListCaseRole(
      @JsonProperty("value") DynamicListItem value,
      @JsonProperty("list_items") List<DynamicListItem> listItems
  ) {
    this.value = value;
    this.listItems = listItems;
  }

  /**
   * The selected case role, or null when none is selected.
   */
  @JsonIgnore
  @Override
  public String getRole() {
    return value == null ? null : value.getCode();
  }

  /**
   * Always empty: this type carries a stored role, it does not declare one.
   */
  @JsonIgnore
  @Override
  public String getCaseTypePermissions() {
    return "";
  }

  @JsonIgnore
  @Override
  public List<CCDAccessGroup> getAccessGroups() {
    return List.of();
  }
}
