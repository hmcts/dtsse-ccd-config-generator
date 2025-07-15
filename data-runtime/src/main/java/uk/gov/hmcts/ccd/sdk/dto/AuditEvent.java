package uk.gov.hmcts.ccd.sdk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.reform.ccd.client.model.SignificantItem;

import java.time.LocalDateTime;
import java.util.Map;

@SuppressWarnings("checkstyle:SummaryJavadoc") // Javadoc predates checkstyle implementation in module
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class AuditEvent extends Event {
    @JsonProperty("user_id")
    private String userId;
    @JsonProperty("user_last_name")
    private String userLastName;
    @JsonProperty("user_first_name")
    private String userFirstName;
    @JsonProperty("event_name")
    private String eventName;
    @JsonProperty("created_date")
    private LocalDateTime createdDate;
    @JsonProperty("case_type_id")
    private String caseTypeId;
    @JsonProperty("case_type_version")
    private Integer caseTypeVersion;
    @JsonProperty("state_id")
    private String stateId;
    @JsonProperty("state_name")
    private String stateName;
    @JsonProperty("data")
    private Map<String, JsonNode> data;
    @JsonProperty("security_classification")
    private String securityClassification;
    @JsonProperty("significant_item")
    private SignificantItem significantItem;

    @JsonProperty("proxied_by")
    private String proxiedBy;

    @JsonProperty("proxied_by_last_name")
    private String proxiedByLastName;

    @JsonProperty("proxied_by_first_name")
    private String proxiedByFirstName;
}
