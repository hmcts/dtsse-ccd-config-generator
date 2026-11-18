package uk.gov.hmcts.ccd.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.jackson.UnwrappedPrefixModule;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

public class CcdCallbackExecutorMapperTest {

  private static final String CASE_TYPE = "PREFIXED";

  @Test
  public void readsCaseDataIntactWithAMapperTheSdkNeverConfigured() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    mapper.convertValue(data(), PrefixedCaseData.class);

    CcdCallbackExecutor executor = new CcdCallbackExecutor(registry(), mapper,
        new DefaultListableBeanFactory().getBeanProvider(ExternalUserResolver.class));
    PrefixedCaseData read = (PrefixedCaseData) executor.convertCaseDetails(CaseDetails.builder()
        .id(1L)
        .caseTypeId(CASE_TYPE)
        .data(data())
        .build()).getData();

    assertThat(read.applicant.contact.email).isEqualTo("ann@example.com");
    assertThat(mapper.getRegisteredModuleIds()).doesNotContain(UnwrappedPrefixModule.class.getName());
  }

  private static Map<String, Object> data() {
    return Map.of("applicantname", "Ann", "applicantcontact", Map.of("email", "ann@example.com"));
  }

  private static ResolvedConfigRegistry registry() {
    var config = new ResolvedCCDConfig<>(PrefixedCaseData.class, State.class, UserRole.class, Map.of(),
        ImmutableSet.copyOf(State.values()));
    var builder = new ConfigBuilderImpl<>(config);
    builder.caseType(CASE_TYPE, "Prefixed", "Prefixed case type");
    return new ResolvedConfigRegistry(List.of(builder.build()));
  }

  public static class PrefixedCaseData {
    @JsonUnwrapped(prefix = "applicant")
    public Applicant applicant;
  }

  public static class Applicant {
    public String name;
    public Contact contact;
  }

  public static class Contact {
    public String email;
  }
}
