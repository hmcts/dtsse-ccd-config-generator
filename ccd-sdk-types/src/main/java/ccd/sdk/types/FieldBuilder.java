package ccd.sdk.types;

import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;

public class FieldBuilder {
    public final String id;
    public final FieldType type;
    public FieldBuilder(String id, FieldType type) {
        this.id = id;
        this.type = type;
    }
    public List<List<String>> labels = new ArrayList<>();
    public FieldBuilder label(String id, String label) {
        labels.add(Lists.newArrayList(id, label));
        return this;
    }
}
