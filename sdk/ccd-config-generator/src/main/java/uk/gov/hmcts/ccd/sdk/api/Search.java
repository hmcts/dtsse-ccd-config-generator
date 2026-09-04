package uk.gov.hmcts.ccd.sdk.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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

    /**
     * Add a search/workbasket field configured through the per-field {@link FieldBuilder} sub-builder,
     * for the columns the plain {@code field(...)} overloads cannot express: {@code ListElementCode}
     * (search <em>within</em> a complex field), {@code FieldShowCondition} and {@code ResultsOrdering}
     * — plus role scoping. Because the {@link Consumer} disambiguates from the {@code String}-only
     * overloads it composes with them freely in one chain; the builder still returns {@code this}, so
     * chaining and the convenience methods below are unaffected.
     *
     * <p><b>Multiple {@code ListElementCode} rows per field.</b> A complex field is searched one leaf
     * at a time — call this once per element code to emit one row each (they are kept distinct because
     * {@code ListElementCode} is part of the row's merge key), mirroring how the hand-written sheets
     * carry several rows for the same {@code (CaseFieldID, UserRole)}.
     *
     * <p><b>Per-sheet column rules (enforced by the definition-store importer, not here).</b>
     * {@code FieldShowCondition} is accepted only on the <em>input</em> sheets
     * ({@code SearchInputFields}, {@code WorkBasketInputFields}); {@code ResultsOrdering} only on the
     * <em>result</em> sheets ({@code SearchResultFields}, {@code WorkBasketResultFields}). Setting the
     * wrong one for a given sheet is a definition-import error. {@code ListElementCode} is valid on all
     * four. Sort priorities must be {@code 1}/{@code 2} (see {@link SortOrder}), contiguous and unique
     * per sheet/role.
     *
     * <pre>{@code
     * builder.searchInputFields()
     *     .field(CaseData::getApplicant, "Applicant surname",
     *         f -> f.listElementCode("surname").showCondition("caseName=\"x\""));
     * builder.searchResultFields()
     *     .field(CaseData::getApplicant, "Applicant surname",
     *         f -> f.listElementCode("surname").resultsOrdering(SortOrder.FIRST.DESCENDING));
     * }</pre>
     */
    public SearchBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String label,
                                     Consumer<FieldBuilder<R>> configurer) {
      return field(propertyUtils.getPropertyName(model, getter), label, configurer);
    }

    /**
     * Add a search/workbasket field by raw field id, configured through the per-field
     * {@link FieldBuilder}. See {@link #field(TypedPropertyGetter, String, Consumer)}.
     */
    public SearchBuilder<T, R> field(String fieldName, String label,
                                     Consumer<FieldBuilder<R>> configurer) {
      FieldBuilder<R> fieldBuilder = new FieldBuilder<>(fieldName, label);
      configurer.accept(fieldBuilder);
      fields.add(fieldBuilder.build());
      return this;
    }

    public SearchBuilder<T, R> caseReferenceField() {
      fields.add(SearchField.<R>builder().id("[CASE_REFERENCE]").label("Case Number").build());
      return this;
    }

  }

  /**
   * Per-field configurer for the optional search/workbasket columns. Obtained via
   * {@link SearchBuilder#field(TypedPropertyGetter, String, Consumer)}; every setter returns
   * {@code this} for chaining and any column left unset is omitted from the row (so a field that only
   * calls, say, {@link #listElementCode(String)} produces exactly the same row as before plus that one
   * column). See that method for the per-sheet rules governing which columns are legal where.
   */
  public static class FieldBuilder<R extends HasRole> {
    private final SearchField.SearchFieldBuilder<R> field;

    FieldBuilder(String id, String label) {
      this.field = SearchField.<R>builder().id(id).label(label);
    }

    /**
     * Search within a complex field: the dotted path of the leaf element to expose.
     */
    public FieldBuilder<R> listElementCode(String listElementCode) {
      field.listElementCode(listElementCode);
      return this;
    }

    /**
     * {@code FieldShowCondition} — input sheets only (see the enclosing method's javadoc).
     */
    public FieldBuilder<R> showCondition(String showCondition) {
      field.showCondition(showCondition);
      return this;
    }

    /**
     * {@code ResultsOrdering} — result sheets only; use the {@link SortOrder} constants.
     */
    public FieldBuilder<R> resultsOrdering(SortOrder order) {
      field.order(order);
      return this;
    }

    /**
     * {@code DisplayContextParameter} for this field.
     */
    public FieldBuilder<R> displayContextParameter(String displayContextParameter) {
      field.displayContextParameter(displayContextParameter);
      return this;
    }

    /**
     * Scope this single row to a role (emits the {@code UserRole}/{@code AccessProfile} column). See
     * {@link SearchBuilder#field(TypedPropertyGetter, String, HasRole)} for the scoping semantics.
     */
    public FieldBuilder<R> role(R role) {
      field.userRole(role);
      return this;
    }

    SearchField<R> build() {
      return field.build();
    }
  }
}
