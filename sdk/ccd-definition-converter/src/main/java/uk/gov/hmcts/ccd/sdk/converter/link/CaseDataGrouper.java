package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uk.gov.hmcts.ccd.sdk.converter.model.ClusteredFieldRef;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;

/**
 * Splits a generated {@code CaseData} into {@code @JsonUnwrapped} sub-objects, so the root class
 * reads as an outline while the SDK still flattens every member back to its original CCD field ID.
 *
 * <p>Two group shapes, both one level deep:
 * <ul>
 *   <li><b>Prefixed</b> — fields sharing a leading camelCase word {@code p} ({@code appellantGivenNames},
 *       {@code appellantFamilyName}, …) become members of a class emitted as
 *       {@code @JsonUnwrapped(prefix = "p") P p}, each member named for the de-capitalised remainder
 *       ({@code givenNames}). The SDK derives {@code p + capitalize(member)} (FieldUtils.getFieldId),
 *       so the remainder must start upper-case; Jackson derives {@code p + member} verbatim, so the
 *       group class carries {@code @JsonNaming(UpperCamelCaseStrategy)} and the two agree.</li>
 *   <li><b>Prefix-less</b> — underscore-separated IDs ({@code progress_caseOfficer_…}) cannot be
 *       prefix-stripped, because the SDK would capitalise the segment after the underscore. They are
 *       grouped by their leading segments under a bare {@code @JsonUnwrapped}, keeping the full ID as
 *       the member name.</li>
 * </ul>
 *
 * <p>A field stays on {@code CaseData} when any typed SDK reference to it only accepts a
 * {@code CaseData} getter: the {@code AuthorisationComplexType} grant
 * ({@code grantComplexType(CaseData::getX, …)}), a {@code CaseTypeTab} row carrying a
 * {@code DisplayContextParameter} ({@code tab.field(CaseData::getX, cond, dcp)}), and a field with
 * {@code CaseEventToComplexTypes} member overrides or a {@code COMPLEX} placement (the derived member
 * scopes are not routed through a group hop in generate mode).
 */
final class CaseDataGrouper {

  /** Leading words that describe a predicate or an action rather than a thing the case holds. */
  static final Set<String> STOP_WORDS = Set.of(
      "is", "has", "have", "can", "should", "did", "does", "was", "are", "add", "make", "update",
      "send", "request", "apply", "upload", "change", "review", "list", "record", "current",
      "previous", "custom", "additional", "new", "other", "case");

  private static final Pattern CAMEL_HEAD = Pattern.compile("^([a-z][a-z0-9]*)([A-Z].*)$");
  private static final Pattern UNDERSCORE_HEAD =
      Pattern.compile("^([a-z][a-zA-Z0-9]*)_([a-zA-Z0-9]+)_.*$");
  private static final Pattern NEXT_WORD = Pattern.compile("^([A-Z][a-z0-9]*)([A-Z].*)$");
  private static final Pattern UNDERSCORE_FIRST = Pattern.compile("^([a-z][a-zA-Z0-9]*)_.*$");

  private final int minGroupSize;

  CaseDataGrouper(int minGroupSize) {
    this.minGroupSize = minGroupSize;
  }

  /** The grouped case fields, the group classes and how to reach each moved field. */
  record Result(List<FieldModel> caseFields, List<ComplexTypeModel> groups,
                Map<String, ClusteredFieldRef> refs) {
  }

  Result group(List<FieldModel> caseFields, Set<String> pinned, Set<String> usedTypeNames) {
    Set<String> rootNames = new LinkedHashSet<>();
    for (FieldModel f : caseFields) {
      rootNames.add(f.getJavaName());
      rootNames.add(f.getId());
    }

    // key -> (prefix or "" for prefix-less, members in input order)
    Map<String, List<FieldModel>> camel = new LinkedHashMap<>();
    Map<String, List<FieldModel>> underscore2 = new LinkedHashMap<>();
    Map<String, List<FieldModel>> underscore1 = new LinkedHashMap<>();
    for (FieldModel f : caseFields) {
      if (!groupable(f, pinned)) {
        continue;
      }
      Matcher u = UNDERSCORE_FIRST.matcher(f.getId());
      if (u.matches()) {
        Matcher u2 = UNDERSCORE_HEAD.matcher(f.getId());
        if (u2.matches()) {
          underscore2.computeIfAbsent(u2.group(1) + capitalise(u2.group(2)), k -> new ArrayList<>())
              .add(f);
        }
        underscore1.computeIfAbsent(u.group(1), k -> new ArrayList<>()).add(f);
        continue;
      }
      Matcher m = CAMEL_HEAD.matcher(f.getId());
      if (!m.matches() || STOP_WORDS.contains(m.group(1))) {
        continue;
      }
      if (!strippable(m.group(2))) {
        continue;
      }
      camel.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(f);
    }

    // groupKey -> group; fieldId -> groupKey
    Map<String, Group> groups = new LinkedHashMap<>();
    Map<String, Group> groupOf = new LinkedHashMap<>();
    camel.forEach((word, members) -> {
      String prefix = extend(word, members);
      if (members.size() >= minGroupSize && !rootNames.contains(prefix)) {
        Group g = new Group(prefix, prefix, uniqueTypeName(prefix, usedTypeNames));
        members.forEach(f -> g.add(f, groupOf));
        groups.put(prefix, g);
      }
    });
    // Prefix-less: the two-segment key when it is big enough, else the one-segment fallback.
    underscore2.forEach((key, members) -> {
      if (members.size() >= minGroupSize && !rootNames.contains(key)) {
        Group g = new Group(key, "", uniqueTypeName(key, usedTypeNames));
        members.forEach(f -> g.add(f, groupOf));
        groups.put(key, g);
      }
    });
    underscore1.forEach((key, members) -> {
      List<FieldModel> rest = members.stream().filter(f -> !groupOf.containsKey(f.getId())).toList();
      if (rest.size() >= minGroupSize && !rootNames.contains(key) && !groups.containsKey(key)) {
        Group g = new Group(key, "", uniqueTypeName(key, usedTypeNames));
        rest.forEach(f -> g.add(f, groupOf));
        groups.put(key, g);
      }
    });

    if (groups.isEmpty()) {
      return new Result(caseFields, List.of(), Map.of());
    }

    Map<String, ClusteredFieldRef> refs = new LinkedHashMap<>();
    List<ComplexTypeModel> types = new ArrayList<>();
    for (Group g : groups.values()) {
      List<FieldModel> members = new ArrayList<>();
      for (FieldModel f : g.members) {
        FieldModel member = g.prefix.isEmpty()
            ? f
            : f.toBuilder()
                .id(deCapitalise(f.getId().substring(g.prefix.length())))
                .javaName(deCapitalise(f.getId().substring(g.prefix.length())))
                .build();
        members.add(member);
        refs.put(f.getId(), ClusteredFieldRef.builder()
            .parentGetter("get" + capitalise(g.memberName))
            .clusterType(g.typeName)
            .memberGetter("get" + capitalise(member.getJavaName()))
            .label("Label".equals(f.getFieldType()))
            .build());
      }
      types.add(ComplexTypeModel.builder()
          .id(g.typeName)
          .javaClassName(g.typeName)
          .members(members)
          .depth(0)
          .build());
    }

    List<FieldModel> rewritten = new ArrayList<>();
    Set<String> inserted = new LinkedHashSet<>();
    for (FieldModel f : caseFields) {
      Group g = groupOf.get(f.getId());
      if (g == null) {
        rewritten.add(f);
      } else if (inserted.add(g.key)) {
        rewritten.add(FieldModel.builder()
            .id(g.memberName)
            .javaName(g.memberName)
            .fieldType("Complex")
            .fieldTypeParameter(g.typeName)
            .javaType(g.typeName)
            .unwrapPrefix(g.prefix)
            .overlayTags(Set.of())
            .accessClassNames(List.of())
            .build());
      }
    }
    return new Result(rewritten, types, refs);
  }

  /**
   * Lengthens a group's prefix one camelCase word at a time while every member shares the next
   * word, so {@code homeOffice*} groups as {@code homeOffice} rather than {@code home}.
   */
  private static String extend(String prefix, List<FieldModel> members) {
    while (true) {
      String next = null;
      for (FieldModel f : members) {
        Matcher m = NEXT_WORD.matcher(f.getId().substring(prefix.length()));
        String word = m.matches() ? m.group(1) : null;
        if (word == null || !strippable(m.group(2)) || (next != null && !next.equals(word))) {
          return prefix;
        }
        next = word;
      }
      if (next == null) {
        return prefix;
      }
      prefix = prefix + next;
    }
  }

  /**
   * Whether a remainder can become a member name the SDK and Jackson both map back to it. The SDK
   * derives {@code prefix + capitalize(member)}, so the remainder must start upper-case and
   * de-capitalise to a legal identifier. Jackson reads the Lombok getter and lower-cases its whole
   * leading capital run ({@code getHMCSecond} gives {@code hmcsecond}), so a remainder whose second
   * character is also upper-case would serialise under a different key; such a field stays flat.
   */
  private static boolean strippable(String remainder) {
    return remainder.length() > 1
        && Character.isUpperCase(remainder.charAt(0))
        && !Character.isUpperCase(remainder.charAt(1))
        && IdentifierSanitiser.isLegalIdentifier(deCapitalise(remainder));
  }

  private static boolean groupable(FieldModel f, Set<String> pinned) {
    return f.getUnwrapPrefix() == null
        && (f.getOverlayTags() == null || f.getOverlayTags().isEmpty())
        && f.getId().equals(f.getJavaName())
        && !pinned.contains(f.getId());
  }

  private static String uniqueTypeName(String key, Set<String> used) {
    String candidate = capitalise(key);
    if (used.contains(candidate)) {
      candidate = candidate + "Fields";
    }
    int n = 2;
    String base = candidate;
    while (used.contains(candidate)) {
      candidate = base + n++;
    }
    used.add(candidate);
    return candidate;
  }

  private static String capitalise(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private static String deCapitalise(String s) {
    return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  private static final class Group {
    final String key;
    final String prefix;
    final String typeName;
    final String memberName;
    final List<FieldModel> members = new ArrayList<>();

    Group(String key, String prefix, String typeName) {
      this.key = key;
      this.prefix = prefix;
      this.typeName = typeName;
      this.memberName = key;
    }

    void add(FieldModel f, Map<String, Group> groupOf) {
      members.add(f);
      groupOf.put(f.getId(), this);
    }
  }
}
