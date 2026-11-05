package uk.gov.hmcts.ccd.sdk.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.cfg.PackageVersion;
import lombok.Data;
import org.junit.Before;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.type.AddressUK;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.DynamicList;

/**
 * On jackson-databind 2.18, which this build resolves, Jackson unwraps a prefixed group through
 * {@code BeanPropertyMap} rather than {@code SettableBeanProperty.unwrapped}, and without the
 * module empties an object nested in the group that has no {@code @JsonCreator}.
 */
public class UnwrappedPrefixModuleOnJackson218Test {

  private static final String STORED = """
      {
        "applicantGivenNames": "Ann",
        "applicantAddress": {
          "AddressLine1": "1 High Street", "AddressLine2": "Flat 2", "AddressLine3": "Old Town",
          "PostTown": "London", "County": "Greater London", "PostCode": "SW1A 1AA", "Country": "UK"
        },
        "applicantDocument": {
          "document_url": "http://dm/documents/1", "document_binary_url": "http://dm/documents/1/binary",
          "document_filename": "one.pdf"
        },
        "applicantJudge": {
          "value": {"code": "6d1e2a4c-8f7b-4c1a-9b8e-1f2a3b4c5d6e", "label": "Judge Rinder"},
          "list_items": [{"code": "6d1e2a4c-8f7b-4c1a-9b8e-1f2a3b4c5d6e", "label": "Judge Rinder"}],
          "valueCode": "6d1e2a4c-8f7b-4c1a-9b8e-1f2a3b4c5d6e",
          "valueLabel": "Judge Rinder"
        },
        "applicantContact": {"Email": "ann@example.com", "Phone": "01234 567890"}
      }
      """;

  @Before
  public void onJackson218() {
    assumeTrue(PackageVersion.VERSION.getMajorVersion() == 2 && PackageVersion.VERSION.getMinorVersion() < 19);
  }

  @Test
  public void keepsEveryObjectInAPrefixedGroupIncludingOneWithoutACreator() throws Exception {
    assertThat(roundTrip(mapper().registerModule(new UnwrappedPrefixModule()))).isEqualTo(mapper().readTree(STORED));
  }

  @Test
  public void jacksonEmptiesAnObjectWithoutACreatorWhenTheModuleIsAbsent() throws Exception {
    assertThat(roundTrip(mapper()).get("applicantContact")).isEqualTo(mapper().createObjectNode());
  }

  private static JsonNode roundTrip(ObjectMapper mapper) throws Exception {
    return mapper.valueToTree(mapper.readValue(STORED, CaseData.class));
  }

  private static ObjectMapper mapper() {
    return new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

  @Data
  public static class CaseData {
    @JsonUnwrapped(prefix = "applicant")
    private Applicant applicant;
  }

  @Data
  @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
  public static class Applicant {
    private String givenNames;
    private AddressUK address;
    private Document document;
    private DynamicList judge;
    private Contact contact;
  }

  @Data
  public static class Contact {
    @JsonProperty("Email")
    private String email;

    @JsonProperty("Phone")
    private String phone;
  }
}
