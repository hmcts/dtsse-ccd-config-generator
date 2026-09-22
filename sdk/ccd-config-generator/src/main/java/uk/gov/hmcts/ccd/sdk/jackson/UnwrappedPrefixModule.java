package uk.gov.hmcts.ccd.sdk.jackson;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.CreatorProperty;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.util.NameTransformer;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a {@code @JsonUnwrapped(prefix = ...)} prefix on the unwrapped object's own properties.
 *
 * <p>When Jackson unwraps an object with a prefix, it also pushes the prefix into the
 * deserializers of that object's nested values. From jackson-databind 2.19 this renames the
 * {@code @JsonCreator} parameters of nested types too, so a nested {@code Document},
 * {@code AddressUK} or {@code OrganisationPolicy} reads as empty and the case data is lost.
 * Setter-based nested types have been affected in every version.
 *
 * <p>This module renames each property as before but leaves its value's deserializer alone,
 * unless the property is itself {@code @JsonUnwrapped}, where the prefix must carry on. It uses
 * {@link SettableBeanProperty#unwrapped(NameTransformer)}, which Jackson calls from 2.19. Older
 * versions never call it, so the module has no effect there.
 *
 * <p>This is <a href="https://github.com/FasterXML/jackson-databind/issues/3178">jackson-databind
 * #3178</a>. It is fixed in Jackson 3.2.0 but not in Jackson 2. Remove this module once the SDK
 * runs on a Jackson 2 release that includes the fix, or on Jackson 3.2+.
 */
public class UnwrappedPrefixModule extends SimpleModule {

  public UnwrappedPrefixModule() {
    super(UnwrappedPrefixModule.class.getName());
    setDeserializerModifier(new PrefixScopeModifier());
  }

  static class PrefixScopeModifier extends BeanDeserializerModifier {

    @Override
    public BeanDeserializerBuilder updateBuilder(DeserializationConfig config,
                                                 BeanDescription beanDesc,
                                                 BeanDeserializerBuilder builder) {
      AnnotationIntrospector introspector = config.getAnnotationIntrospector();
      List<SettableBeanProperty> scoped = new ArrayList<>();
      builder.getProperties().forEachRemaining(property -> {
        if (!(property instanceof CreatorProperty)
            && !(property instanceof PrefixScopedProperty)
            && !isUnwrapped(introspector, property)) {
          scoped.add(new PrefixScopedProperty(property));
        }
      });
      scoped.forEach(property -> builder.addOrReplaceProperty(property, true));
      return builder;
    }

    private static boolean isUnwrapped(AnnotationIntrospector introspector, SettableBeanProperty property) {
      AnnotatedMember member = property.getMember();
      return introspector != null && member != null
          && introspector.findUnwrappingNameTransformer(member) != null;
    }
  }

  static class PrefixScopedProperty extends SettableBeanProperty.Delegating {

    PrefixScopedProperty(SettableBeanProperty delegate) {
      super(delegate);
    }

    @Override
    protected SettableBeanProperty withDelegate(SettableBeanProperty delegate) {
      return new PrefixScopedProperty(delegate);
    }

    @Override
    public SettableBeanProperty unwrapped(NameTransformer transformer) {
      return withSimpleName(transformer.transform(getName()));
    }
  }
}
