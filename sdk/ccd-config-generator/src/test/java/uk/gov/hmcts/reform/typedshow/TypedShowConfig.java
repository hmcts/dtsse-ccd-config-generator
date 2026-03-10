package uk.gov.hmcts.reform.typedshow;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.ShowCondition;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

@Component
public class TypedShowConfig implements CCDConfig<TypedShowCaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<TypedShowCaseData, State, UserRole> builder) {
    builder.caseType("typed_show", "Typed show", "Typed show conditions test case type");

    ShowCondition visible =
        ShowCondition.when(TypedShowCaseData::getVisibility).is(TypedShowVisibility.SHOW);
    ShowCondition categoryAorB =
        ShowCondition.when(TypedShowCaseData::getCategory).isAnyOf("A", "B");
    ShowCondition tagsContainUrgent =
        ShowCondition.when(TypedShowCaseData::getTags).contains("urgent");
    ShowCondition complexCategory =
        ShowCondition.when(TypedShowCaseData::getCategory).is("complex");

    builder.event("typedShowEvent")
        .forState(State.Open)
        .name("Typed show event")
        .grant(CRU, HMCTS_ADMIN)
        .fields()
        .page("1")
        .showConditionIf(visible)
        .mandatoryIf(TypedShowCaseData::getTitle, visible)
        .optionalIf(TypedShowCaseData::getNotes, visible.or(categoryAorB), true)
        .readonlyIf(TypedShowCaseData::getReadonlyField, tagsContainUrgent, true)
        .listIf(TypedShowCaseData::getItems, visible)
          .optional(TypedShowItem::getValue)
          .done()
        .complexIf(TypedShowCaseData::getDetails, complexCategory, "Details label", "Details hint")
          .optional(TypedShowDetails::getDetailText)
          .done()
        .labelIf("typedLabel", "### Typed label", visible, true);

    builder.event("typedShowAcronymGetterEvent")
        .forState(State.Open)
        .name("Typed show acronym getter event")
        .grant(CRU, HMCTS_ADMIN)
        .fields()
        .mandatoryIf(
            TypedShowCaseData::getTitle,
            ShowCondition.when(TypedShowCaseData::getAField).is("YES"));

    builder.event("typedShowStateAndNegationEvent")
        .forState(State.Open)
        .name("Typed show state and negation event")
        .grant(CRU, HMCTS_ADMIN)
        .fields()
        .mandatoryIf(
            TypedShowCaseData::getTitle,
            ShowCondition.when(TypedShowCaseData::getVisibility).isNot(TypedShowVisibility.HIDE)
                .and(ShowCondition.stateIs(State.Open)))
        .optionalIf(TypedShowCaseData::getNotes, ShowCondition.stateIsNot(State.Submitted), true)
        .labelIf("typedStateLabel", "### Typed state label", ShowCondition.stateIs(State.Open), true);

    builder.event("typedShowPrecedenceEvent")
        .forState(State.Open)
        .name("Typed show precedence event")
        .grant(CRU, HMCTS_ADMIN)
        .fields()
        .mandatoryIf(TypedShowCaseData::getTitle, visible.and(categoryAorB));

    builder.event("typedShowJsonNamingEvent")
        .forState(State.Open)
        .name("Typed show JsonNaming event")
        .grant(CRU, HMCTS_ADMIN)
        .fields()
        .page("1")
        .complex(TypedShowCaseData::getNamingData)
          .mandatory(TypedShowJsonNamingData::getNamingValue)
          .done()
        .mandatoryIf(TypedShowCaseData::getTitle,
            ShowCondition.when(TypedShowCaseData::getNamingData, TypedShowJsonNamingData::getNamingValue).is("YES"));

    ShowCondition.FieldRef category = ShowCondition.ref(TypedShowCaseData::getCategory);
    ShowCondition.FieldRef tags = ShowCondition.ref(TypedShowCaseData::getTags);
    ShowCondition.FieldRef detailText =
        ShowCondition.ref(TypedShowCaseData::getDetails, TypedShowDetails::getDetailText);

    builder.event("typedShowHelperMethodsEvent")
        .forState(State.Open)
        .name("Typed show helper methods event")
        .grant(CRU, HMCTS_ADMIN)
        .fields()
        .page("1")
        .showConditionIf(ShowCondition.allOf(
            ShowCondition.isNot(category, "archived"),
            ShowCondition.anyOf(
                ShowCondition.isAnyOf(category, "A", "B"),
                ShowCondition.contains(tags, "urgent"))))
        .mandatoryIf(TypedShowCaseData::getTitle, ShowCondition.allOf(
            ShowCondition.is(category, "A"),
            ShowCondition.isNot(detailText, "skip")))
        .optionalIf(TypedShowCaseData::getNotes, ShowCondition.anyOf(
            ShowCondition.contains(tags, "urgent"),
            ShowCondition.isAnyOf(category, "B", "C")), true)
        .hidden(TypedShowCaseData::getReadonlyField, true)
        .labelIf("typedHelperLabel", "### Typed helper label", ShowCondition.isAnyOf(category, "A", "B"), true);
  }
}
