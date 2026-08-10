#!/usr/bin/env bash
#
# refresh-migration-branches.sh — rebuild each service's `ccd-config-generator-migration` commit from
# the CURRENT converter output, so the open migration PRs show what the converter produces today
# rather than what it produced the day they were opened.
#
# The review clones under retrofit-reports/<lane> are the single source of truth: regen-review-clones.sh
# has already written today's companion tree, passthrough resources and model patch into each. This
# script replays exactly those three artefact classes onto the branch's own base commit and commits
# the result.
#
#   1. companion sources — every UNTRACKED .java under the lane's source root in the review clone.
#      Untracked is the discriminator: the team's own model files are tracked there, generated
#      companions are not (see regen-review-clones.sh for why path-based selection is unsafe — sscs
#      declares its model INSIDE the companion root package).
#   2. passthrough resources — resources/ccd-passthrough, wholly generated, so replaced not merged.
#   3. the model annotation patch — the tracked modifications in the review clone, i.e. exactly
#      `git diff` there. Applying that diff reproduces the @CCD annotations on the team's model.
#
# Work happens in a THROWAWAY WORKTREE per lane, never in the service clone itself: no reset, no
# checkout, no branch move in the clone the user works in. Their working tree and local branch ref
# are left exactly as found.
#
# The refreshed state lands as ONE NEW COMMIT ON TOP of the branch's current tip, not as a rewrite of
# it. The tree is built from the branch's BASE (so the content is exactly today's converter output,
# with none of the previous generation's files surviving as fossils), but it is committed with the old
# tip as its parent via commit-tree. So the push is an ordinary fast-forward — no history rewrite on a
# branch that already has an open PR — and the PR's diff-since-last-review is precisely the delta
# between the two generations of converter output.
#
# The pinned SDK version IS rewritten, to --sdk-version. It used to be left alone, on the grounds that
# everything on the converter branch since the 96.1 publish was converter-only; that stopped being true
# once the branch added ConfigBuilder API the generated companions call (complexScope/complexMember —
# `git diff <old publish>..HEAD -- sdk/ccd-config-generator/src/main`). Regenerated output pinned to an
# SDK that predates the API it calls does not compile, so the two move together. Pass the version the
# publish workflow produced for the converter commit being regenerated from; omit it to keep whatever
# each branch pins today (correct only when no SDK main-source change is involved).
#
# Nothing is pushed automatically: the script prepares each commit and prints its push command,
# because these branches feed open PRs and the operator should read the diffstat first.
#
# Usage: refresh-migration-branches.sh [--sdk-version <version>] [lane ...]
#        (default lanes: every lane whose migration PR is OPEN, asked of GitHub at run time)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONVERTER_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPORTS="${CONVERTER_DIR}/retrofit-reports"
WORKSPACE="$(cd "${CONVERTER_DIR}/../../../.." && pwd)"

# lane | service clone (rel to workspace root) | source root (rel to clone) | push remote | case type
LANES=(
  "probate-back-office|apps/probate/probate-back-office|src/main/java|origin|GrantOfRepresentation"
  "fpl-ccd-configuration|apps/fpl/fpl-ccd-configuration|service/src/main/java|fork|CARE_SUPERVISION_EPO"
  "et-ccd-callbacks|apps/et/et-ccd-callbacks|et-shared/src/main/java|fork|ET_EnglandWales"
  "prl-cos-api|apps/prl/prl-cos-api|src/main/java|fork|PRLAPPS"
  "sscs-common|apps/sscs/sscs-common|src/main/java|origin|Benefit"
  "civil-service|apps/civil/civil-service|src/main/java|fork|CIVIL"
)

BRANCH=ccd-config-generator-migration
SDK_VERSION=""

run_lane() {
  IFS='|' read -r lane clonerel srcroot remote casetype <<<"$1"
  local review="${REPORTS}/${lane}"
  local clone="${WORKSPACE}/${clonerel}"
  local wt="${WORKSPACE}/${clonerel}-refresh-wt"
  echo "########## ${lane} (${casetype})"

  [[ -d "${review}" ]] || { echo "!! no review clone at ${review}"; return 1; }
  [[ -d "${clone}" ]]  || { echo "!! no service clone at ${clone}"; return 1; }

  # The tip to build on is the REMOTE's, fetched now, never the clone's local branch ref. The local ref
  # is whatever the operator last checked out and goes stale the moment a refresh is pushed from a
  # worktree (the push updates the remote, not the clone's branch). Committing onto a stale local ref
  # produces a commit whose parent is not the remote tip, so the push is a non-fast-forward and the only
  # ways out are a force-push over an open PR or discarding the work. Fetch, then read the
  # remote-tracking ref.
  local first base tip remoteref
  git -C "${clone}" fetch -q "${remote}" "${BRANCH}" || { echo "!! fetch ${remote} ${BRANCH} failed"; return 1; }
  remoteref="refs/remotes/${remote}/${BRANCH}"
  git -C "${clone}" rev-parse --verify -q "${remoteref}" >/dev/null \
    || { echo "!! no ${remoteref} after fetch"; return 1; }

  # The branch's base: the commit its first migration commit was built on. Derived, not assumed, so
  # this stays correct if the branch is later rebased onto a newer upstream.
  first="$(git -C "${clone}" log --format=%H --grep='as Java config' -1 "${remoteref}")"
  [[ -n "${first}" ]] || { echo "!! no migration commit found on ${lane}"; return 1; }
  base="$(git -C "${clone}" rev-parse "${first}^")"
  tip="$(git -C "${clone}" rev-parse "${remoteref}")"
  echo "  base $(git -C "${clone}" log --oneline -1 "${base}")"
  echo "  old tip $(git -C "${clone}" log --oneline -1 "${tip}")"

  # Detached worktree at the base commit. Detached so no branch ref anywhere is touched.
  git -C "${clone}" worktree remove --force "${wt}" 2>/dev/null || true
  git -C "${clone}" worktree add -q --detach "${wt}" "${base}"

  # 1. companion sources.
  local list="${wt}/.companions.txt"
  git -C "${review}" ls-files --others --exclude-standard -- "${srcroot}" \
    | grep '\.java$' > "${list}" || true
  local n=0
  while read -r rel; do
    [[ -z "${rel}" ]] && continue
    mkdir -p "${wt}/$(dirname "${rel}")"
    cp "${review}/${rel}" "${wt}/${rel}"
    n=$((n + 1))
  done < "${list}"
  rm -f "${list}"
  echo "  companions: ${n} java files"

  # 2. passthrough resources — wholly generated, replace outright.
  #
  # The DESTINATION is read off the branch, not hardcoded: the mergePassthrough task in each branch's
  # build.gradle takes the directory as a literal argument, and that build file is carried over from the
  # old tip verbatim (below), so writing the tree anywhere else silently breaks the merge — sscs keeps
  # its passthrough under ccd-definition/, everyone else under resources/. Derived from the old tip's
  # own tree so it follows the branch if a service moves it again.
  # (Nothing that closes the pipe early — no `grep -m1`, no `head -1`: the reader exiting first SIGPIPEs
  # whatever is upstream of it, and under `set -e -o pipefail` a failing command substitution aborts the
  # whole lane silently. It only LOOKS harmless on a small tree, whose ls-tree output fits the pipe
  # buffer and so completes before the reader leaves; prl's 1226 files do not. So the whole stream is
  # read and the first line taken with a parameter expansion instead.)
  local ptparent
  ptparent="$(git -C "${clone}" ls-tree -r --name-only "${tip}" \
    | sed -n 's|/ccd-passthrough/.*||p')"
  ptparent="${ptparent%%$'\n'*}"
  [[ -n "${ptparent}" ]] || ptparent="resources"
  rm -rf "${wt:?}/${ptparent}/ccd-passthrough"
  if [[ -d "${review}/resources/ccd-passthrough" ]]; then
    mkdir -p "${wt}/${ptparent}"
    cp -a "${review}/resources/ccd-passthrough" "${wt}/${ptparent}/"
    echo "  passthrough: $(find "${wt}/${ptparent}/ccd-passthrough" -type f | wc -l) files" \
      "-> ${ptparent}/ccd-passthrough"
  fi

  # 3. the model annotation patch = the review clone's tracked modifications. Applied as a diff rather
  # than by copying files, so only the annotated hunks move and any upstream change between the review
  # baseline and this branch's base is preserved. git apply is atomic: capture rc directly, never
  # through a pipe.
  local patch="${WORKSPACE}/${clonerel}-refresh.patch"
  git -C "${review}" diff > "${patch}"
  if [[ -s "${patch}" ]]; then
    local rc=0
    git -C "${wt}" apply --3way "${patch}" || rc=$?
    if [[ ${rc} -ne 0 ]]; then
      echo "  !! model patch failed to apply (rc=${rc}); left for inspection: ${patch}"
      return 1
    fi
    echo "  model patch: $(grep -c '^+++ b/' "${patch}") files annotated"
    rm -f "${patch}"
  fi

  # The migration branches also carry the plugin/ccd wiring in the build file. That is hand-written,
  # not converter output, so take it from the old tip verbatim.
  local buildfiles
  buildfiles="$(git -C "${clone}" diff --name-only "${base}" "${tip}" \
    -- '*build.gradle' '*settings.gradle' || true)"
  if [[ -n "${buildfiles}" ]]; then
    while read -r f; do
      [[ -z "$f" ]] && continue
      git -C "${wt}" checkout "${tip}" -- "$f"
      echo "  build wiring preserved: $f"
    done <<<"${buildfiles}"
    # Then re-pin the SDK, in the files just taken from the tip. Both spellings the branches use: the
    # plugin id's `version '…'` and ET's explicit ccd-config-generator dependency coordinate.
    #
    # Matched on the LINE (the plugin id, or the ccd-config-generator coordinate) and on the publish
    # workflow's version SHAPE — `<branch-ref>-<run>.<attempt>-<sha8>` — not on a particular branch
    # prefix. It used to be anchored on `json-definition-converter-`, which silently stopped matching
    # anything the moment the pins moved to the SDK-only branch's publish (sdk-migration-features-…),
    # so a re-pin to a THIRD version would have left every branch on the second one. The line guard is
    # what keeps sscs's `ccdPassthrough … name: 'ccd-definition-converter'` coordinate out of it: that
    # one pins the CONVERTER artifact, which only the converter branch publishes, so it must not move
    # with the SDK.
    if [[ -n "${SDK_VERSION}" ]]; then
      while read -r f; do
        [[ -z "$f" ]] && continue
        perl -pi -e "s/'[^']*-[0-9]+\.[0-9]+-[0-9a-f]{8}'/'${SDK_VERSION}'/g
                     if /hmcts\.ccd\.sdk|name: 'ccd-config-generator'/" \
          "${wt}/$f"
      done <<<"${buildfiles}"
      local pinned
      pinned="$(grep -rl "${SDK_VERSION}" "${wt}" --include='*.gradle' | wc -l)"
      [[ "${pinned}" -gt 0 ]] || { echo "!! SDK re-pin matched nothing in ${lane}'s build files"; return 1; }
      echo "  SDK re-pinned to ${SDK_VERSION} in ${pinned} build file(s)"
    fi

    # ccd.rootPackage must name the COMPANION package, whose ConverterGeneratedApplication is the
    # generation entry point, not the service's own base package. Rewritten here rather than trusted
    # from the old tip because the tip's value was hand-written before the companion layout settled and
    # is wrong on every lane: it pointed Main's single-@SpringBootConfiguration lookup at the service's
    # real application, which boots the entire service (probate: lifeevents TLS + LaunchDarkly 401) or,
    # on sscs-common, at a package with no application class at all — and its ...ccd.domain.ccd spelling
    # names a directory the converter has never emitted. Derived from the companion tree just staged, so
    # it cannot drift from what was actually generated.
    local rootpkg entrypoint
    entrypoint="$(cd "${wt}" && find . -name 'ConverterGeneratedApplication.java' -print | head -1)"
    if [[ -n "${entrypoint}" ]]; then
      rootpkg="$(sed -n 's/^package \(.*\);$/\1/p' "${wt}/${entrypoint}")"
      [[ -n "${rootpkg}" ]] || { echo "!! could not read the entry point's package"; return 1; }
      # Rewrite where the line exists; INSERT into the ccd { } block where it does not. et-ccd-callbacks
      # sets no rootPackage at all and so inherits the plugin's `uk.gov.hmcts` default — which scans the
      # whole tree and finds the service's own application. A rewrite-only pass matches nothing there and
      # would leave the lane broken while reporting success, so absence is handled, not assumed away.
      while read -r f; do
        [[ -z "$f" ]] && continue
        if grep -q "^\s*rootPackage\s*=" "${wt}/$f"; then
          perl -pi -e "s/^(\s*rootPackage\s*=\s*)'[^']*'/\${1}'${rootpkg}'/" "${wt}/$f"
        else
          perl -0pi -e "s/(\nccd \{\n)/\${1}    rootPackage = '${rootpkg}'\n/" "${wt}/$f"
        fi
      done <<<"${buildfiles}"
      grep -rq "rootPackage = '${rootpkg}'" "${wt}" --include='*.gradle' \
        || { echo "!! rootPackage rewrite matched nothing in ${lane}'s build files"; return 1; }
      echo "  ccd.rootPackage -> ${rootpkg} (companion entry point)"

      # Generation has no web tier, so run it with WebApplicationType.NONE. Without this the emitted
      # entry point starts, deduces a SERVLET application from the service's classpath (spring-webmvc is
      # on it), and then fails with "no ServletWebServerFactory bean defined" — because opting out of
      # autoconfiguration is exactly what removes that factory. sscs-common already carried the line by
      # hand and so was the only lane that got this far; retrofit-verify sets it from its init script,
      # which is why the harness never saw it. Added to the build file holding the ccd block, next to
      # the rootPackage it belongs with.
      while read -r f; do
        [[ -z "$f" ]] && continue
        grep -q "rootPackage = '${rootpkg}'" "${wt}/$f" || continue
        grep -q 'web-application-type' "${wt}/$f" && continue
        cat >> "${wt}/$f" <<'GRADLE'

// The generator's Spring context has no web tier: run it with no embedded server. The generated
// entry point takes no autoconfiguration, so nothing supplies a ServletWebServerFactory and Boot
// would otherwise fail on deducing a servlet application from the service's classpath.
tasks.named('generateCCDConfig') {
    systemProperty 'spring.main.web-application-type', 'none'
}
GRADLE
        echo "  generateCCDConfig -> web-application-type=none in $f"
      done <<<"${buildfiles}"
    else
      echo "  !! no ConverterGeneratedApplication in the companion tree — generateCCDConfig will fail"
      return 1
    fi
  fi

  # The tree is what we built from the base; the parent is the branch's existing tip. commit-tree
  # separates the two, which `git commit` cannot: the content is a clean regeneration (no fossils from
  # the previous generation), while the history stays append-only so the push fast-forwards and the open
  # PR's incremental diff is exactly one generation of converter output against the other.
  git -C "${wt}" add -A
  local tree refreshed
  tree="$(git -C "${wt}" write-tree)"
  if [[ "${tree}" == "$(git -C "${clone}" rev-parse "${tip}^{tree}")" ]]; then
    echo "  already up to date with today's converter output — nothing to push"
    git -C "${clone}" worktree remove --force "${wt}"
    return 0
  fi
  refreshed="$(git -C "${wt}" commit-tree "${tree}" -p "${tip}" -F - <<EOF
Regenerate ${casetype} from the current converter

Rebuilds the generated half of this migration from ccd-definition-converter as it
stands today (branch json-definition-converter), so the PR shows what the converter
produces now rather than what it produced when the PR was opened. The tree is a clean
regeneration from this branch's base, so nothing from the previous generation survives
as a fossil; the hand-written build wiring is carried over unchanged.

The pinned SDK artifact moves with it${SDK_VERSION:+ (now ${SDK_VERSION})}: the branch adds ConfigBuilder
API the generated companions call, so the regenerated output and the SDK it resolves
against have to be the same generation.

DO NOT MERGE — this is for review of the migration's shape and fidelity, not to land.
EOF
)"
  git -C "${wt}" reset -q --hard "${refreshed}"
  echo "  committed $(git -C "${wt}" log --oneline -1)"
  git -C "${wt}" diff --shortstat "${base}" HEAD | sed 's/^/  /'
  echo "  vs old tip: $(git -C "${wt}" diff --shortstat "${tip}" HEAD)"
  # Fast-forward push: HEAD's first parent IS the current remote tip, so no --force is needed and no
  # published history is rewritten.
  echo "  PUSH:   git -C ${wt} push ${remote} HEAD:${BRANCH}"
  echo "  CLEANUP after push:   git -C ${clone} worktree remove --force ${wt}"
}

while [[ $# -gt 0 && "$1" == --* ]]; do
  case "$1" in
    --sdk-version) SDK_VERSION="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ $# -gt 0 ]]; then
  for want in "$@"; do
    for spec in "${LANES[@]}"; do
      # `[[ … ]] && run_lane` as the loop's last command makes the SCRIPT's exit status that of the
      # final non-matching test, so a wholly successful named-lane run exited 1 — an exit code that
      # lies about the outcome. An if statement has no such fall-through status.
      if [[ "${spec%%|*}" == "${want}" ]]; then run_lane "${spec}"; fi
    done
  done
else
  # Default = the lanes with an OPEN PR, checked against GitHub rather than hardcoded: the previous
  # hardcoded skip list said sscs's PR was closed long after it was reopened as #1958, so the default
  # run silently left the branch behind every converter change. A lane whose PR state can't be read
  # (no gh, no auth, no network) is RUN rather than skipped — refreshing a lane with no open PR wastes a
  # worktree, but skipping one that has an open PR leaves it showing stale converter output.
  for spec in "${LANES[@]}"; do
    lane="${spec%%|*}"
    clonerel="$(echo "${spec}" | cut -d'|' -f2)"
    repo="hmcts/$(basename "${clonerel}")"
    if command -v gh >/dev/null 2>&1; then
      open="$(gh pr list --repo "${repo}" --head "${BRANCH}" --state open --json number \
        --jq 'length' 2>/dev/null || echo unknown)"
      if [[ "${open}" == "0" ]]; then
        echo "########## ${lane}: no open ${BRANCH} PR on ${repo} — skipped"
        continue
      fi
    fi
    run_lane "${spec}"
  done
fi
