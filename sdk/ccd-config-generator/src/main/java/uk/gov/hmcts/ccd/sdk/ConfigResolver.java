package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import lombok.SneakyThrows;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

class ConfigResolver<T, S, R extends HasRole> {

  private static final String basePackage = "uk.gov.hmcts";

  private final Collection<CCDConfig<T, S, R>> configs;

  public ConfigResolver(Collection<CCDConfig<T, S, R>> configs) {
    if (configs.isEmpty()) {
      throw new RuntimeException("Expected at least one CCDConfig implementation but none found.");
    }
    this.configs = configs;
  }


  @SneakyThrows
  public ResolvedCCDConfig<T, S, R> resolveCCDConfig() {
    CCDConfig<T, S, R> config = this.configs.iterator().next();
    Class<?> userClass = ClassUtils.getUserClass(config);
    ResolvableType configType = ResolvableType.forClass(userClass).as(CCDConfig.class);
    @SuppressWarnings("unchecked")
    Class<T> caseType = (Class<T>) resolveGenericArgument(configType, 0, userClass);
    @SuppressWarnings("unchecked")
    Class<S> stateType = (Class<S>) resolveGenericArgument(configType, 1, userClass);
    @SuppressWarnings("unchecked")
    Class<R> roleType = (Class<R>) resolveGenericArgument(configType, 2, userClass);

    ImmutableSet<S> allStates = ImmutableSet.copyOf(stateType.getEnumConstants());
    Map<Class, Integer> types = resolve(caseType, basePackage);
    ResolvedCCDConfig<T, S, R> resolvedConfig =
        new ResolvedCCDConfig(caseType, stateType, roleType, types, allStates);
    resolvedConfig.resolveStateLabels();
    ConfigBuilderImpl<T, S, R> builder = new ConfigBuilderImpl(resolvedConfig);

    for (CCDConfig<T, S, R> c : configs) {
      c.configureDecentralised(builder);
    }

    return builder.build();
  }


  public static Map<Class, Integer> resolve(Class dataClass, String basePackage) {
    // Insertion-ordered: the generators iterate this map, and two reachable classes can share one
    // CCD ID (the same simple name in different packages, or the same @ComplexType(name)), in which
    // case they merge into one output file and whichever is visited first wins. A HashMap keyed on
    // Class orders by identity hash, which varies between JVM runs and shifts whenever an unrelated
    // type becomes reachable — so the definition generated from unchanged source was not stable.
    // Walk order is.
    Map<Class, Integer> result = Maps.newLinkedHashMap();
    resolve(dataClass, result, 0, Sets.newLinkedHashSet());
    result = Maps.filterKeys(result, x -> x.getPackageName().startsWith(basePackage));
    return result;
  }

  private static void resolve(
      Class dataClass, Map<Class, Integer> result, int level, Set<Class> path) {
    path.add(dataClass);
    ReflectionUtils.doWithFields(
        dataClass,
        field -> {
          // A field may name a type it does not declare, via @CCD(typeParameterClass): the field is a
          // String (or another leaf) carrying typeParameterOverride, and the class supplying the rows
          // for that list ID is reachable nowhere else. Resolve it alongside the declared type rather
          // than instead of it — a Collection<X> field can legitimately do both.
          Class declared = getComplexType(dataClass, field);
          for (Class c : new Class[] {declared, typeParameterClass(dataClass, field)}) {
            if (null == c || c.equals(dataClass)) {
              continue;
            }
            JsonUnwrapped unwrapped = field.getAnnotation(JsonUnwrapped.class);

            // unwrapped properties are automatically ignored as complex types
            if (null == unwrapped && (!result.containsKey(c) || result.get(c) < level)) {
              result.put(c, level);
            }
            // Only descend into a type not already on the current path. The walk covers EVERY field
            // type, not just those under basePackage (that filter is applied to the result, after
            // the walk), so it reaches JDK and third-party internals — and a reference cycle there
            // recursed until the stack was exhausted. A single java.util.Date field is enough:
            // Date -> BaseCalendar$Date -> Era -> CalendarDate -> Era (prl's Document.
            // documentCreatedOn). Guarding on the path rather than on every class visited keeps the
            // existing revisit-at-a-deeper-level behaviour that establishes ComplexTypes ordering
            // intact; only the infinite regress is cut.
            if (!path.contains(c)) {
              resolve(c, result, level + 1, path);
            }
          }
        },
        // Exclude every field the definition does not contain — @JsonIgnore, @CCD(ignore = true) and
        // an inactive @CCD(gate) alike — so a complex type reachable ONLY through such fields
        // contributes no ComplexTypes (or FixedLists) rows, matching how those fields are already
        // dropped from CaseField/AuthorisationCaseField/CaseEventToFields. An ignored field is not
        // part of the generated definition, so neither is a type nothing else reaches: emitting rows
        // for it declares a complex type no field references.
        //
        // Read through dataClass, the class this walk was entered with, so an inherited field the
        // definition configures per subclass is judged by the configuration that subclass supplies
        // (see FieldUtils#ccdAnnotation): a declaration marked ignore = true because MOST subclasses
        // have no row for it still reaches its types through the one subclass that does.
        field -> !Modifier.isStatic(field.getModifiers())
            && !FieldUtils.isFieldIgnored(dataClass, field));
    path.remove(dataClass);
  }

  /**
   * The class named by {@code @CCD(typeParameterClass)} on this field as reached through
   * {@code owner}, or null when unset. Void is the annotation's "unset" marker and never a reachable
   * type.
   *
   * <p>Read through {@code owner} rather than off the declaration, because an INHERITED field's
   * configuration can be supplied per subclass by a class-level {@code @CCD(member)} — and the class
   * supplying a fixed list's rows is then reachable only through that override. sscs's abstract
   * {@code Party} declares {@code ibcRole}, which the definition has a row for under
   * {@code appellant} only, so {@code Appellant} carries the whole
   * {@code typeOverride/typeParameterOverride/typeParameterClass} triple while the declaration itself
   * is {@code ignore = true}: reading the declaring field found no {@code typeParameterClass} at all
   * and {@code FL_ibcRoles} emitted no {@code FixedLists} rows.
   */
  private static Class<?> typeParameterClass(Class<?> owner, Field field) {
    CCD annotation = FieldUtils.ccdAnnotation(owner, field);
    if (annotation == null || Void.class.equals(annotation.typeParameterClass())) {
      return null;
    }
    return annotation.typeParameterClass();
  }

  public static Class getComplexType(Class c, Field field) {
    ResolvableType fieldType = ResolvableType.forField(field, c);
    if (Collection.class.isAssignableFrom(field.getType())) {
      ResolvableType elementType = fieldType.getGeneric(0);
      if (elementType.hasGenerics()) {
        elementType = elementType.getGeneric(0);
      }
      Class<?> resolved = elementType.resolve();
      if (resolved == null) {
        throw new IllegalStateException("Unable to resolve collection element type for %s.%s"
            .formatted(c.getName(), field.getName()));
      }
      return resolved;
    }
    // Resolve a non-collection field against the class we reached it through, exactly as the
    // collection branch above already does. field.getType() on a field whose type is a type
    // variable yields its ERASURE — the bound — not the type argument the subclass supplied. A
    // generic wrapper hierarchy (sscs: AbstractDocument<D extends AbstractDocumentDetails> with a
    // `private D value`, subclassed as AbstractDocument<SscsWelshDocumentDetails>) therefore
    // resolved every subclass's value field to the bound: the bound became reachable and emitted
    // its members under an ID no definition row names, while every type argument became reachable
    // nowhere and emitted nothing at all. Only a subclass separately reachable through some
    // concrete declaration survived. resolve() returns null for a variable no implementation class
    // binds (a genuinely raw or unresolvable use), so fall back to the erasure there.
    Class<?> resolved = fieldType.resolve();
    return resolved != null ? resolved : field.getType();
  }

  private static Class<?> resolveGenericArgument(
      ResolvableType type, int index, Class<?> sourceClass) {
    Class<?> resolved = type.getGeneric(index).resolve();
    if (resolved == null) {
      throw new IllegalStateException(
          "Unable to resolve generic argument %d for %s".formatted(index, sourceClass.getName()));
    }
    return resolved;
  }
}
