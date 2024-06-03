package uk.gov.hmcts.reform.fpl.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.type.DynamicListItem;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
public class CaseRoleID implements HasRole {

    @JsonProperty("value")
    private DynamicListItem value;

    @JsonProperty("list_items")
    private List<DynamicListItem> listItems;

    public String getRole() {
        return value.getCode();
    }

    public String getCaseTypePermissions() {
        return value.getLabel();
    }

    public CaseRoleID(@JsonProperty("value") DynamicListItem value,
                      @JsonProperty("list_items") List<DynamicListItem> listItems) {
        this.value = value;
        this.listItems = listItems;
    }
}
