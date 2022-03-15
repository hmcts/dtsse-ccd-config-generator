package uk.gov.hmcts.ccd.sdk.api;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class Tab<T, R extends HasRole> {
  private String tabID;
  private String labelText;
  private String showCondition;
  private int displayOrder;
  private List<TabField> fields;
  private Set<R> forRoles;

  public List<String> getForRolesAsString() {
    return forRoles.stream().map(HasRole::getRole).sorted().collect(toList());
  }

  public static class TabBuilder<T, R extends HasRole> {
    private Class<T> model;
    private PropertyUtils propertyUtils;

    public static <T, R extends HasRole> TabBuilder<T, R> builder(Class<T> model,
        PropertyUtils propertyUtils) {
      TabBuilder<T, R> result = Tab.builder();
      result.model = model;
      result.propertyUtils = propertyUtils;
      result.fields = new ArrayList<>();
      result.forRoles = new HashSet<>();
      return result;
    }

    public TabBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String showCondition, String displayContext) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(
          TabField.builder().id(name).showCondition(showCondition).displayContextParameter(displayContext).build());
      return this;
    }

    public TabBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String showCondition) {
      return field(getter, showCondition, null);
    }

    public TabBuilder<T, R> field(TypedPropertyGetter<T, ?> getter) {
      return field(getter, null);
    }

    public TabBuilder<T, R> field(String fieldName) {
      fields.add(TabField.builder().id(fieldName).build());
      return this;
    }

    public TabBuilder<T, R> field(String fieldName, String showCondition) {
      fields.add(TabField.builder().id(fieldName).showCondition(showCondition).build());
      return this;
    }

    public TabBuilder<T, R> label(String fieldName, String showCondition, String label) {
      fields.add(TabField.builder().id(fieldName).showCondition(showCondition).label(label).build());
      return this;
    }

    public TabBuilder<T, R> forRoles(R... roles) {
      forRoles.addAll(asList(roles));

      return this;
    }

    public TabBuilder<T, R> collection(TypedPropertyGetter<T, ? extends Collection> getter) {
      return field(getter, null);
    }
  }
}
