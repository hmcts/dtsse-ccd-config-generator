package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import uk.gov.hmcts.ccd.sdk.converter.model.AccessClassModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

/**
 * Derives, per case field, the access grants that reproduce the AuthorisationCaseField sheet on top
 * of the grants the SDK's AuthorisationCaseFieldGenerator injects automatically (event,
 * caseHistory, tab/search read). Every role the injected baseline leaves short of its definition
 * grant is part of the field's <em>residual</em> grant map (role id to CRUD), which must be
 * expressed on the field's {@code @CCD(access = {...})}.
 *
 * <p><b>Normalisation.</b> A residual role carries its <em>full</em> definition CRUD, not just the
 * permissions the injected baseline lacks. The SDK unions a field's class grants with what it
 * injects, and the injected grant is always a subset of the definition grant (anything else is the
 * advisory over-grant recorded in {@link #residual}), so the union is unchanged — but two fields
 * granting a role the same CRUD now share one atom whatever the SDK happens to inject on each.
 *
 * <p><b>Emission policy — composition (nfdiv-style).</b> Instead of minting one class per distinct
 * residual pattern (thousands of single-use classes across the big case types), the converter
 * expresses every residual as a <em>union of named access-group classes</em>, exactly as a
 * hand-written HMCTS model does ({@code @CCD(access = {CaseworkerCruAccess.class,
 * CitizenRAccess.class})}). This exploits the SDK's proven additive composition: a field's effective
 * grant for a role is the union of the permissions from every class it names (see
 * {@code AuthorisationCaseFieldGenerator.addPermissionsFromFields}, which merges each class's
 * {@code getGrants()} into the same field/role cell). Class names never appear in the emitted
 * AuthorisationCaseField — only the resolved grants do — so any partition of a residual into classes
 * whose grants union back to it round-trips byte-identically.
 *
 * <p>The decomposition is deterministic:
 *
 * <ol>
 *   <li><b>Atoms.</b> The finest grain is one class per distinct {@code (role -> CRUD)} pair that
 *       actually occurs — small and semantic ({@code CaseworkerCruAccess}, {@code CitizenRAccess}).
 *       A residual with distinct roles decomposes into one atom per role.</li>
 *   <li><b>Groups.</b> Frequently co-occurring atom-sets that grant one CRUD level to all their
 *       roles are mined into named bundles by a greedy frequent-itemset pass — the bundle covering
 *       the most residual across the case type is carved out first, then the pass repeats on what
 *       remains. A group must be used by at least {@link #MIN_GROUP_FIELDS} fields, contain at least
 *       {@link #MIN_GROUP_ATOMS} atoms and have a semantic name to be mined. Each is named from its
 *       content ({@code JudgeLegalrepCruAccess}).</li>
 *   <li><b>Per-field cover.</b> Each residual is covered by the groups that carved it plus any
 *       leftover atoms.</li>
 *   <li><b>Combined classes.</b> A cover longer than {@link #MAX_CLASSES_PER_FIELD} is shortened in
 *       two steps. First, classes several over-cap covers list in common are folded into one
 *       combined class they all share (any mix of CRUD levels, readable names only; see
 *       {@link #mineCombined}). Then each cover still over the cap keeps its
 *       {@code MAX_CLASSES_PER_FIELD - 1} widest classes and folds the rest into one combined
 *       class, shared by every cover folding the same tail.</li>
 * </ol>
 *
 * <p>Only classes actually referenced by some field's cover are emitted (an atom always folded into a
 * group is never minted standalone). The per-case-type class counts and per-field array distribution
 * are recorded in {@link Result#summaryNote()} for the converter report.
 *
 * <p><b>Names.</b> A role id is split into lower-case alphanumeric tokens on every other character
 * ({@code caseworker-ia-legalrep-solicitor}, {@code [CHILDSOLICITORA]}). Service teams' roles are
 * namespaced, so up to {@link #MAX_PREFIX_STAGES} leading tokens are elided case-type-wide, one per
 * pass (see {@link #computePrefixStages}): {@code caseworker-ia-legalrep-solicitor} names as
 * {@code LegalrepSolicitor}. A multi-role name lists its roles grouped by CRUD (widest first), writes
 * each CRUD token once after its run, factors a run of roles sharing leading tokens
 * ({@code specific-access-admin, specific-access-judge} -> {@code SpecificAccessAdminJudge}) and
 * collapses a one-letter series ({@code [CHILDSOLICITORA]}..{@code [CHILDSOLICITORO]} ->
 * {@code ChildsolicitorAToO}). A name over {@link #MAX_SEMANTIC_NAME} characters is truncated to the
 * first role's token plus a role count and a short stable digest. Grants always key on the full role
 * id — only the derived class <em>name</em> is affected by any of this.
 */
final class AccessClassComputer {

  /**
   * The minimum share of the case type's non-case-role roles (participating in some residual) that
   * must start with a token, with something after it, for that token to be elided from names.
   */
  static final double PREFIX_MIN_SHARE = 0.4;

  /**
   * The minimum number of roles that must carry the candidate prefix token, on top of clearing
   * {@link #PREFIX_MIN_SHARE}. Guards tiny case types where a "shared" token is a coincidence of a
   * small sample rather than a real namespace convention.
   */
  static final int MIN_PREFIX_ROLES = 3;

  /** How many leading tokens prefix elision may strip, one per pass. */
  static final int MAX_PREFIX_STAGES = 3;

  /** A group must be used by at least this many fields to earn a name (else its atoms stand alone). */
  static final int MIN_GROUP_FIELDS = 3;

  /** A group must bundle at least this many atoms (a one-atom "group" is just an atom class). */
  static final int MIN_GROUP_ATOMS = 2;

  /**
   * The most classes a field's {@code @CCD(access)} array lists (see the combined classes).
   */
  static final int MAX_CLASSES_PER_FIELD = 5;

  /** Safety bound on mined groups so a pathological input cannot loop unbounded. */
  private static final int MAX_GROUPS = 1024;

  private static final int MAX_SEMANTIC_NAME = 70;

  /**
   * The minimum number of distinct roles a case type must have before prefix elision applies. With a
   * single role there is nothing to compare it against, so it keeps its full token form.
   */
  private static final int MIN_ROLES_FOR_COMMON_PREFIX = 2;

  private final GapCollector gaps;

  /**
   * The leading tokens elided from role names, in pass order (e.g. {@code ["caseworker", "ia"]}),
   * computed once per {@link #compute} call by {@link #computePrefixStages}.
   */
  private List<String> prefixStages = List.of();

  AccessClassComputer(GapCollector gaps) {
    this.gaps = gaps;
  }

  /** One atomic grant: a single role paired with the CRUD it is granted. */
  private record Atom(String role, String crud) {
    String signature() {
      return role + '=' + crud;
    }
  }

  /**
   * A distinct residual pattern: its atom-set, the fields that carry it, plus the running cover the
   * mining pass builds up (the groups that carved it, in order) and the atoms still uncovered.
   */
  private static final class Pattern {
    private final Set<Atom> atoms;
    private final List<String> fieldIds = new ArrayList<>();
    private final List<Integer> coverGroups = new ArrayList<>();
    private final Set<Atom> uncovered;

    Pattern(Set<Atom> atoms) {
      this.atoms = atoms;
      this.uncovered = new LinkedHashSet<>(atoms);
    }

    int weight() {
      return fieldIds.size();
    }
  }

  /**
   * Computes the per-field residual grants and expresses each as a union of named access classes
   * (atoms + mined groups, with combined classes for over-wide residuals).
   *
   * @param fieldIds the case field IDs in declaration order
   * @param desired the AuthorisationCaseField grants: field ID to role to CRUD string
   * @param injected the SDK-injected grants: field ID to role to CRUD string
   * @return the derivation result: the emitted classes, per-field class-name lists and the summary
   */
  Result compute(
      List<String> fieldIds,
      Map<String, Map<String, String>> desired,
      Map<String, Map<String, String>> injected) {
    return compose(residuals(fieldIds, desired, injected));
  }

  /**
   * Each field's residual grant map (see {@link #residual}), in {@code fieldIds} order. Records the
   * advisory over-grant gaps, so call it once per conversion.
   */
  Map<String, Map<String, String>> residuals(
      List<String> fieldIds,
      Map<String, Map<String, String>> desired,
      Map<String, Map<String, String>> injected) {
    Map<String, Map<String, String>> residuals = new LinkedHashMap<>();
    for (String fieldId : fieldIds) {
      Map<String, String> want = desired.getOrDefault(fieldId, Map.of());
      Map<String, String> have = injected.getOrDefault(fieldId, Map.of());
      residuals.put(fieldId, residual(fieldId, want, have));
    }
    return residuals;
  }

  /**
   * Expresses each unit's grant map as a union of named access classes. A unit is usually a case
   * field, but may be anything carrying an {@code @CCD(access)} list — e.g. a group container's
   * base access.
   *
   * @param unitResiduals unit id to the grant map (role to CRUD) its access list must union to
   * @return the classes and each unit's class-name list
   */
  Result compose(Map<String, Map<String, String>> unitResiduals) {
    // 1. Residual per unit, decomposed into atoms; group identical residuals into distinct patterns.
    Map<String, Pattern> patternsBySignature = new LinkedHashMap<>();
    Map<String, List<String>> fieldToClassNames = new LinkedHashMap<>();
    int fieldsWithResidual = 0;

    for (Map.Entry<String, Map<String, String>> unit : unitResiduals.entrySet()) {
      String fieldId = unit.getKey();
      Map<String, String> residual = unit.getValue();
      if (residual.isEmpty()) {
        fieldToClassNames.put(fieldId, List.of());
        continue;
      }
      fieldsWithResidual++;
      Set<Atom> atoms = atoms(residual);
      Pattern pattern = patternsBySignature.computeIfAbsent(
          signature(residual), s -> new Pattern(atoms));
      pattern.fieldIds.add(fieldId);
    }

    List<Pattern> patterns = new ArrayList<>(patternsBySignature.values());

    // Every role that will appear in some derived class name, so prefix elision sees the same
    // population that naming later tokenises.
    Set<String> rolesInResiduals = new LinkedHashSet<>();
    for (Pattern pattern : patterns) {
      for (Atom atom : pattern.atoms) {
        rolesInResiduals.add(atom.role());
      }
    }
    prefixStages = computePrefixStages(rolesInResiduals);

    // 2. Mine frequently co-occurring uniform-CRUD atom-sets into groups (greedy: carve the
    //    highest-coverage bundle out of every pattern that contains it, then repeat).
    List<Set<Atom>> groups = mineGroups(patterns);

    // 3. Per-field cover: the groups that carved a pattern, then its leftover atoms; a cover over the
    //    cap is shortened with combined classes.
    Set<Integer> usedGroups = new LinkedHashSet<>();
    Map<Atom, Integer> atomUsage = new LinkedHashMap<>();
    // Combined classes keyed by atom signature so equal atom-sets share one class.
    Map<String, Set<Atom>> combined = new LinkedHashMap<>();
    // Final cover per pattern, as a list of tokens (group index, or atom, or combined key).
    List<List<CoverRef>> covers = new ArrayList<>();

    List<List<CoverRef>> raw = new ArrayList<>();
    // How many fields would reference each group/atom before the cap applies: ranks which of an
    // over-cap field's classes are kept when its tail is folded.
    Map<Integer, Integer> rawGroupUse = new LinkedHashMap<>();
    Map<Atom, Integer> rawAtomUse = new LinkedHashMap<>();
    for (Pattern pattern : patterns) {
      List<CoverRef> cover = new ArrayList<>();
      for (int groupIndex : pattern.coverGroups) {
        cover.add(CoverRef.group(groupIndex));
        rawGroupUse.merge(groupIndex, pattern.weight(), Integer::sum);
      }
      List<Atom> leftover = new ArrayList<>(pattern.uncovered);
      leftover.sort(Comparator.comparing(Atom::signature));
      for (Atom atom : leftover) {
        cover.add(CoverRef.atom(atom));
        rawAtomUse.merge(atom, pattern.weight(), Integer::sum);
      }
      raw.add(cover);
    }

    mineCombined(raw, groups, combined, rawGroupUse, rawAtomUse);

    final int cap = MAX_CLASSES_PER_FIELD;
    for (int p = 0; p < patterns.size(); p++) {
      Pattern pattern = patterns.get(p);
      List<CoverRef> cover = raw.get(p);
      if (cover.size() > cap) {
        // Keep the cap - 1 classes covering the most roles (ties to the most widely used) and fold
        // the rest into one combined class, keyed by its atoms so equal tails share it.
        List<CoverRef> ranked = new ArrayList<>(cover);
        Comparator<CoverRef> bySize = Comparator.comparingInt(
            r -> refAtoms(r, groups, combined).size());
        Comparator<CoverRef> byUse = Comparator.comparingInt(
            r -> refUse(r, rawGroupUse, rawAtomUse));
        ranked.sort(bySize.thenComparing(byUse).reversed());
        List<CoverRef> kept = new ArrayList<>(ranked.subList(0, cap - 1));
        Set<Atom> tail = new LinkedHashSet<>();
        for (CoverRef ref : ranked.subList(cap - 1, ranked.size())) {
          tail.addAll(refAtoms(ref, groups, combined));
        }
        String key = signatureOf(tail);
        combined.putIfAbsent(key, tail);
        // Restore the original cover order for the kept classes, the merged class last.
        List<CoverRef> ordered = new ArrayList<>();
        for (CoverRef ref : cover) {
          if (kept.contains(ref)) {
            ordered.add(ref);
          }
        }
        ordered.add(CoverRef.combined(key));
        for (CoverRef ref : ordered) {
          if (ref.groupIndex >= 0) {
            usedGroups.add(ref.groupIndex);
          } else if (ref.atom != null) {
            atomUsage.merge(ref.atom, pattern.weight(), Integer::sum);
          }
        }
        cover = ordered;
      } else {
        for (CoverRef ref : cover) {
          if (ref.groupIndex >= 0) {
            usedGroups.add(ref.groupIndex);
          } else if (ref.atom != null) {
            atomUsage.merge(ref.atom, pattern.weight(), Integer::sum);
          }
        }
      }
      covers.add(cover);
    }
    // A combined class mined in one round can be folded into a later one; keep only those a final
    // cover still names.
    Set<String> referencedCombined = new LinkedHashSet<>();
    for (List<CoverRef> cover : covers) {
      for (CoverRef ref : cover) {
        if (ref.combinedKey != null) {
          referencedCombined.add(ref.combinedKey);
        }
      }
    }
    combined.keySet().retainAll(referencedCombined);

    // 4. Assign content-derived names. Only referenced classes are emitted.
    Set<String> usedNames = new LinkedHashSet<>();
    Map<Integer, String> groupNames = assignGroupNames(groups, covers, usedGroups, usedNames);
    Map<Atom, String> atomNames = assignAtomNames(atomUsage.keySet(), usedNames);
    Map<String, String> combinedNames = assignCombinedNames(combined, usedNames);

    // 5. Resolve each field's cover to concrete class names.
    for (int i = 0; i < patterns.size(); i++) {
      Pattern pattern = patterns.get(i);
      List<String> names = new ArrayList<>();
      for (CoverRef ref : covers.get(i)) {
        if (ref.groupIndex >= 0) {
          names.add(groupNames.get(ref.groupIndex));
        } else if (ref.atom != null) {
          names.add(atomNames.get(ref.atom));
        } else {
          names.add(combinedNames.get(ref.combinedKey));
        }
      }
      List<String> shared = List.copyOf(names);
      for (String fieldId : pattern.fieldIds) {
        fieldToClassNames.put(fieldId, shared);
      }
    }

    // 6. Emit only referenced classes, ordered: groups, atoms, combined (each by name).
    List<AccessClassModel> classes = new ArrayList<>();
    List<Map.Entry<String, Set<Atom>>> emittedGroups = new ArrayList<>();
    for (int groupIndex : usedGroups) {
      emittedGroups.add(Map.entry(groupNames.get(groupIndex), groups.get(groupIndex)));
    }
    emittedGroups.sort(Map.Entry.comparingByKey());
    for (Map.Entry<String, Set<Atom>> entry : emittedGroups) {
      classes.add(AccessClassModel.builder()
          .className(entry.getKey()).grants(grantsOf(entry.getValue())).build());
    }
    List<Atom> atomList = new ArrayList<>(atomUsage.keySet());
    atomList.sort(Comparator.comparing(a -> atomNames.get(a)));
    for (Atom atom : atomList) {
      classes.add(AccessClassModel.builder()
          .className(atomNames.get(atom)).grants(grantsOf(Set.of(atom))).build());
    }
    List<Map.Entry<String, Set<Atom>>> combinedClasses = new ArrayList<>();
    for (Map.Entry<String, Set<Atom>> entry : combined.entrySet()) {
      combinedClasses.add(Map.entry(combinedNames.get(entry.getKey()), entry.getValue()));
    }
    combinedClasses.sort(Map.Entry.comparingByKey());
    for (Map.Entry<String, Set<Atom>> entry : combinedClasses) {
      classes.add(AccessClassModel.builder()
          .className(entry.getKey()).grants(grantsOf(entry.getValue())).build());
    }

    String note = summaryNote(fieldToClassNames, fieldsWithResidual, emittedGroups, atomList.size(),
        combinedClasses.size(), groupNames, patterns, covers);
    if (!combinedClasses.isEmpty()) {
      Map<String, Integer> combinedUse = new TreeMap<>();
      for (int i = 0; i < patterns.size(); i++) {
        for (CoverRef ref : covers.get(i)) {
          if (ref.combinedKey != null) {
            combinedUse.merge(combinedNames.get(ref.combinedKey), patterns.get(i).weight(),
                Integer::sum);
          }
        }
      }
      StringBuilder fb = new StringBuilder(String.format(
          "%nCombined classes (name -> roles -> fields using):%n%n| Class | Roles | Fields |%n"
              + "|---|---|---|%n"));
      for (Map.Entry<String, Set<Atom>> entry : combinedClasses) {
        fb.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue().size())
            .append(" | ").append(combinedUse.getOrDefault(entry.getKey(), 0)).append(" |\n");
      }
      note = note + fb;
    }
    return new Result(classes, fieldToClassNames, note);
  }

  private static Set<Atom> refAtoms(CoverRef ref, List<Set<Atom>> groups,
                                    Map<String, Set<Atom>> combined) {
    if (ref.groupIndex >= 0) {
      return groups.get(ref.groupIndex);
    }
    return ref.atom != null ? Set.of(ref.atom) : combined.get(ref.combinedKey);
  }

  private static int refUse(CoverRef ref, Map<Integer, Integer> groupUse,
                            Map<Atom, Integer> atomUse) {
    if (ref.groupIndex >= 0) {
      return groupUse.getOrDefault(ref.groupIndex, 0);
    }
    return ref.atom != null ? atomUse.getOrDefault(ref.atom, 0) : 0;
  }

  private static String refKey(CoverRef ref) {
    if (ref.groupIndex >= 0) {
      return "g" + ref.groupIndex;
    }
    return ref.atom != null ? "a" + ref.atom.signature() : "d" + ref.combinedKey;
  }

  /**
   * Greedily mines combined classes shared by over-cap covers: each round takes the pairwise
   * intersections of the over-cap covers' class sets, trims each to the fewest (smallest) classes
   * that bring its widest supporter within the cap, and folds the candidate shared by the most
   * over-cap covers (ties to the smaller, then by key) into one combined class in each of them.
   * A candidate whose union has no semantic name is skipped. Stops when fewer than two over-cap
   * covers share any candidate; {@link #compose} then folds the tail of each cover still over.
   */
  private void mineCombined(List<List<CoverRef>> raw, List<Set<Atom>> groups,
                            Map<String, Set<Atom>> combined, Map<Integer, Integer> groupUse,
                            Map<Atom, Integer> atomUse) {
    for (int round = 0; round < MAX_GROUPS; round++) {
      List<Integer> over = new ArrayList<>();
      List<Map<String, CoverRef>> keyed = new ArrayList<>();
      for (int p = 0; p < raw.size(); p++) {
        if (raw.get(p).size() > MAX_CLASSES_PER_FIELD) {
          over.add(p);
        }
      }
      if (over.size() < 2) {
        return;
      }
      for (int p : over) {
        Map<String, CoverRef> byKey = new LinkedHashMap<>();
        for (CoverRef ref : raw.get(p)) {
          byKey.put(refKey(ref), ref);
        }
        keyed.add(byKey);
      }
      Comparator<String> small = Comparator.<String>comparingInt(k -> {
        CoverRef ref = findRef(keyed, k);
        return refAtoms(ref, groups, combined).size();
      }).thenComparingInt(k -> refUse(findRef(keyed, k), groupUse, atomUse))
          .thenComparing(Comparator.naturalOrder());
      List<String> best = null;
      int bestSupport = 1;
      Set<String> seen = new LinkedHashSet<>();
      for (int i = 0; i < over.size(); i++) {
        for (int j = i + 1; j < over.size(); j++) {
          List<String> shared = new ArrayList<>(keyed.get(i).keySet());
          shared.retainAll(keyed.get(j).keySet());
          if (shared.size() < 2) {
            continue;
          }
          int needMax = 0;
          for (int q = 0; q < over.size(); q++) {
            if (keyed.get(q).keySet().containsAll(shared)) {
              needMax = Math.max(needMax, keyed.get(q).size() - MAX_CLASSES_PER_FIELD);
            }
          }
          shared.sort(small);
          List<String> trimmed = List.copyOf(shared.subList(0, Math.min(shared.size(), needMax + 1)));
          if (trimmed.size() < 2 || !seen.add(String.join("|", trimmed))) {
            continue;
          }
          Set<Atom> union = new LinkedHashSet<>();
          for (String k : trimmed) {
            union.addAll(refAtoms(findRef(keyed, k), groups, combined));
          }
          if (semanticName(grantsOf(union)) == null) {
            continue;
          }
          int support = 0;
          for (Map<String, CoverRef> k : keyed) {
            if (k.keySet().containsAll(trimmed)) {
              support++;
            }
          }
          if (support > bestSupport || (support == bestSupport && best != null
              && (trimmed.size() < best.size() || (trimmed.size() == best.size()
                  && String.join("|", trimmed).compareTo(String.join("|", best)) < 0)))) {
            best = trimmed;
            bestSupport = support;
          }
        }
      }
      if (best == null) {
        return;
      }
      Set<Atom> atoms = new LinkedHashSet<>();
      for (String k : best) {
        atoms.addAll(refAtoms(findRef(keyed, k), groups, combined));
      }
      String key = signatureOf(atoms);
      combined.putIfAbsent(key, atoms);
      for (int idx = 0; idx < over.size(); idx++) {
        if (!keyed.get(idx).keySet().containsAll(best)) {
          continue;
        }
        List<CoverRef> next = new ArrayList<>();
        boolean placed = false;
        for (CoverRef ref : raw.get(over.get(idx))) {
          if (best.contains(refKey(ref))) {
            if (!placed) {
              next.add(CoverRef.combined(key));
              placed = true;
            }
          } else {
            next.add(ref);
          }
        }
        raw.set(over.get(idx), next);
      }
    }
  }

  private static CoverRef findRef(List<Map<String, CoverRef>> keyed, String key) {
    for (Map<String, CoverRef> k : keyed) {
      CoverRef ref = k.get(key);
      if (ref != null) {
        return ref;
      }
    }
    throw new IllegalStateException("No cover ref " + key);
  }

  /** One reference within a per-field cover: a group index, a leftover atom, or a combined class. */
  private static final class CoverRef {
    private final int groupIndex;
    private final Atom atom;
    private final String combinedKey;

    private CoverRef(int groupIndex, Atom atom, String combinedKey) {
      this.groupIndex = groupIndex;
      this.atom = atom;
      this.combinedKey = combinedKey;
    }

    static CoverRef group(int index) {
      return new CoverRef(index, null, null);
    }

    static CoverRef atom(Atom atom) {
      return new CoverRef(-1, atom, null);
    }

    static CoverRef combined(String key) {
      return new CoverRef(-1, null, key);
    }
  }

  /**
   * Greedy frequent-itemset mining over uniform-CRUD bundles. Each round, the candidates are, per
   * CRUD level, every pattern's still-uncovered atoms at that level and the pairwise intersections
   * of those per-level sets, so a group always grants one permission level to each of its roles.
   * Each candidate is scored by the residual it would cover ({@code (|bundle| - 1) * fields-using});
   * the best qualifying bundle is carved out of every pattern that contains it, and the pass repeats.
   * A bundle too wide for a semantic name is never mined (its roles stay atoms or end up in a
   * combined class). Deterministic throughout: candidates are keyed and tie-broken by signature.
   *
   * @param patterns the distinct residual patterns (their {@code uncovered}/{@code coverGroups} are mutated)
   * @return the mined groups, in mining order
   */
  private List<Set<Atom>> mineGroups(List<Pattern> patterns) {
    List<Set<Atom>> groups = new ArrayList<>();
    while (groups.size() < MAX_GROUPS) {
      Set<Atom> best = null;
      long bestScore = -1;
      for (Set<Atom> candidate : uniformCandidates(patterns).values()) {
        int support = 0;
        for (Pattern pattern : patterns) {
          if (pattern.uncovered.containsAll(candidate)) {
            support += pattern.weight();
          }
        }
        if (support < MIN_GROUP_FIELDS || semanticName(grantsOf(candidate)) == null) {
          continue;
        }
        long score = (long) (candidate.size() - 1) * support;
        // Higher coverage wins; ties to the larger, then signature (candidates are already iterated
        // in signature order, so first-wins gives the signature tie-break).
        if (score > bestScore
            || (score == bestScore && best != null && candidate.size() > best.size())) {
          best = candidate;
          bestScore = score;
        }
      }

      if (best == null) {
        break;
      }
      // Snapshot the chosen bundle: a candidate may alias a pattern's live uncovered set, which the
      // carve below mutates.
      Set<Atom> bundle = new LinkedHashSet<>(best);
      int groupIndex = groups.size();
      groups.add(bundle);
      for (Pattern pattern : patterns) {
        if (pattern.uncovered.containsAll(bundle)) {
          pattern.uncovered.removeAll(bundle);
          pattern.coverGroups.add(groupIndex);
        }
      }
    }
    return groups;
  }

  /**
   * One mining round's candidate bundles, keyed by signature (see {@link #mineGroups}).
   */
  private Map<String, Set<Atom>> uniformCandidates(List<Pattern> patterns) {
    Map<String, List<Set<Atom>>> byLevel = new TreeMap<>();
    for (Pattern pattern : patterns) {
      Map<String, Set<Atom>> atLevel = new TreeMap<>();
      for (Atom atom : pattern.uncovered) {
        atLevel.computeIfAbsent(atom.crud(), k -> new LinkedHashSet<>()).add(atom);
      }
      for (Map.Entry<String, Set<Atom>> entry : atLevel.entrySet()) {
        if (entry.getValue().size() >= MIN_GROUP_ATOMS) {
          byLevel.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
        }
      }
    }
    Map<String, Set<Atom>> candidates = new TreeMap<>();
    for (List<Set<Atom>> sets : byLevel.values()) {
      for (int i = 0; i < sets.size(); i++) {
        candidates.putIfAbsent(signatureOf(sets.get(i)), sets.get(i));
        for (int j = i + 1; j < sets.size(); j++) {
          Set<Atom> intersection = new LinkedHashSet<>(sets.get(i));
          intersection.retainAll(sets.get(j));
          if (intersection.size() >= MIN_GROUP_ATOMS) {
            candidates.putIfAbsent(signatureOf(intersection), intersection);
          }
        }
      }
    }
    return candidates;
  }

  private Map<Integer, String> assignGroupNames(
      List<Set<Atom>> groups, List<List<CoverRef>> covers, Set<Integer> usedGroups,
      Set<String> usedNames) {
    Map<Integer, Integer> usage = new LinkedHashMap<>();
    for (List<CoverRef> cover : covers) {
      for (CoverRef ref : cover) {
        if (ref.groupIndex >= 0) {
          usage.merge(ref.groupIndex, 1, Integer::sum);
        }
      }
    }
    Map<Integer, String> names = new LinkedHashMap<>();
    if (usedGroups.isEmpty()) {
      return names;
    }
    // The most-used group (ties to the lowest mining index) claims its name first, so a collision
    // suffix never lands on the bundle most fields carry; the rest follow in mining order.
    int mostUsed = usedGroups.stream()
        .max(Comparator.<Integer>comparingInt(g -> usage.getOrDefault(g, 0))
            .thenComparing(Comparator.reverseOrder()))
        .orElseThrow();
    List<Integer> order = new ArrayList<>();
    order.add(mostUsed);
    for (int groupIndex : usedGroups) {
      if (groupIndex != mostUsed) {
        order.add(groupIndex);
      }
    }
    for (int groupIndex : order) {
      String name = uniqueName(nameFor(grantsOf(groups.get(groupIndex))), usedNames);
      names.put(groupIndex, name);
      usedNames.add(name);
    }
    return names;
  }

  private Map<Atom, String> assignAtomNames(Set<Atom> atoms, Set<String> usedNames) {
    List<Atom> ordered = new ArrayList<>(atoms);
    ordered.sort(Comparator.comparing(Atom::signature));
    Map<Atom, String> names = new LinkedHashMap<>();
    for (Atom atom : ordered) {
      String name = uniqueName(atomName(atom), usedNames);
      names.put(atom, name);
      usedNames.add(name);
    }
    return names;
  }

  private Map<String, String> assignCombinedNames(
      Map<String, Set<Atom>> combined, Set<String> usedNames) {
    List<Map.Entry<String, Set<Atom>>> ordered = new ArrayList<>(combined.entrySet());
    ordered.sort(Map.Entry.comparingByKey());
    Map<String, String> names = new LinkedHashMap<>();
    for (Map.Entry<String, Set<Atom>> entry : ordered) {
      String name = uniqueName(nameFor(grantsOf(entry.getValue())), usedNames);
      names.put(entry.getKey(), name);
      usedNames.add(name);
    }
    return names;
  }

  /**
   * The field's residual: every role the injected grant leaves short of its definition grant,
   * mapped to the full definition CRUD (see the class Javadoc on normalisation).
   */
  private Map<String, String> residual(
      String fieldId, Map<String, String> want, Map<String, String> have) {
    Map<String, String> residual = new TreeMap<>();
    Set<String> roles = new LinkedHashSet<>();
    roles.addAll(want.keySet());
    roles.addAll(have.keySet());

    for (String role : roles) {
      Set<Character> wantPerms = CrudSet.parse(want.get(role));
      Set<Character> havePerms = CrudSet.parse(have.get(role));

      if (!wantPerms.containsAll(havePerms)) {
        // The SDK injects a permission the definition does not grant; adding an access class
        // cannot remove it. The only permission this converter ever injects here is a tab/search
        // read (see DefaultDefinitionLinker.deriveAccessClasses, whose tab/search loops add exactly
        // Set.of('R')), so `extra` is always {R}: the read the AuthorisationCaseFieldGenerator adds
        // for a role that can already see the field via an unrestricted tab or search. It is a
        // display-only over-grant on a role that is otherwise granted the field, forgiven on the
        // round-trip by the TAB_READ_INJECTION comparator rule — no AuthorisationCaseField row is
        // written to passthrough for it (deriveAccessClasses emits only access classes), so this
        // entry is a report-only ADVISORY record, not a passed-through row. Recording it keeps the
        // "nothing is silently dropped" invariant honest without failing the conversion.
        Set<Character> extra = new LinkedHashSet<>(havePerms);
        extra.removeAll(wantPerms);
        gaps.add(GapEntry.builder()
            .sheet("AuthorisationCaseField")
            .rowKey(fieldId + "/" + role)
            .column("CRUD")
            .value(CrudSet.format(wantPerms))
            .category(GapCategory.AUTH_NOT_DERIVABLE)
            .action(GapAction.ADVISORY)
            .detail("SDK injects " + CrudSet.format(extra) + " for role " + role
                + " on field " + fieldId + " which the definition does not grant: a display-only"
                + " read the AuthorisationCaseFieldGenerator adds for a role that can see the field"
                + " via an unrestricted tab/search. No access class can remove it; the divergence is"
                + " forgiven by the TAB_READ_INJECTION comparator rule. Report-only — no row is"
                + " passed through.")
            .build());
      }

      if (!havePerms.containsAll(wantPerms)) {
        residual.put(role, CrudSet.format(wantPerms));
      }
    }
    return residual;
  }

  /** Decomposes a residual grant map into its atoms (one per role). */
  private Set<Atom> atoms(Map<String, String> residual) {
    Set<Atom> atoms = new LinkedHashSet<>();
    for (Map.Entry<String, String> entry : new TreeMap<>(residual).entrySet()) {
      atoms.add(new Atom(entry.getKey(), entry.getValue()));
    }
    return atoms;
  }

  /** The role-to-CRUD grant map an atom-set expresses (each atom is a distinct role). */
  private Map<String, String> grantsOf(Set<Atom> atoms) {
    Map<String, String> grants = new TreeMap<>();
    for (Atom atom : atoms) {
      grants.put(atom.role(), atom.crud());
    }
    return grants;
  }

  private String signature(Map<String, String> residual) {
    Map<String, String> sorted = new TreeMap<>(residual);
    StringBuilder builder = new StringBuilder();
    for (Map.Entry<String, String> entry : sorted.entrySet()) {
      builder.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
    }
    return builder.toString();
  }

  private String signatureOf(Set<Atom> atoms) {
    Set<String> sorted = new TreeSet<>();
    for (Atom atom : atoms) {
      sorted.add(atom.signature());
    }
    return String.join(";", sorted);
  }

  private String uniqueName(String base, Set<String> used) {
    if (!used.contains(base)) {
      return base;
    }
    int suffix = 2;
    String candidate = base + suffix;
    while (used.contains(candidate)) {
      candidate = base + (++suffix);
    }
    return candidate;
  }

  /**
   * A deterministic, content-derived name for a single atom: {@code <RoleToken><CrudToken>Access}.
   *
   * @param atom the role/CRUD atom
   * @return the atom's class name (without collision suffix)
   */
  private String atomName(Atom atom) {
    String role = pascal(roleTokens(atom.role()));
    if (role.isEmpty()) {
      return "Access" + digest(atom.signature());
    }
    return role + crudToken(atom.crud()) + "Access";
  }

  /**
   * A deterministic, content-derived class name for a grant map (see the class Javadoc), falling
   * back to {@code <FirstRole><Crud>Plus<N>Roles<Digest>Access} when the semantic form is too long.
   *
   * @param residual the grant map (role id to CRUD)
   * @return the class name (without collision suffix)
   */
  private String nameFor(Map<String, String> residual) {
    String semantic = semanticName(residual);
    if (semantic != null) {
      return semantic;
    }
    List<Map.Entry<List<String>, String>> entries = nameEntries(residual);
    Map.Entry<List<String>, String> first = entries.get(0);
    return pascal(first.getKey()) + crudToken(first.getValue())
        + "Plus" + (entries.size() - 1) + "Roles" + digest(signature(residual)) + "Access";
  }

  /**
   * The semantic class name for a grant map, or null when it would exceed
   * {@link #MAX_SEMANTIC_NAME}. Roles are ordered widest CRUD first; within a same-CRUD run, a role
   * sharing leading tokens with the one before it drops them ({@code SpecificAccessAdminJudge}), and
   * a trailing token the run's members share is written once, at the end of the run. The CRUD token
   * follows each run.
   */
  private String semanticName(Map<String, String> residual) {
    List<Map.Entry<List<String>, String>> entries = nameEntries(residual);
    List<List<String>> emitted = new ArrayList<>();
    List<Integer> commons = new ArrayList<>();
    List<String> prev = List.of();
    String prevCrud = null;
    for (Map.Entry<List<String>, String> entry : entries) {
      List<String> tokens = entry.getKey();
      int common = 0;
      if (entry.getValue().equals(prevCrud)) {
        while (common < prev.size() - 1 && common < tokens.size() - 1
            && prev.get(common).equals(tokens.get(common))) {
          common++;
        }
      }
      emitted.add(new ArrayList<>(tokens.subList(common, tokens.size())));
      commons.add(common);
      prev = tokens;
      prevCrud = entry.getValue();
    }
    for (int i = 0; i + 1 < emitted.size(); i++) {
      List<String> a = emitted.get(i);
      List<String> b = emitted.get(i + 1);
      if (commons.get(i + 1) > 0 && a.size() > 1 && b.size() > 1
          && entries.get(i).getKey().size() > commons.get(i + 1) + 1
          && a.get(a.size() - 1).equals(b.get(b.size() - 1))) {
        a.remove(a.size() - 1);
      }
    }
    StringBuilder name = new StringBuilder();
    for (int i = 0; i < entries.size(); i++) {
      String crud = entries.get(i).getValue();
      name.append(pascal(emitted.get(i)));
      if (i == entries.size() - 1 || !entries.get(i + 1).getValue().equals(crud)) {
        name.append(crudToken(crud));
      }
    }
    name.append("Access");
    return name.length() <= MAX_SEMANTIC_NAME ? name.toString() : null;
  }

  /** A grant map as (elided role tokens, CRUD) entries in naming order, one-letter series collapsed. */
  private List<Map.Entry<List<String>, String>> nameEntries(Map<String, String> residual) {
    List<Map.Entry<List<String>, String>> entries = new ArrayList<>();
    for (Map.Entry<String, String> e : residual.entrySet()) {
      entries.add(Map.entry(roleTokens(e.getKey()), e.getValue()));
    }
    entries.sort(NAMING_ORDER);
    return collapseSeries(entries);
  }

  /** Widest CRUD first, then CRUD, then the role tokens. */
  private static final Comparator<Map.Entry<List<String>, String>> NAMING_ORDER =
      Comparator.comparingInt((Map.Entry<List<String>, String> e) -> -e.getValue().length())
          .thenComparing(Map.Entry::getValue)
          .thenComparing(e -> String.join("-", e.getKey()));

  /**
   * Collapses three or more same-CRUD roles that differ only by a one-character suffix on a shared
   * stem ({@code [CHILDSOLICITORA]..[CHILDSOLICITORO]}) into one entry {@code Childsolicitor A To O};
   * a non-contiguous series lists its suffixes ({@code Solicitor A C F}).
   */
  private static List<Map.Entry<List<String>, String>> collapseSeries(
      List<Map.Entry<List<String>, String>> entries) {
    Map<String, List<Map.Entry<List<String>, String>>> byStem = new LinkedHashMap<>();
    for (Map.Entry<List<String>, String> e : entries) {
      List<String> t = e.getKey();
      String last = t.get(t.size() - 1);
      String key = last.length() > 2
          ? e.getValue() + "|" + String.join("-", t.subList(0, t.size() - 1)) + "|"
              + last.substring(0, last.length() - 1)
          : "\u0000" + byStem.size();
      byStem.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
    }
    List<Map.Entry<List<String>, String>> out = new ArrayList<>();
    for (List<Map.Entry<List<String>, String>> members : byStem.values()) {
      if (members.size() < 3) {
        out.addAll(members);
        continue;
      }
      List<String> t0 = members.get(0).getKey();
      String last0 = t0.get(t0.size() - 1);
      List<Character> suffixes = new ArrayList<>();
      for (Map.Entry<List<String>, String> m : members) {
        String last = m.getKey().get(m.getKey().size() - 1);
        suffixes.add(last.charAt(last.length() - 1));
      }
      suffixes.sort(Comparator.naturalOrder());
      List<String> tokens = new ArrayList<>(t0.subList(0, t0.size() - 1));
      tokens.add(last0.substring(0, last0.length() - 1));
      boolean contiguous = suffixes.get(suffixes.size() - 1) - suffixes.get(0) == suffixes.size() - 1;
      if (contiguous) {
        tokens.add(String.valueOf(suffixes.get(0)));
        tokens.add("to");
        tokens.add(String.valueOf(suffixes.get(suffixes.size() - 1)));
      } else {
        for (char c : suffixes) {
          tokens.add(String.valueOf(c));
        }
      }
      out.add(Map.entry(tokens, members.get(0).getValue()));
    }
    out.sort(NAMING_ORDER);
    return out;
  }

  /**
   * A role id's naming tokens: its raw tokens with {@link #prefixStages} elided in order.
   */
  private List<String> roleTokens(String role) {
    List<String> tokens = rawTokens(role);
    for (String prefix : prefixStages) {
      tokens = stripPrefix(tokens, prefix);
    }
    return tokens;
  }

  /** A role id lower-cased and split on every non-alphanumeric run, case-role brackets dropped. */
  private static List<String> rawTokens(String role) {
    String cleaned = role.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
    List<String> tokens = new ArrayList<>();
    for (String t : cleaned.split("[^a-z0-9]+")) {
      if (!t.isEmpty()) {
        tokens.add(t);
      }
    }
    return tokens;
  }

  /**
   * Drops a leading {@code prefix} token. A role that is the prefix alone keeps it, so it still
   * yields a non-empty, distinguishing name.
   */
  private static List<String> stripPrefix(List<String> tokens, String prefix) {
    if (tokens.size() < 2 || !tokens.get(0).equals(prefix)) {
      return tokens;
    }
    return tokens.subList(1, tokens.size());
  }

  /**
   * The leading tokens to elide from role names, one per pass, at most {@link #MAX_PREFIX_STAGES}.
   * Each pass picks the most common leading token among roles that have more after it (ties to the
   * alphabetically first) and stops unless at least {@link #MIN_PREFIX_ROLES} roles and
   * {@link #PREFIX_MIN_SHARE} of all roles carry it; the next pass runs on the stripped tokens.
   * Case roles are their own namespace and are left out of the count.
   *
   * @param roles the distinct role IDs participating in some residual
   * @return the tokens to strip, in pass order (never null)
   */
  private List<String> computePrefixStages(Set<String> roles) {
    List<List<String>> current = new ArrayList<>();
    for (String role : roles) {
      if (role.startsWith("[")) {
        continue;
      }
      List<String> tokens = rawTokens(role);
      if (!tokens.isEmpty()) {
        current.add(tokens);
      }
    }
    int total = current.size();
    List<String> stages = new ArrayList<>();
    if (total < MIN_ROLES_FOR_COMMON_PREFIX) {
      return stages;
    }
    while (stages.size() < MAX_PREFIX_STAGES) {
      Map<String, Integer> byLead = new TreeMap<>();
      for (List<String> tokens : current) {
        if (tokens.size() > 1) {
          byLead.merge(tokens.get(0), 1, Integer::sum);
        }
      }
      Map.Entry<String, Integer> best = null;
      for (Map.Entry<String, Integer> entry : byLead.entrySet()) {
        if (best == null || entry.getValue() > best.getValue()) {
          best = entry;
        }
      }
      if (best == null || best.getValue() < MIN_PREFIX_ROLES
          || (double) best.getValue() / total < PREFIX_MIN_SHARE) {
        break;
      }
      String prefix = best.getKey();
      stages.add(prefix);
      List<List<String>> next = new ArrayList<>();
      for (List<String> tokens : current) {
        next.add(stripPrefix(tokens, prefix));
      }
      current = next;
    }
    return List.copyOf(stages);
  }

  private static String pascal(List<String> tokens) {
    StringBuilder builder = new StringBuilder();
    for (String t : tokens) {
      builder.append(Character.toUpperCase(t.charAt(0))).append(t.substring(1));
    }
    return builder.toString();
  }

  // A CRUD string turned into a title-case token: "CRU" -> "Cru", "R" -> "R".
  private String crudToken(String crud) {
    if (crud == null || crud.isEmpty()) {
      return "";
    }
    String lower = crud.toLowerCase();
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  /** A short, stable, alphabetic digest of a signature string, for name uniqueness on truncation. */
  private String digest(String signature) {
    int hash = signature.hashCode();
    StringBuilder builder = new StringBuilder();
    long value = Integer.toUnsignedLong(hash);
    for (int i = 0; i < 6; i++) {
      builder.append((char) ('a' + (int) (value % 26)));
      value /= 26;
    }
    return Character.toUpperCase(builder.charAt(0)) + builder.substring(1);
  }

  /**
   * Builds the converter-report note: total class count broken down into groups/atoms/combined, the
   * per-field array distribution (avg/max), and the mined-group table (name, roles/CRUD, usage).
   */
  private String summaryNote(
      Map<String, List<String>> fieldToClassNames, int fieldsWithResidual,
      List<Map.Entry<String, Set<Atom>>> emittedGroups, int atomCount, int combinedCount,
      Map<Integer, String> groupNames, List<Pattern> patterns, List<List<CoverRef>> covers) {
    int total = emittedGroups.size() + atomCount + combinedCount;
    int sum = 0;
    int max = 0;
    for (List<String> names : fieldToClassNames.values()) {
      if (names.isEmpty()) {
        continue;
      }
      sum += names.size();
      max = Math.max(max, names.size());
    }
    double avg = fieldsWithResidual == 0 ? 0 : (double) sum / fieldsWithResidual;

    // Usage per group name (fields referencing it) for the mined-group table.
    Map<String, Integer> groupUsage = new LinkedHashMap<>();
    for (int i = 0; i < patterns.size(); i++) {
      int weight = patterns.get(i).weight();
      for (CoverRef ref : covers.get(i)) {
        if (ref.groupIndex >= 0) {
          groupUsage.merge(groupNames.get(ref.groupIndex), weight, Integer::sum);
        }
      }
    }

    StringBuilder sb = new StringBuilder();
    sb.append(String.format(
        "composition scheme: %d access classes (%d groups, %d atoms, %d combined) "
            + "for %d fields with a residual; classes per field avg %.2f, max %d.%n",
        total, emittedGroups.size(), atomCount, combinedCount, fieldsWithResidual, avg, max));
    if (!emittedGroups.isEmpty()) {
      sb.append(String.format("%nMined groups (name -> roles/CRUD -> fields using):%n%n"));
      sb.append("| Group | Roles/CRUD | Fields |\n|---|---|---|\n");
      for (Map.Entry<String, Set<Atom>> entry : emittedGroups) {
        String rolesCrud = grantsOf(entry.getValue()).entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .reduce((x, y) -> x + ", " + y).orElse("");
        sb.append("| ").append(entry.getKey())
            .append(" | ").append(rolesCrud)
            .append(" | ").append(groupUsage.getOrDefault(entry.getKey(), 0))
            .append(" |\n");
      }
    }
    return sb.toString();
  }

  /**
   * The derived access classes plus the class-name list to attach to each field, and a report note.
   *
   * @param accessClasses the emitted access classes (groups + atoms + combined classes)
   * @param fieldClassNames field id to the access-class names it references (union = its residual)
   * @param summaryNote the class-count and per-field-array summary for the converter report
   */
  record Result(
      List<AccessClassModel> accessClasses,
      Map<String, List<String>> fieldClassNames,
      String summaryNote) {
  }
}
