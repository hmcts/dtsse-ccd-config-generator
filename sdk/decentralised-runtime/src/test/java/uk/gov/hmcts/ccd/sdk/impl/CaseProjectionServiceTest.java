package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseProjectionServiceTest {

  private final CaseDataRepository caseDataRepository = mock(CaseDataRepository.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final ResolvedConfigRegistry configRegistry = mock(ResolvedConfigRegistry.class);
  private final DefinitionRegistry definitionRegistry = mock(DefinitionRegistry.class);

  @Test
  void loadResolvesExplicitCaseTypeIdsForSharedCaseData() {
    var configA = config(TestCaseData.class, TestState.class);
    var configB = config(TestCaseData.class, TestState.class);
    when(configRegistry.asMap()).thenReturn(Map.of("CASE_A", configA, "CASE_B", configB));
    when(caseDataRepository.getCase(111L)).thenReturn(caseDetails(111L, "CASE_B", "Open"));
    when(definitionRegistry.find("CASE_B")).thenReturn(Optional.empty());

    var service = new CaseProjectionService(
        caseDataRepository,
        mapper,
        List.of(new ExplicitCaseTypeView(Set.of("CASE_A", "CASE_B"))),
        configRegistry,
        definitionRegistry,
        false
    );

    var loaded = service.load(111L);
    assertThat(loaded.getCaseDetails().getData().get("projected").asText()).isEqualTo("111-blob");
  }

  @Test
  void loadInLegacyModeAllowsExplicitCaseTypesOutsideResolvedConfigRegistry() {
    when(configRegistry.asMap()).thenReturn(Map.of());
    when(caseDataRepository.getCase(222L)).thenReturn(caseDetails(222L, "LEGACY_CASE", "Open"));
    when(definitionRegistry.find("LEGACY_CASE"))
        .thenReturn(Optional.of(mock(CaseTypeDefinition.class)), Optional.empty());

    var service = new CaseProjectionService(
        caseDataRepository,
        mapper,
        List.of(new ExplicitCaseTypeView(Set.of("LEGACY_CASE"))),
        configRegistry,
        definitionRegistry,
        true
    );

    var loaded = service.load(222L);
    assertThat(loaded.getCaseDetails().getData().get("projected").asText()).isEqualTo("222-blob");
  }

  @Test
  void loadFallsBackToGenericTypeInferenceWhenCaseTypeIdsAreNotDeclared() {
    var config = config(TestCaseData.class, TestState.class);
    when(configRegistry.asMap()).thenReturn(Map.of("CASE_A", config));
    when(caseDataRepository.getCase(333L)).thenReturn(caseDetails(333L, "CASE_A", "Open"));
    when(definitionRegistry.find("CASE_A")).thenReturn(Optional.empty());

    var service = new CaseProjectionService(
        caseDataRepository,
        mapper,
        List.of(new InferredView()),
        configRegistry,
        definitionRegistry,
        false
    );

    var loaded = service.load(333L);
    assertThat(loaded.getCaseDetails().getData().get("projected").asText()).isEqualTo("333-blob");
  }

  @Test
  void constructorRejectsAmbiguousGenericMatchesWithoutExplicitCaseTypeIds() {
    var configA = config(TestCaseData.class, TestState.class);
    var configB = config(TestCaseData.class, TestState.class);
    when(configRegistry.asMap()).thenReturn(Map.of("CASE_A", configA, "CASE_B", configB));

    assertThatThrownBy(() -> new CaseProjectionService(
        caseDataRepository,
        mapper,
        List.of(new InferredView()),
        configRegistry,
        definitionRegistry,
        false
    ))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("caseTypeIds()");
  }

  @Test
  void constructorAllowsLegacyExplicitCaseTypeBeforeDefinitionSnapshotsExist() {
    when(configRegistry.asMap()).thenReturn(Map.of());

    var service = new CaseProjectionService(
        caseDataRepository,
        mapper,
        List.of(new ExplicitCaseTypeView(Set.of("LEGACY_CASE"))),
        configRegistry,
        definitionRegistry,
        true
    );

    assertThat(service).isNotNull();
  }

  private static DecentralisedCaseDetails caseDetails(long reference, String caseType, String state) {
    var details = new CaseDetails();
    details.setReference(reference);
    details.setCaseTypeId(caseType);
    details.setState(state);
    details.setData(Map.of("source", TextNode.valueOf("blob")));

    var wrapper = new DecentralisedCaseDetails();
    wrapper.setCaseDetails(details);
    return wrapper;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static ResolvedCCDConfig<?, ?, ?> config(Class<?> caseDataClass, Class<?> stateClass) {
    ResolvedCCDConfig<?, ?, ?> config = mock(ResolvedCCDConfig.class);
    when(config.getCaseClass()).thenReturn((Class) caseDataClass);
    when(config.getStateClass()).thenReturn((Class) stateClass);
    return config;
  }

  private static class TestCaseData {
    public String source;
    public String projected;
  }

  private enum TestState {
    Open
  }

  private static class ExplicitCaseTypeView implements CaseView<TestCaseData, TestState> {
    private final Set<String> caseTypeIds;

    ExplicitCaseTypeView(Set<String> caseTypeIds) {
      this.caseTypeIds = caseTypeIds;
    }

    @Override
    public TestCaseData getCase(CaseViewRequest<TestState> request, TestCaseData blobCase) {
      blobCase.projected = request.caseRef() + "-" + blobCase.source;
      return blobCase;
    }

    @Override
    public Set<String> caseTypeIds() {
      return caseTypeIds;
    }
  }

  private static class InferredView implements CaseView<TestCaseData, TestState> {
    @Override
    public TestCaseData getCase(CaseViewRequest<TestState> request, TestCaseData blobCase) {
      blobCase.projected = request.caseRef() + "-" + blobCase.source;
      return blobCase;
    }
  }
}
