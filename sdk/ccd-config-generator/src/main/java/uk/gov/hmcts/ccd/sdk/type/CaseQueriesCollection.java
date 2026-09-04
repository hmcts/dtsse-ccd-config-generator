package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * Predefined CCD complex type shipped platform-side (definition-store base type
 * {@code CaseQueriesCollection}), the platform's query-management thread: a party/role identifier
 * plus the collection of {@link CaseMessage} making up the thread.
 */
@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "CaseQueriesCollection", generate = false)
public class CaseQueriesCollection {

  @JsonProperty("partyName")
  private String partyName;

  @JsonProperty("roleOnCase")
  private String roleOnCase;

  @JsonProperty("caseMessages")
  private List<ListValue<CaseMessage>> caseMessages;

  @JsonCreator
  public CaseQueriesCollection(
      @JsonProperty("partyName") String partyName,
      @JsonProperty("roleOnCase") String roleOnCase,
      @JsonProperty("caseMessages") List<ListValue<CaseMessage>> caseMessages
  ) {
    this.partyName = partyName;
    this.roleOnCase = roleOnCase;
    this.caseMessages = caseMessages;
  }
}
