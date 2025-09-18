package uk.gov.hmcts.ccd.sdk.type;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "ChangeOrganisationRequest", generate = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChangeOrganisationRequest<R extends HasRole> {

  @JsonProperty("OrganisationToAdd")
  private Organisation organisationToAdd;

  @JsonProperty("OrganisationToRemove")
  private Organisation organisationToRemove;

  @JsonProperty("CaseRoleId")
  private R caseRoleId;

  @JsonProperty("Reason")
  private String reason;

  @JsonProperty("NotesReason")
  private String notesReason;

  @JsonProperty("ApprovalStatus")
  private ChangeOrganisationApprovalStatus approvalStatus;

  @JsonProperty("RequestTimestamp")
  @JsonSerialize(
          using = LocalDateTimeSerializer.class
  )
  @JsonDeserialize(
          using = LocalDateTimeDeserializer.class
  )
  private LocalDateTime requestTimestamp;

  @JsonProperty("ApprovalRejectionTimestamp")
  private LocalDateTime approvalRejectionTimestamp;

  @JsonProperty("CreatedBy")
  private String createdBy;

  @JsonCreator
  public ChangeOrganisationRequest(
      @JsonProperty("OrganisationToAdd") Organisation organisationToAdd,
      @JsonProperty("OrganisationToRemove") Organisation organisationToRemove,
      @JsonProperty("CaseRoleId") R caseRoleId,
      @JsonProperty("Reason") String reason,
      @JsonProperty("NotesReason") String notesReason,
      @JsonProperty("ApprovalStatus") ChangeOrganisationApprovalStatus approvalStatus,
      @JsonProperty("RequestTimestamp") LocalDateTime requestTimestamp,
      @JsonProperty("ApprovalRejectionTimestamp") LocalDateTime approvalRejectionTimestamp,
      @JsonProperty("CreatedBy") String createdBy
  ) {
    this.organisationToAdd = organisationToAdd;
    this.organisationToRemove = organisationToRemove;
    this.caseRoleId = caseRoleId;
    this.reason = reason;
    this.notesReason = notesReason;
    this.approvalStatus = approvalStatus;
    this.requestTimestamp = requestTimestamp;
    this.approvalRejectionTimestamp = approvalRejectionTimestamp;
    this.createdBy = createdBy;
  }
}
