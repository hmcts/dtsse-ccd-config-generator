package ccd.sdk.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.cronn.reflection.util.PropertyUtils;
import de.cronn.reflection.util.TypedPropertyGetter;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class Field<T> {
    String id;
    String name;
    String description;
    String label;
    DisplayContext context;
    String showCondition;
    Object page;
    boolean showSummary;
    int pageFieldDisplayOrder;
    int pageDisplayOrder;
    String pageLabel;

    private Class dataClass;
    private FieldCollection.FieldCollectionBuilder<T> parent;

    public static class FieldBuilder<T> {
        public static <T> FieldBuilder<T> builder(Class dataclass, FieldCollection.FieldCollectionBuilder<T> parent) {
            FieldBuilder result = new FieldBuilder();
            result.dataClass = dataclass;
            result.parent = parent;
            return result;
        }

        public FieldBuilder<T> id(String id) {
            this.id = id;
            return this;
        }

        public FieldBuilder<T> id(TypedPropertyGetter<T, ?> getter) {
            JsonProperty j = PropertyUtils.getAnnotationOfProperty(dataClass, getter, JsonProperty.class);
            id = j != null ? j.value() : PropertyUtils.getPropertyName(dataClass, getter);
            return this;
        }

        public FieldCollection.FieldCollectionBuilder<T> done() {
            return parent;
        }
    }
}
