package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.Search;
import uk.gov.hmcts.ccd.sdk.api.Search.SearchBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchField;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

// Pins the fluent role-scoped SearchBuilder overloads used by the JSON -> Java converter to emit
// WorkBasket/Search input & result rows carrying a UserRole (AccessProfile) column.
public class SearchBuilderRoleTest {

  private final PropertyUtils propertyUtils = new PropertyUtils();

  @Test
  public void rawFieldOverloadSetsUserRole() {
    SearchBuilder<CaseData, UserRole> builder = SearchBuilder.builder(CaseData.class, propertyUtils);
    builder.field("hearingDetails", "Hearing Details", LOCAL_AUTHORITY);

    Search<CaseData, UserRole> search = builder.build();
    SearchField<UserRole> field = search.getFields().get(0);

    assertThat(field.getId()).isEqualTo("hearingDetails");
    assertThat(field.getLabel()).isEqualTo("Hearing Details");
    assertThat(field.getUserRole()).isEqualTo(LOCAL_AUTHORITY);
    // Role-scoped: only visible to the scoped role.
    assertThat(field.availableToRole(LOCAL_AUTHORITY.getRole())).isTrue();
    assertThat(field.availableToRole(HMCTS_ADMIN.getRole())).isFalse();
  }

  @Test
  public void getterOverloadSetsUserRole() {
    SearchBuilder<CaseData, UserRole> builder = SearchBuilder.builder(CaseData.class, propertyUtils);
    builder.field(CaseData::getCaseName, "Case name", HMCTS_ADMIN);

    SearchField<UserRole> field = builder.build().getFields().get(0);

    assertThat(field.getId()).isEqualTo("caseName");
    assertThat(field.getUserRole()).isEqualTo(HMCTS_ADMIN);
  }

  @Test
  public void unscopedFieldRemainsVisibleToAllRoles() {
    SearchBuilder<CaseData, UserRole> builder = SearchBuilder.builder(CaseData.class, propertyUtils);
    builder.field("caseName", "Case name");

    SearchField<UserRole> field = builder.build().getFields().get(0);

    assertThat(field.getUserRole()).isNull();
    assertThat(field.availableToRole(LOCAL_AUTHORITY.getRole())).isTrue();
    assertThat(field.availableToRole(HMCTS_ADMIN.getRole())).isTrue();
  }
}
