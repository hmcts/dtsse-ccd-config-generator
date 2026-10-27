package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.JdkNamedMemberState.Open;
import static uk.gov.hmcts.reform.JdkNamedMemberState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A case type whose event descends through a complex member named {@code value} — the collection
 * wrapper name real definitions spell out in a {@code ListElementCode} path (see
 * {@link JdkNamedMemberWrapper}). Placing a leaf beneath it makes
 * {@code CaseEventToComplexTypesGenerator} resolve the name {@code value} against
 * {@code String.class}, hitting {@code String}'s own private {@code value} member; that used to
 * abort generation for the entire case type with an {@code InaccessibleObjectException}. The golden
 * snapshot pins the rows the descent must emit.
 */
@Component
public class JdkNamedMemberCaseType
    implements CCDConfig<JdkNamedMemberCaseData, JdkNamedMemberState, UserRole> {

  @Override
  public void configure(
      ConfigBuilder<JdkNamedMemberCaseData, JdkNamedMemberState, UserRole> builder) {
    builder.caseType(
        "JdkNamedMember", "JDK-named member", "A complex member named after a JDK field");

    builder.event("create")
        .forStateTransition(Open, Submitted)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .complex(JdkNamedMemberCaseData::getOrdersToSend)
          .complex(JdkNamedMemberWrapper::getValue)
            .mandatory(JdkNamedMemberOrder::getDocumentName)
          .done()
          .done();
  }
}
