package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.type.AddressUK;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.reform.fpl.access.BulkScan;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

@Component
public class JsonNamingConfig implements CCDConfig<JsonNamingConfig.CaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
    builder.caseType("JsonNaming", "JSON naming", "JSON naming");
    builder.event("editNames").forAllStates().grant(CRU, HMCTS_ADMIN)
        .fields().page("names")
        .complex(CaseData::getParty)
          .mandatory(NamingParty::getName)
          .mandatory(NamingParty::getDocument)
          .mandatory(NamingParty::getAddressUk)
          .mandatory(NamingParty::getExplicitName)
        .done()
        .complex(CaseData::getFlattened)
          .mandatory(NamingParty::getName)
          .mandatory(NamingParty::getExplicitName)
        .done()
        .complex(CaseData::getSnakeParty)
          .mandatory(SnakeParty::getName)
          .mandatory(SnakeParty::getContactName)
        .done();
  }

  @Getter
  @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
  public static class CaseData {
    private NamingParty party;
    @JsonUnwrapped(prefix = "flat")
    private NamingParty flattened;
    private SnakeParty snakeParty;
  }

  @Getter
  public static class NamedPerson {
    @CCD(label = "Name", access = BulkScan.class)
    private String name;
  }

  @Getter
  @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
  public static class NamingParty extends NamedPerson {
    private Document document;
    private AddressUK addressUk;
    @JsonProperty("explicit_name")
    private String explicitName;
  }

  @Getter
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public static class SnakeParty extends NamedPerson {
    private String contactName;
  }
}
