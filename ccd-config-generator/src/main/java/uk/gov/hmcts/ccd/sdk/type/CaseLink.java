package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "CaseLink", generate = false)
public class CaseLink {

  @JsonProperty("CaseReference")
  private String caseReference;

  @JsonProperty("ReasonForLink")
  private List<ListValue<LinkReason>> reasonForLink;

  @JsonProperty("CreatedDateTime")
  private LocalDate createdDateTime;

  @JsonProperty("CaseType")
  private String caseType;


  @JsonCreator
  public CaseLink(
      @JsonProperty("CaseReference") String caseReference,
      @JsonProperty("ReasonForLink") List<ListValue<LinkReason>> reasonForLink,
      @JsonProperty("CreatedDateTime") LocalDate createdDateTime,
      @JsonProperty("CaseType") String caseType
  ) {
    this.reasonForLink = reasonForLink;
    this.caseReference = caseReference;
    this.caseType = caseType;
    this.createdDateTime = createdDateTime;
  }
}
