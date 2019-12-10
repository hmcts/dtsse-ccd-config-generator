package ccd.sdk.types;

import ccd.sdk.types.Field.FieldBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import de.cronn.reflection.util.PropertyUtils;
import de.cronn.reflection.util.TypedPropertyGetter;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.beans.PropertyDescriptor;
import java.util.List;
import java.util.Map;

@Builder
@Data
public class FieldCollection<T, Parent> {
    private List<Field.FieldBuilder> fields;
    private List<FieldCollectionBuilder<T, Parent>> complexFields;
    private String rootFieldname;

    public static class FieldCollectionBuilder<T, Parent> {
        private Class dataClass;

        private Object pageId;
        private int order;
        private int pageDisplayOrder;
        private int fieldDisplayOrder;
        private String pageLabel;
        private FieldCollectionBuilder<Parent, ?> parent;

        public static <T, Parent> FieldCollectionBuilder<T, Parent> builder(FieldCollectionBuilder<Parent, ?> parent, Class<T> dataClass) {
            FieldCollectionBuilder<T, Parent> result = new FieldCollectionBuilder<T, Parent>();
            result.parent = parent;
            result.dataClass = dataClass;
            result.fields = Lists.newArrayList();
            result.complexFields = Lists.newArrayList();
            return result;
        }

        public FieldCollectionBuilder<T, Parent> field(String id, DisplayContext context, String showCondition) {
            field().id(id).context(context).showCondition(showCondition);
            return this;
        }

        public FieldCollectionBuilder<T, Parent> field(String fieldName, DisplayContext context) {
            field().id(fieldName).context(context);
            return this;
        }

        public FieldCollectionBuilder<T, Parent> field(TypedPropertyGetter<T, ?> getter) {
            return field(getter, null);
        }

        public FieldCollectionBuilder<T, Parent> field(TypedPropertyGetter<T, ?> getter, DisplayContext context) {
            return field(getter, context, false);
        }

        public FieldCollectionBuilder<T, Parent> field(TypedPropertyGetter<T, ?> getter, DisplayContext context, String showCondition) {
            field().id(getter).context(context).showCondition(showCondition);
            return this;
        }

        public FieldCollectionBuilder<T, Parent> field(TypedPropertyGetter<T, ?> getter, DisplayContext context, boolean showSummary) {
            field().id(getter).context(context).showSummary(showSummary);
            return this;
        }

        public FieldCollectionBuilder<Parent, ?> done() {
            return parent;
        }

        public <U> FieldCollectionBuilder<U, T> complex(TypedPropertyGetter<T, U> getter) {
            PropertyDescriptor descriptor = PropertyUtils.getPropertyDescriptor(dataClass, getter);
            return complex(getter, (Class<U>) descriptor.getPropertyType());
        }

        public <U> FieldCollectionBuilder<U, T> complex(TypedPropertyGetter<T, ?> getter, Class<U> c) {
            String fieldName = PropertyUtils.getPropertyName(dataClass, getter);
            FieldCollectionBuilder<T, Parent> result = (FieldCollectionBuilder<T, Parent>) FieldCollectionBuilder.builder(this, c);
            complexFields.add(result);
            result.rootFieldname = fieldName;
            result.fieldDisplayOrder = this.fieldDisplayOrder;
            if (null == this.rootFieldname) {
                // Register only the root complex as a field
                field().id(getter).showSummary(true);
            }
            return (FieldCollectionBuilder<U, T>) result;
        }

        public FieldCollectionBuilder<T, Parent> label(String id, String value) {
            field().id(id).context(DisplayContext.ReadOnly).label(value);
            return this;
        }

        public FieldBuilder<T> field() {
            FieldBuilder<T> f = FieldBuilder.builder(dataClass, this);
            f.page(this.pageId);
            f.pageLabel(this.pageLabel);
            if (this.pageId != null) {
                f.pageLabel(" ");
            }
            fields.add(f);
            f.fieldDisplayOrder(++fieldDisplayOrder);
            f.pageFieldDisplayOrder(++order);
            f.pageDisplayOrder(Math.max(1, this.pageDisplayOrder));
            return f;
        }

        public FieldCollectionBuilder<T, Parent> page(String id) {
            return this.pageObj(id);
        }

        public FieldCollectionBuilder<T, Parent> page(int id) {
            return this.pageObj(id);
        }

        private FieldCollectionBuilder<T, Parent> pageObj(Object id) {
            this.pageId = id;
            this.order = 0;
            this.fieldDisplayOrder = 0;
            this.pageDisplayOrder++;
            return this;
        }

        public FieldCollectionBuilder<T, Parent> pageLabel(String id) {
            this.pageLabel = id;
            return this;
        }

    }
}
