package ccd.sdk.types;

import com.google.common.collect.Maps;
import lombok.Builder;
import lombok.Data;

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

    private FieldCollection.FieldCollectionBuilder<T, ?> fields;

    @Builder.Default
    private String endButtonLabel = "Save and continue";
    @Builder.Default
    private int displayOrder = -1;

    private Map<String, String> grants;
    public Map<String, String> getGrants() {
        return grants;
    }

    private Class dataClass;


    public static class EventBuilder<T> {

        public static <T> EventBuilder<T> builder(Class dataClass) {
            EventBuilder<T> result = new EventBuilder<T>();
            result.dataClass = dataClass;
            result.grants = Maps.newHashMap();
            result.fields = FieldCollection.FieldCollectionBuilder.builder(null, dataClass);
            return result;
        }

        public FieldCollection.FieldCollectionBuilder<T, ?> fields() {
            return fields;
        }

        public EventBuilder<T> forState(String state) {
            this.preState = state;
            this.postState = state;
            return this;
        }

        public EventBuilder<T> grant(String role, String crud) {
            grants.put(role, crud);
            return this;
        }
    }
}
