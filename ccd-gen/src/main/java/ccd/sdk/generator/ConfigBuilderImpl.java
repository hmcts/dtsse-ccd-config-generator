package ccd.sdk.generator;

import ccd.sdk.types.ConfigBuilder;
import ccd.sdk.types.FieldBuilder;
import ccd.sdk.types.FieldType;
import com.google.common.collect.Lists;

import java.util.List;

public class ConfigBuilderImpl implements ConfigBuilder {
    public final List<FieldBuilder> fields = Lists.newArrayList();

    @Override
    public FieldBuilder caseField(String id, FieldType type) {
        FieldBuilder builder = new FieldBuilder(id, type);
        fields.add(builder);
        return builder;
    }
}
