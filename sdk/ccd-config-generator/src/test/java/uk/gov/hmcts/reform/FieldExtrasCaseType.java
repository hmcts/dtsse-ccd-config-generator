package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.FieldExtrasState.Open;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Exercises the {@code CaseEventToFields} metadata gaps this feature closes: raw-string
 * {@link uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder#defaultValue(String)}, fluent
 * {@link uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder#retainHiddenValue()}, and the
 * {@code FieldCollectionBuilder} {@code lastField()}-style fluent forms of the same plus
 * label/hint/showCondition/DisplayContextParameter, usable after a {@code readonly}/{@code
 * *NoSummary} call that returns the collection builder rather than the field. Each is default-off;
 * a config that does not call them produces no trace of the corresponding column.
 */
@Component
public class FieldExtrasCaseType
    implements CCDConfig<FieldExtrasCaseData, FieldExtrasState, UserRole> {

  @Override
  public void configure(ConfigBuilder<FieldExtrasCaseData, FieldExtrasState, UserRole> builder) {
    builder.caseType("FieldExtras", "Field extras", "Field extras case type");
    builder.jurisdiction("FIELDEXTRAS", "Field extras jurisdiction", "Field extras jurisdiction desc");

    builder.event("create")
        .initialState(Open)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .optional(FieldExtrasCaseData::getOptionalField, "noSummaryField=\"*\"")
        .defaultValue("a literal default")
        .retainHiddenValue()
        .readonlyNoSummary(FieldExtrasCaseData::getReadonlyField)
        .caseEventFieldLabel("Readonly override label")
        .caseEventFieldHint("Readonly override hint")
        .fieldShowCondition("optionalField=\"a literal default\"")
        .displayContextParameter("#TEXTAREA")
        .retainHiddenValue()
        .mandatoryNoSummary(FieldExtrasCaseData::getNoSummaryField)
        .defaultValue("no-summary default")
        // Positional defaultValue on a top-level field: routed to CaseEventToComplexTypes only, it
        // must leave no DefaultValue on this field's CaseEventToFields row (the consumer regression
        // that PR #1031's original applyDefaultValue introduced).
        .optional(FieldExtrasCaseData::getPositionalDefaultField, null, "positional default")
        // PublishAs without publish(true): the definition store reads Publish and PublishAs as
        // unrelated columns, so the alias must emit with no Publish=Y alongside it.
        .optional(FieldExtrasCaseData::getAliasOnlyField)
        .publishAs("aliasOnly");
  }
}
