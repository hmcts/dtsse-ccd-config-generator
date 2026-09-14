package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * Predefined CCD complex type shipped platform-side (definition-store base type
 * {@code CaseMessage}), a single message in a {@link CaseQueriesCollection} query thread.
 */
@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "CaseMessage", generate = false)
public class CaseMessage {

  @JsonProperty("id")
  private String id;

  @JsonProperty("subject")
  private String subject;

  @JsonProperty("name")
  private String name;

  @JsonProperty("body")
  private String body;

  @JsonProperty("attachments")
  private List<ListValue<Document>> attachments;

  @JsonProperty("isHearingRelated")
  private YesOrNo isHearingRelated;

  @JsonProperty("hearingDate")
  private LocalDate hearingDate;

  @JsonProperty("createdOn")
  private LocalDateTime createdOn;

  @JsonProperty("createdBy")
  private String createdBy;

  @JsonProperty("parentId")
  private String parentId;

  @JsonProperty("messageType")
  private String messageType;

  @JsonProperty("isClosed")
  private YesOrNo isClosed;

  @JsonProperty("isHmctsStaff")
  private YesOrNo isHmctsStaff;

  @JsonCreator
  public CaseMessage(
      @JsonProperty("id") String id,
      @JsonProperty("subject") String subject,
      @JsonProperty("name") String name,
      @JsonProperty("body") String body,
      @JsonProperty("attachments") List<ListValue<Document>> attachments,
      @JsonProperty("isHearingRelated") YesOrNo isHearingRelated,
      @JsonProperty("hearingDate") LocalDate hearingDate,
      @JsonProperty("createdOn") LocalDateTime createdOn,
      @JsonProperty("createdBy") String createdBy,
      @JsonProperty("parentId") String parentId,
      @JsonProperty("messageType") String messageType,
      @JsonProperty("isClosed") YesOrNo isClosed,
      @JsonProperty("isHmctsStaff") YesOrNo isHmctsStaff
  ) {
    this.id = id;
    this.subject = subject;
    this.name = name;
    this.body = body;
    this.attachments = attachments;
    this.isHearingRelated = isHearingRelated;
    this.hearingDate = hearingDate;
    this.createdOn = createdOn;
    this.createdBy = createdBy;
    this.parentId = parentId;
    this.messageType = messageType;
    this.isClosed = isClosed;
    this.isHmctsStaff = isHmctsStaff;
  }
}
