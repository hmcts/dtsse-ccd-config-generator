package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Optional;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.type.AddressGlobalUK;

public class Jackson2CaseDataMapperTest {

  @Test
  public void roundTripsCcdAddressFields() throws Exception {
    ObjectMapper applicationMapper = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    ObjectMapper mapper = Jackson2CaseDataMapper.configured(Optional.of(applicationMapper));
    String input = """
        {"address":{"AddressLine1":"line 1","PostTown":"town","PostCode":"postcode","Country":"UK"}}
        """;

    AddressCaseData data = mapper.readValue(input, AddressCaseData.class);

    assertThat(data.address().getAddressLine1()).isEqualTo("line 1");
    assertThat(mapper.readTree(mapper.writeValueAsBytes(data)).path("address").path("AddressLine1").asText())
        .isEqualTo("line 1");
  }

  @Test
  public void roundTripsAddressInsidePrefixedUnwrappedData() throws Exception {
    ObjectMapper mapper = Jackson2CaseDataMapper.configured(Optional.of(new ObjectMapper()));
    String input = """
        {"applicant1Address":{"AddressLine1":"line 1","PostTown":"town","PostCode":"postcode","Country":"UK"}}
        """;

    UnwrappedCaseData data = mapper.readValue(input, UnwrappedCaseData.class);

    assertThat(data.applicant().address().getAddressLine1()).isEqualTo("line 1");
    assertThat(mapper.readTree(mapper.writeValueAsBytes(data)).path("applicant1Address").path("AddressLine1").asText())
        .isEqualTo("line 1");
  }

  private record AddressCaseData(AddressGlobalUK address) {
  }

  private record UnwrappedCaseData(@JsonUnwrapped(prefix = "applicant1") ApplicantData applicant) {
  }

  @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
  private record ApplicantData(AddressGlobalUK address) {
  }
}
