package uk.gov.hmcts.ccd.sdk.impl.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.domain.model.definition.CaseEventDefinition;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;
import uk.gov.hmcts.ccd.sdk.PropertyUtils;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.impl.DefinitionRegistry;

class JsonResolvedConfigAugmenterTest {

  @Test
  void registersJsonDefinitionEventWithoutCallbacks() {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> config = config(Map.of());

    augmenter(definition(event("json-no-callback"))).augment(List.of(config));

    assertThat(config.getEvents()).containsKey("json-no-callback");
    Event<TestCaseData, TestRole, TestState> event = config.getEvents().get("json-no-callback");
    assertThat(event.getAboutToSubmitCallback()).isNull();
    assertThat(event.getSubmittedCallback()).isNull();
  }

  @Test
  void leavesExistingSdkEventAloneWhenJsonDefinitionHasCallbacks() {
    CaseEventDefinition overlappingEvent = event("overlap");
    overlappingEvent.setCallBackURLAboutToSubmitEvent("/legacy/about-to-submit");
    overlappingEvent.setCallBackURLSubmittedEvent("/legacy/submitted");
    Event<TestCaseData, TestRole, TestState> sdkEvent = existingEvent("overlap");
    JsonCallbackAdapterFactory callbackAdapterFactory = mock(JsonCallbackAdapterFactory.class);
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> config = config(Map.of(
        "overlap", sdkEvent
    ));

    augmenter(definition(overlappingEvent), callbackAdapterFactory).augment(List.of(config));

    assertThat(config.getEvents()).containsEntry("overlap", sdkEvent);
    verifyNoInteractions(callbackAdapterFactory);
  }

  private JsonResolvedConfigAugmenter augmenter(CaseTypeDefinition definition) {
    return augmenter(definition, mock(JsonCallbackAdapterFactory.class));
  }

  private JsonResolvedConfigAugmenter augmenter(CaseTypeDefinition definition,
                                                JsonCallbackAdapterFactory callbackAdapterFactory) {
    DefinitionRegistry definitionRegistry = mock(DefinitionRegistry.class);
    when(definitionRegistry.loadDefinitions()).thenReturn(Map.of("TestCase", definition));
    return new JsonResolvedConfigAugmenter(
        definitionRegistry,
        callbackAdapterFactory,
        List.of()
    );
  }

  private CaseTypeDefinition definition(CaseEventDefinition... events) {
    CaseTypeDefinition definition = new CaseTypeDefinition();
    definition.setId("TestCase");
    definition.setEvents(List.of(events));
    return definition;
  }

  private CaseEventDefinition event(String id) {
    CaseEventDefinition event = new CaseEventDefinition();
    event.setId(id);
    event.setName(id);
    return event;
  }

  private ResolvedCCDConfig<TestCaseData, TestState, TestRole> config(
      Map<String, Event<TestCaseData, TestRole, TestState>> events
  ) {
    var config = new ResolvedCCDConfig<>(
        TestCaseData.class,
        TestState.class,
        TestRole.class,
        Map.of(),
        ImmutableSet.copyOf(TestState.values())
    );
    config.configureCaseType("TestCase");
    config.addEvents(events);
    return config;
  }

  private Event<TestCaseData, TestRole, TestState> existingEvent(String id) {
    return Event.EventBuilder.<TestCaseData, TestRole, TestState>builder(
        id,
        TestCaseData.class,
        new PropertyUtils(),
        Set.of(TestState.Submitted),
        Set.of(TestState.Submitted)
    ).doBuild();
  }

  private static class TestCaseData {
  }

  private enum TestState {
    Submitted
  }

  private enum TestRole implements HasRole {
    CASEWORKER;

    @Override
    public String getRole() {
      return "caseworker";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }
}
