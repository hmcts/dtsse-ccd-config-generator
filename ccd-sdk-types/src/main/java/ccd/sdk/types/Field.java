package ccd.sdk.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.cronn.reflection.util.ClassUtils;
import de.cronn.reflection.util.PropertyUtils;
import de.cronn.reflection.util.TypedPropertyGetter;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.beans.PropertyDescriptor;

@Builder
@Data
public class Field<T, Parent> {
    String id;
    String name;
    String description;
    String label;
    String hint;
    DisplayContext context;
    String showCondition;
    Object page;
    boolean showSummary;
    int fieldDisplayOrder;
    int pageFieldDisplayOrder;
    int pageDisplayOrder;
    String pageLabel;
    String type;
    String fieldTypeParameter;

    private Class dataClass;
    @ToString.Exclude
    private FieldCollection.FieldCollectionBuilder<T, Parent> parent;

    public static class FieldBuilder<T, Parent> {
        public static <T, Parent> FieldBuilder<T, Parent> builder(Class dataclass, FieldCollection.FieldCollectionBuilder<T, ?> parent) {
            FieldBuilder result = new FieldBuilder();
            result.dataClass = dataclass;
            result.parent = parent;
            return result;
        }

        protected FieldBuilder<T, Parent> id(String id) {
            this.id = id;
            return this;
        }

        protected FieldBuilder<T, Parent> type(String t) {
            this.type = t;
            return this;
        }

        public FieldBuilder<T, Parent> id(TypedPropertyGetter<T, ?> getter) {
            JsonProperty j = PropertyUtils.getAnnotationOfProperty(dataClass, getter, JsonProperty.class);
            PropertyDescriptor details = PropertyUtils.getPropertyDescriptor(dataClass, getter);
            id = j != null ? j.value() : details.getName();

            CaseField cf = PropertyUtils.getAnnotationOfProperty(dataClass, getter, CaseField.class);
            if (null != cf) {
                label = cf.label();
                hint = cf.hint();
            }
            return this;
        }

        public FieldCollection.FieldCollectionBuilder<T, Parent> done() {
            return (FieldCollection.FieldCollectionBuilder<T, Parent>) parent;
        }
    }
}
