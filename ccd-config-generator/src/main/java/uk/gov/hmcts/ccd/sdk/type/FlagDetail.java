package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

import java.time.LocalDateTime;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
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

}
