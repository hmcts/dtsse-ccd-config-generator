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
 *
 * <p>Also exercises {@link uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder#publishAs(String)} called
 * without {@code publish(true)}: the definition store's {@code Publish}/{@code PublishAs} columns
 * are unrelated, so the field carries {@code PublishAs} with no {@code Publish} column at all
 * (this event does not {@code publishToCamunda()}).
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
        .optional(FieldExtrasCaseData::getPublishAliasField)
        .publishAs("aliasOnly");
  }
}
