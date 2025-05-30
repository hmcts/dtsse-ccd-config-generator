package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;


@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "Flags", generate = false)
public class Flags {
  @JsonProperty("partyName")
  private String partyName;

  @JsonProperty("roleOnCase")
  private String roleOnCase;

  @JsonProperty("details")
  private List<ListValue<FlagDetail>> details;

  @JsonProperty("visibility")
  private FlagVisibility visibility;

  @JsonProperty("groupId")
  private UUID groupId;

  @JsonCreator
  public Flags(@JsonProperty("partyName") String partyName,
               @JsonProperty("roleOnCase") String roleOnCase,
               @JsonProperty("details") List<ListValue<FlagDetail>>  details,
               @JsonProperty("visibility") FlagVisibility visibility,
               @JsonProperty("groupId") UUID groupId) {
    this.details = details;
    this.partyName = partyName;
    this.roleOnCase = roleOnCase;
    this.visibility = visibility;
    this.groupId = groupId;
  }
}
