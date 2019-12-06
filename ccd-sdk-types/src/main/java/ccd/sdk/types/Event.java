package ccd.sdk.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import de.cronn.reflection.util.PropertyUtils;
import de.cronn.reflection.util.TypedPropertyGetter;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Builder
@Data
public class Event<T> {
    private String id;
    private String name;
    private String preState;
    private String postState;
    private String description;
    private String aboutToStartURL;
    private String aboutToSubmitURL;
    private String submittedURL;
    private String midEventURL;
    private String retries;
    @Builder.Default
    private String endButtonLabel = "Save and continue";
    @Builder.Default
    private int displayOrder = -1;

    private Map<String, String> grants;
    public Map<String, String> getGrants() {
        return grants;
    }

    private Class dataClass;


    private List<Field.FieldBuilder> fields;

    public static <T> EventBuilder<T> builder(Class dataClass) {
        EventBuilder<T> result = new EventBuilder<T>();
        result.dataClass = dataClass;
        result.fields = Lists.newArrayList();
        result.grants = Maps.newHashMap();
        return result;
    }

    public static class EventBuilder<T> {

        private Object pageId;
        private int order;
        private int pageDisplayOrder;
        private String pageLabel;

        public EventBuilder<T> forState(String state) {
            this.preState = state;
            this.postState = state;
            return this;
        }

        public EventBuilder<T> grant(String role, String crud) {
            grants.put(role, crud);
            return this;
        }

        public EventBuilder<T> page(String id) {
            this.pageId = id;
            this.order = 0;
            this.pageDisplayOrder++;
            return this;
        }

        public EventBuilder<T> page(int id) {
            this.pageId = id;
            this.order = 0;
            this.pageDisplayOrder++;
            return this;
        }

        public EventBuilder<T> pageLabel(String id) {
            this.pageLabel = id;
            return this;
        }

        public EventBuilder<T> field(String id, DisplayContext context, String showCondition) {
            field().id(id).context(context).showCondition(showCondition);
            return this;
        }

        public EventBuilder<T> field(String fieldName, DisplayContext context) {
            field().id(fieldName).context(context);
            return this;
        }

        public EventBuilder<T> field(TypedPropertyGetter<T, ?> getter) {
            return field(getter, null);
        }

        public EventBuilder<T> field(TypedPropertyGetter<T, ?> getter, DisplayContext context) {
            return field(getter, context, false);
        }

        public EventBuilder<T> field(TypedPropertyGetter<T, ?> getter, DisplayContext context, boolean showSummary) {
            field().id(getter).context(context).showSummary(showSummary);
            return this;
        }

        public EventBuilder<T> label(String id, String value) {
            field().id(id).context(DisplayContext.ReadOnly).label(value);
            return this;
        }

        public Field.FieldBuilder<T> field() {
            Field.FieldBuilder<T> f = Field.FieldBuilder.builder(dataClass, this);
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
    }
}
