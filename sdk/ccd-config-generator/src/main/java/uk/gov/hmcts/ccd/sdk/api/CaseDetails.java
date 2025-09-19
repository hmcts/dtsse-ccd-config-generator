package uk.gov.hmcts.ccd.sdk.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.reform.ccd.client.model.Classification;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CaseDetails<T, S> {
  private Long id;

  private String jurisdiction;

  @JsonProperty("case_type_id")
  private String caseTypeId;

  @JsonProperty("created_date")
  private LocalDateTime createdDate;

  @JsonProperty("last_modified")
  private LocalDateTime lastModified;

  private S state;

  @JsonProperty("locked_by_user_id")
  private Integer lockedBy;

  @JsonProperty("security_level")
  private Integer securityLevel;

  @JsonProperty("case_data")
  @JsonAlias("data")
  private T data;

  @JsonProperty("security_classification")
  private Classification securityClassification;

  @JsonProperty("callback_response_status")
  private String callbackResponseStatus;

}
