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
@ComplexType(name = "FlagDetail", generate = false)
@SuppressWarnings("java:S117")
public class FlagDetail {

  @JsonProperty("name")
  private String name;

  @JsonProperty("name_cy")
  private String nameCy;

  @JsonProperty("subTypeValue")
  private String subTypeValue;

  @JsonProperty("subTypeValue_cy")
  private String subTypeValueCy;

  @JsonProperty("subTypeKey")
  private String subTypeKey;

  @JsonProperty("otherDescription")
  private String otherDescription;

  @JsonProperty("otherDescription_cy")
  private String otherDescriptionCy;

  @JsonProperty("flagComment")
  private String flagComment;

  @JsonProperty("flagComment_cy")
  private String flagCommentCy;

  @JsonProperty("flagUpdateComment")
  private String flagUpdateComment;

  @JsonProperty("dateTimeModified")
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  private LocalDateTime dateTimeModified;

  @JsonProperty("dateTimeCreated")
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  private LocalDateTime dateTimeCreated;

  @JsonProperty("path")
  private List<ListValue<String>> path;

  @JsonProperty("hearingRelevant")
  private YesOrNo hearingRelevant;

  @JsonProperty("flagCode")
  private String flagCode;

  @JsonProperty("status")
  private String status;

  @JsonProperty("availableExternally")
  private YesOrNo availableExternally;

  @JsonCreator
  public FlagDetail(@JsonProperty("name") String name,
                    @JsonProperty("name_cy") String nameCy,
                    @JsonProperty("subTypeValue") String subTypeValue,
                    @JsonProperty("subTypeValue_cy") String subTypeValueCy,
                    @JsonProperty("subTypeKey") String subTypeKey,
                    @JsonProperty("otherDescription") String otherDescription,
                    @JsonProperty("otherDescription_cy") String otherDescriptionCy,
                    @JsonProperty("flagComment") String flagComment,
                    @JsonProperty("flagComment_cy") String flagCommentCy,
                    @JsonProperty("flagUpdateComment") String flagUpdateComment,
                    @JsonProperty("dateTimeModified") LocalDateTime dateTimeModified,
                    @JsonProperty("dateTimeCreated") LocalDateTime dateTimeCreated,
                    @JsonProperty("path") List<ListValue<String>>  path,
                    @JsonProperty("hearingRelevant") YesOrNo hearingRelevant,
                    @JsonProperty("flagCode") String flagCode,
                    @JsonProperty("status") String status,
                    @JsonProperty("availableExternally") YesOrNo availableExternally
  ) {
    this.name = name;
    this.nameCy = nameCy;
    this.subTypeValue = subTypeValue;
    this.subTypeValueCy = subTypeValueCy;
    this.subTypeKey = subTypeKey;
    this.otherDescription = otherDescription;
    this.otherDescriptionCy = otherDescriptionCy;
    this.flagComment = flagComment;
    this.flagCommentCy = flagCommentCy;
    this.flagUpdateComment = flagUpdateComment;
    this.dateTimeCreated = dateTimeCreated;
    this.dateTimeModified = dateTimeModified;
    this.path = path;
    this.hearingRelevant = hearingRelevant;
    this.flagCode = flagCode;
    this.status = status;
    this.availableExternally = availableExternally;

  }
}
