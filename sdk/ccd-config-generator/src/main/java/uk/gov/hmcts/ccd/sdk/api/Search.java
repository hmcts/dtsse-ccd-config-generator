package uk.gov.hmcts.ccd.sdk.api;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Search<T, R extends HasRole> {
  private List<SearchField<R>> fields;

  public static class SearchBuilder<T, R extends HasRole> {
    private Class<T> model;
    private PropertyUtils propertyUtils;

    public static <T, R extends HasRole> SearchBuilder<T, R> builder(Class<T> model,
                                                                     PropertyUtils propertyUtils) {
      SearchBuilder<T, R> result = Search.builder();
      result.model = model;
      result.propertyUtils = propertyUtils;
      result.fields = new ArrayList<>();
      return result;
    }

    public SearchBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String label) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(SearchField.<R>builder().id(name).label(label).build());
      return this;
    }

    /**
     * Add a role-scoped search/workbasket field. The row is emitted with a {@code UserRole} column
     * ({@code AccessProfile} alias) so the definition store only makes the field visible to the
     * given role; add one call per role to expose it to several roles (matching how the sheets scope
     * individual rows). Follows the row-level scoping of {@link Tab.TabBuilder#forRoles} but per field.
     *
     * <p>Read permission for the referenced case field is injected for the scoped role only (not all
     * roles) by the AuthorisationCaseField generator, which is what the definition store expects for a
     * role-scoped search field.
     */
    public SearchBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String label, R role) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(SearchField.<R>builder().id(name).label(label).userRole(role).build());
      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label) {
      fields.add(SearchField.<R>builder().id(fieldName).label(label).build());
      return this;
    }

    /**
     * Add a role-scoped search/workbasket field by raw field id. See
     * {@link #field(TypedPropertyGetter, String, HasRole)} for the role-scoping semantics.
     */
    public SearchBuilder<T, R> field(String fieldName, String label, R role) {
      fields.add(SearchField.<R>builder().id(fieldName).label(label).userRole(role).build());
      return this;
    }

    public SearchBuilder<T, R> caseReferenceField() {
      fields.add(SearchField.<R>builder().id("[CASE_REFERENCE]").label("Case Number").build());
      return this;
    }

  }
}
