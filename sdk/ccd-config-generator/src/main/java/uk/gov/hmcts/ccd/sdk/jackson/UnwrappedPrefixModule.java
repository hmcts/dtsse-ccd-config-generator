package uk.gov.hmcts.ccd.sdk.jackson;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.CreatorProperty;
import com.fasterxml.jackson.databind.deser.NullValueProvider;
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
 * properties of nested types too, so a nested {@code Document}, {@code AddressUK} or
 * {@code OrganisationPolicy} reads as empty and the case data is lost. It happens whether the
 * unwrapped object is built through setters or through a creator such as a Lombok
 * {@code @Builder @Jacksonized} class.
 *
 * <p>This module renames each property as before but leaves its value's deserializer alone,
 * unless the property is itself {@code @JsonUnwrapped}, where the prefix must carry on. It uses
 * {@link SettableBeanProperty#unwrapped(NameTransformer)}, which Jackson calls from 2.19. Older
 * versions never call it, so the module has no effect there. Creator properties are replaced
 * with a {@link CreatorProperty} subclass that survives Jackson's own copies of the property, so
 * the same rule applies when the unwrapped object is built through a creator.
 *
 * <p>This is <a href="https://github.com/FasterXML/jackson-databind/issues/3178">jackson-databind
 * #3178</a>. It is fixed in Jackson 3.2.0 but not in Jackson 2. Remove this module once the SDK
 * runs on a Jackson 2 release that includes the fix, or on Jackson 3.2+.
 */
public class UnwrappedPrefixModule extends SimpleModule {

  private static final long serialVersionUID = 1L;

  public UnwrappedPrefixModule() {
    super(UnwrappedPrefixModule.class.getName());
    setDeserializerModifier(new PrefixScopeModifier());
  }

  static class PrefixScopeModifier extends BeanDeserializerModifier {

    private static final long serialVersionUID = 1L;

    @Override
    public BeanDeserializerBuilder updateBuilder(DeserializationConfig config,
                                                 BeanDescription beanDesc,
                                                 BeanDeserializerBuilder builder) {
      AnnotationIntrospector introspector = config.getAnnotationIntrospector();
      List<SettableBeanProperty> scoped = new ArrayList<>();
      builder.getProperties().forEachRemaining(property -> {
        if (property instanceof PrefixScoped || isUnwrapped(introspector, property)) {
          return;
        }
        if (property instanceof CreatorProperty creatorProperty) {
          scoped.add(new PrefixScopedCreatorProperty(creatorProperty));
        } else {
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

  /** Marks a property whose value deserializer must not receive an unwrap prefix. */
  interface PrefixScoped {
  }

  static class PrefixScopedProperty extends SettableBeanProperty.Delegating implements PrefixScoped {

    private static final long serialVersionUID = 1L;

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

  /**
   * A creator property that keeps the prefix off its value. Jackson resolves creator properties
   * by identity and copies them with {@code withName}, {@code withValueDeserializer} and
   * {@code withNullProvider}, so each copy must stay an instance of this class.
   */
  static class PrefixScopedCreatorProperty extends CreatorProperty implements PrefixScoped {

    private static final long serialVersionUID = 1L;

    PrefixScopedCreatorProperty(CreatorProperty source) {
      super(source, source.getFullName());
    }

    private PrefixScopedCreatorProperty(CreatorProperty source, PropertyName newName) {
      super(source, newName);
    }

    private PrefixScopedCreatorProperty(CreatorProperty source,
                                        JsonDeserializer<?> deserializer,
                                        NullValueProvider nullProvider) {
      super(source, deserializer, nullProvider);
    }

    @Override
    public SettableBeanProperty withName(PropertyName newName) {
      return new PrefixScopedCreatorProperty(this, newName);
    }

    @Override
    public SettableBeanProperty withValueDeserializer(JsonDeserializer<?> deserializer) {
      if (_valueDeserializer == deserializer) {
        return this;
      }
      NullValueProvider nullProvider = (_valueDeserializer == _nullProvider) ? deserializer : _nullProvider;
      return new PrefixScopedCreatorProperty(this, deserializer, nullProvider);
    }

    @Override
    public SettableBeanProperty withNullProvider(NullValueProvider nullProvider) {
      return new PrefixScopedCreatorProperty(this, _valueDeserializer, nullProvider);
    }

    @Override
    public SettableBeanProperty unwrapped(NameTransformer transformer) {
      return withSimpleName(transformer.transform(getName()));
    }
  }
}
