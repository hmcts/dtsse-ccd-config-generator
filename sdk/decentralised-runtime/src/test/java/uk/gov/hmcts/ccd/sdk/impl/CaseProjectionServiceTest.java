package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;

class CaseProjectionServiceTest {

  @Test
  void projectionPreservesTheExactReservedTtlNode() {
    long caseReference = 1234567890123456L;
    var mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    var ttl = mapper.createObjectNode()
        .put("SystemTTL", "2030-01-02")
        .put("Suspended", "No")
        .put("FutureMetadata", "preserve-me");
    ttl.putNull("OverrideTTL");

    var rawCase = new CaseDetails();
    rawCase.setReference(caseReference);
    rawCase.setCaseTypeId("TestCase");
    rawCase.setState(TestState.SUBMITTED.name());
    rawCase.setData(Map.of(
        "subject", mapper.getNodeFactory().textNode("A case"),
        "TTL", ttl
    ));
    var raw = new DecentralisedCaseDetails();
    raw.setCaseDetails(rawCase);

    var repository = mock(CaseDataRepository.class);
    when(repository.getCase(caseReference)).thenReturn(raw);
    var configRegistry = mock(ResolvedConfigRegistry.class);
    ResolvedCCDConfig<?, ?, ?> config = mock(ResolvedCCDConfig.class);
    when(configRegistry.asMap()).thenReturn(Map.of("TestCase", config));
    var definitionRegistry = mock(DefinitionRegistry.class);
    when(definitionRegistry.find("TestCase")).thenReturn(Optional.empty());

    var service = new CaseProjectionService(
        repository,
        mapper,
        java.util.List.of(new TestCaseView()),
        configRegistry,
        definitionRegistry
    );

    Map<String, JsonNode> projected = service.load(caseReference).getCaseDetails().getData();

    assertThat(projected.get("subject").asText()).isEqualTo("A case");
    assertThat(projected.get("TTL")).isSameAs(ttl);
  }

  private enum TestState {
    SUBMITTED
  }

  private record TestCaseData(String subject) {}

  private static class TestCaseView implements CaseView<TestCaseData, TestState> {
    @Override
    public Set<String> caseTypeIds() {
      return Set.of("TestCase");
    }

    @Override
    public TestCaseData getCase(CaseViewRequest<TestState> request, TestCaseData blobCase) {
      return blobCase;
    }
  }
}
