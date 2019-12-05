package ccd.sdk.types;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import de.cronn.reflection.util.PropertyUtils;
import de.cronn.reflection.util.TypedPropertyGetter;
import lombok.*;
import net.jodah.typetools.TypeResolver;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

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
    private String retries;
    @Builder.Default
    private int displayOrder = -1;

    private Map<String, String> grants;
    public Map<String, String> getGrants() {
        return grants;
    }

    private Class dataClass;


    private Map<String, DisplayContext> fields;

    public static <T> EventBuilder<T> builder(Class dataClass) {
        EventBuilder<T> result = new EventBuilder<T>();
        result.dataClass = dataClass;
        result.fields = Maps.newHashMap();
        result.grants = Maps.newHashMap();
        return result;
    }

    public static class EventBuilder<T> {

        public EventBuilder<T> forState(String state) {
            this.preState = state;
            this.postState = state;
            return this;
        }

        public EventBuilder<T> grant(String role, String crud) {
            grants.put(role, crud);
            return this;
        }

        public EventBuilder<T> field(TypedPropertyGetter<T, ?> getter, DisplayContext context) {
            String fieldName = PropertyUtils.getPropertyName(dataClass, getter);
            fields.put(fieldName, context);
            return this;
        }
    }
}
