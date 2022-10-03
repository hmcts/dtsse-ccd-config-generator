package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "FlagDetail", generate = false)
public class FlagDetail {

  @JsonProperty("name")
  private String name;

  @JsonProperty("subTypeValue")
  private String subTypeValue;

  @JsonProperty("subTypeKey")
  private String subTypeKey;

  @JsonProperty("otherDescription")
  private String otherDescription;

  @JsonProperty("flagComment")
  private String flagComment;

  @JsonProperty("dateTimeModified")
  private LocalDateTime dateTimeModified;

  @JsonProperty("dateTimeCreated")
  private LocalDateTime dateTimeCreated;

  @JsonProperty("path")
  private String path;

  @JsonProperty("hearingRelated")
  private YesOrNo hearingRelated;

  @JsonProperty("flagCode")
  private String flagCode;

  @JsonProperty("status")
  private String status;

  @JsonCreator
  public FlagDetail(@JsonProperty("name") String name,
                    @JsonProperty("subTypeValue") String subTypeValue,
                    @JsonProperty("subTypeKey") String subTypeKey,
                    @JsonProperty("otherDescription") String otherDescription,
                    @JsonProperty("flagComment") String flagComment,
                    @JsonProperty("dateTimeModified") LocalDateTime dateTimeModified,
                    @JsonProperty("dateTimeCreated") LocalDateTime dateTimeCreated,
                    @JsonProperty("path") String path,
                    @JsonProperty("hearingRelated") YesOrNo hearingRelated,
                    @JsonProperty("flagCode") String flagCode,
                    @JsonProperty("status") String status
  ) {
    this.name = name;
    this.subTypeValue = subTypeValue;
    this.subTypeKey = subTypeKey;
    this.otherDescription = otherDescription;
    this.flagComment = flagComment;
    this.dateTimeCreated = dateTimeCreated;
    this.dateTimeModified = dateTimeModified;
    this.path = path;
    this.hearingRelated = hearingRelated;
    this.flagCode = flagCode;
    this.status = status;

  }
}
