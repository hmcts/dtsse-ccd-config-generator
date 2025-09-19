package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
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
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
  private LocalDateTime createdDateTime;

  @JsonProperty("CaseType")
  private String caseType;


  @JsonCreator
  public CaseLink(
      @JsonProperty("CaseReference") String caseReference,
      @JsonProperty("ReasonForLink") List<ListValue<LinkReason>> reasonForLink,
      @JsonProperty("CreatedDateTime") LocalDateTime createdDateTime,
      @JsonProperty("CaseType") String caseType
  ) {
    this.reasonForLink = reasonForLink;
    this.caseReference = caseReference;
    this.caseType = caseType;
    this.createdDateTime = createdDateTime;
  }
}
