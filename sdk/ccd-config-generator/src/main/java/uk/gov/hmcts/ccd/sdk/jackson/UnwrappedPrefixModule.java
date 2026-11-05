package uk.gov.hmcts.ccd.sdk.jackson;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps a {@code @JsonUnwrapped(prefix = ...)} prefix on the unwrapped object's own properties.
 *
 * <p>When Jackson unwraps an object with a prefix, it also pushes the prefix into the
 * deserializers of that object's nested values, so a nested {@code Document}, {@code AddressUK},
 * {@code OrganisationPolicy} or a service's own nested object reads as empty and the case data is
 * lost. It happens whether the unwrapped object is built through setters or through a creator
 * such as a Lombok {@code @Builder @Jacksonized} class.
 *
 * <p>This module renames each property as before but leaves its value's deserializer alone,
 * unless the property is itself {@code @JsonUnwrapped}, where the prefix must carry on. Jackson
 * renames a property for unwrapping with {@code withSimpleName}, and then offers the renamed copy
 * an unwrapping value deserializer: from 2.19 inside {@code SettableBeanProperty.unwrapped}, on
 * 2.18 inside {@code BeanPropertyMap._rename}. A renamed copy here keeps the value deserializer
 * it already has, so both lines behave the same. Creator properties are replaced with a
 * {@link CreatorProperty} subclass that survives Jackson's own copies of the property, so the
 * same rule applies when the unwrapped object is built through a creator.
 *
 * <p>This is <a href="https://github.com/FasterXML/jackson-databind/issues/3178">jackson-databind
 * #3178</a>. It is fixed in Jackson 3.2.0 but not in Jackson 2. Remove this module once the SDK
 * runs on a Jackson 2 release that includes the fix, or on Jackson 3.2+.
 */
@Slf4j
public class UnwrappedPrefixModule extends SimpleModule {

  private static final long serialVersionUID = 1L;

  public UnwrappedPrefixModule() {
    super(UnwrappedPrefixModule.class.getName());
    setDeserializerModifier(new PrefixScopeModifier());
  }

  /**
   * Returns {@code mapper} if this module is registered on it, otherwise a copy with it
   * registered. Registering the module on a mapper that has already read a type does not reach
   * that type's cached deserializer, so the copy, which starts with an empty cache, is used
   * instead and the mapper itself is left as it is.
   */
  public static ObjectMapper ensureRegistered(ObjectMapper mapper) {
    if (mapper.getRegisteredModuleIds().contains(UnwrappedPrefixModule.class.getName())) {
      return mapper;
    }
    log.warn("An ObjectMapper without {} was created before the SDK could configure it; case data is read with"
        + " a copy that has it", UnwrappedPrefixModule.class.getSimpleName());
    return mapper.copy().registerModule(new UnwrappedPrefixModule());
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

  /**
   * A property that keeps the prefix off its value. Jackson only renames a bean property when it
   * unwraps the bean, so a renamed copy keeps the value deserializer it was renamed with.
   */
  static class PrefixScopedProperty extends SettableBeanProperty.Delegating implements PrefixScoped {

    private static final long serialVersionUID = 1L;

    private final boolean renamed;

    PrefixScopedProperty(SettableBeanProperty delegate) {
      this(delegate, false);
    }

    private PrefixScopedProperty(SettableBeanProperty delegate, boolean renamed) {
      super(delegate);
      this.renamed = renamed;
    }

    @Override
    protected SettableBeanProperty withDelegate(SettableBeanProperty delegate) {
      return new PrefixScopedProperty(delegate, renamed);
    }

    @Override
    public SettableBeanProperty withSimpleName(String simpleName) {
      SettableBeanProperty renamedDelegate = delegate.withSimpleName(simpleName);
      return renamedDelegate == delegate ? this : new PrefixScopedProperty(renamedDelegate, true);
    }

    @Override
    public SettableBeanProperty withValueDeserializer(JsonDeserializer<?> deserializer) {
      if (renamed && hasValueDeserializer()) {
        return this;
      }
      return super.withValueDeserializer(deserializer);
    }

    // Overrides SettableBeanProperty#unwrapped from jackson-databind 2.19; 7.x compiles against 2.18.
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

    private final boolean renamed;

    PrefixScopedCreatorProperty(CreatorProperty source) {
      super(source, source.getFullName());
      this.renamed = false;
    }

    private PrefixScopedCreatorProperty(PrefixScopedCreatorProperty source, PropertyName newName, boolean renamed) {
      super(source, newName);
      this.renamed = renamed;
    }

    private PrefixScopedCreatorProperty(PrefixScopedCreatorProperty source,
                                        JsonDeserializer<?> deserializer,
                                        NullValueProvider nullProvider) {
      super(source, deserializer, nullProvider);
      this.renamed = source.renamed;
    }

    @Override
    public SettableBeanProperty withName(PropertyName newName) {
      return new PrefixScopedCreatorProperty(this, newName, renamed);
    }

    @Override
    public SettableBeanProperty withSimpleName(String simpleName) {
      PropertyName newName = _propName == null ? new PropertyName(simpleName) : _propName.withSimpleName(simpleName);
      return newName == _propName ? this : new PrefixScopedCreatorProperty(this, newName, true);
    }

    @Override
    public SettableBeanProperty withValueDeserializer(JsonDeserializer<?> deserializer) {
      if (_valueDeserializer == deserializer || (renamed && hasValueDeserializer())) {
        return this;
      }
      NullValueProvider nullProvider = (_valueDeserializer == _nullProvider) ? deserializer : _nullProvider;
      return new PrefixScopedCreatorProperty(this, deserializer, nullProvider);
    }

    @Override
    public SettableBeanProperty withNullProvider(NullValueProvider nullProvider) {
      return new PrefixScopedCreatorProperty(this, _valueDeserializer, nullProvider);
    }

    // Overrides SettableBeanProperty#unwrapped from jackson-databind 2.19; 7.x compiles against 2.18.
    public SettableBeanProperty unwrapped(NameTransformer transformer) {
      return withSimpleName(transformer.transform(getName()));
    }
  }
}
