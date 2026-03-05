package uk.gov.hmcts.reform.typedshow;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.TypedShowCondition;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

@Component
public class TypedShowConfig implements CCDConfig<TypedShowCaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<TypedShowCaseData, State, UserRole> builder) {
    builder.caseType("typed_show", "Typed show", "Typed show conditions test case type");

    TypedShowCondition visible =
        TypedShowCondition.when(TypedShowCaseData::getVisibility).is(TypedShowVisibility.SHOW);
    TypedShowCondition categoryAorB =
        TypedShowCondition.when(TypedShowCaseData::getCategory).isAnyOf("A", "B");
    TypedShowCondition tagsContainUrgent =
        TypedShowCondition.when(TypedShowCaseData::getTags).contains("urgent");
    TypedShowCondition complexCategory =
        TypedShowCondition.when(TypedShowCaseData::getCategory).is("complex");

    builder.event("typedShowEvent")
        .forState(State.Open)
        .name("Typed show event")
        .grant(CRU, HMCTS_ADMIN)
        .fields()
        .page("1")
        .showConditionWhen(visible)
        .mandatoryWhen(TypedShowCaseData::getTitle, visible)
        .optionalWhen(TypedShowCaseData::getNotes, visible.or(categoryAorB), true)
        .readonlyWhen(TypedShowCaseData::getReadonlyField, tagsContainUrgent, true)
        .listWhen(TypedShowCaseData::getItems, visible)
          .optional(TypedShowItem::getValue)
          .done()
        .complexWhen(TypedShowCaseData::getDetails, complexCategory, "Details label", "Details hint")
          .optional(TypedShowDetails::getDetailText)
          .done()
        .labelWhen("typedLabel", "### Typed label", visible, true);

    builder.event("typedShowAcronymGetterEvent")
        .forState(State.Open)
        .name("Typed show acronym getter event")
        .grant(CRU, HMCTS_ADMIN)
        .fields()
        .mandatoryWhen(
            TypedShowCaseData::getTitle,
            TypedShowCondition.when(TypedShowCaseData::getAField).is("YES"));
  }
}
