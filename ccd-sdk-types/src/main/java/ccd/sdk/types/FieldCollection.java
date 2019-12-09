package ccd.sdk.types;

import ccd.sdk.types.Field.FieldBuilder;
import com.google.common.collect.Lists;
import de.cronn.reflection.util.TypedPropertyGetter;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Builder
@Data
public class FieldCollection<T> {
    private List<Field.FieldBuilder> fields;

    public static class FieldCollectionBuilder<T> {
        private Class dataClass;

        private Object pageId;
        private int order;
        private int pageDisplayOrder;
        private String pageLabel;

        public static <T> FieldCollectionBuilder<T> builder(Class<T> dataClass) {
            FieldCollectionBuilder<T> result = new FieldCollectionBuilder<T>();
            result.dataClass = dataClass;
            result.fields = Lists.newArrayList();
            return result;
        }

        public FieldCollectionBuilder<T> field(String id, DisplayContext context, String showCondition) {
            field().id(id).context(context).showCondition(showCondition);
            return this;
        }

        public FieldCollectionBuilder<T> field(String fieldName, DisplayContext context) {
            field().id(fieldName).context(context);
            return this;
        }

        public FieldCollectionBuilder<T> field(TypedPropertyGetter<T, ?> getter) {
            return field(getter, null);
        }

        public FieldCollectionBuilder<T> field(TypedPropertyGetter<T, ?> getter, DisplayContext context) {
            return field(getter, context, false);
        }

        public FieldCollectionBuilder<T> field(TypedPropertyGetter<T, ?> getter, DisplayContext context, boolean showSummary) {
            field().id(getter).context(context).showSummary(showSummary);
            return this;
        }

        public FieldCollectionBuilder<T> label(String id, String value) {
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
            f.pageFieldDisplayOrder(++order);
            f.pageDisplayOrder(Math.max(1, this.pageDisplayOrder));
            return f;
        }

        public FieldCollectionBuilder<T> page(String id) {
            this.pageId = id;
            this.order = 0;
            this.pageDisplayOrder++;
            return this;
        }

        public FieldCollectionBuilder<T> page(int id) {
            this.pageId = id;
            this.order = 0;
            this.pageDisplayOrder++;
            return this;
        }

        public FieldCollectionBuilder<T> pageLabel(String id) {
            this.pageLabel = id;
            return this;
        }
    }
}
