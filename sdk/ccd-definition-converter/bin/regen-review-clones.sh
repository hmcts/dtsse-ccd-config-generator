#!/usr/bin/env bash
#
# regen-review-clones.sh — refresh the retrofit review clones' patch + companion sources after a
# converter change, in place, WITHOUT re-running the expensive full gCC verify (the retrofit-verify
# stages 2–5 need a mavenLocal SDK publish + per-service build, which is intractable offline; this
# script runs the converter's phase-2 emission — stage 1 of retrofit-verify — which is what the
# converter change affects). RETROFIT-REPORT-NARRATIVE*.md files are preserved untouched.
#
# STRUCTURAL ROUND (2026-07-16): the companion sources now follow the reference-service package
# layout — one @Component per event in <root>.event, page classes in <root>.event.page, access
# classes in <root>.access, config split by concern in <root> — rooted at each lane's derived `.ccd`
# root package (config-package column below). At integration the orchestrator writes this tree into
# the real service clone's MAIN source tree (untracked in the clone's git status), retiring the flat
# generated-config/ directory; this offline refresh stages the same tree under the review clone for
# inspection.
#
# Each lane regenerates: <clone>/companion/  (the reference-layout companion tree) and
# ../patches/retrofit-<CaseType>.patch  (the model annotation patch).
#
# Usage: regen-review-clones.sh [lane-dir ...]   (default: all six)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONVERTER_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SDK_DIR="$(cd "${CONVERTER_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SDK_DIR}/.." && pwd)"
GRADLEW="${REPO_ROOT}/gradlew"
REPORTS="${CONVERTER_DIR}/retrofit-reports"
PATCHES="${REPORTS}/patches"

# lane | model-repo | model-source-root (rel to repo root) | definition dir(s, comma-sep) | case-type | model-class FQN | config-package | overlays (space-sep, may be empty) | env (space-sep)
LANES=(
  "probate-back-office|test-projects/probate-back-office|test-projects/probate-back-office/src/main/java|test-projects/probate-back-office/ccdImports/configFiles/CCD_Probate_Backoffice|GrantOfRepresentation|uk.gov.hmcts.probate.model.ccd.raw.request.CaseData|uk.gov.hmcts.probate.ccd.config|unshutter=!CCD_DEF_SHUTTERED:true shutter=CCD_DEF_SHUTTERED:true|CCD_DEF_ENV=nonprod CCD_DEF_PUBLISH=N"
  "fpl-ccd-configuration|test-builds/fpl-ccd-configuration|test-builds/fpl-ccd-configuration/service/src/main/java|test-builds/fpl-ccd-configuration/ccd-definition|CARE_SUPERVISION_EPO|uk.gov.hmcts.reform.fpl.model.CaseData|uk.gov.hmcts.reform.fpl.model|shuttered=CCD_DEF_SHUTTERED:true nonshuttered=!CCD_DEF_SHUTTERED:true|CCD_DEF_ENV=nonprod"
  "sscs-common|test-projects/sscs-common|test-projects/sscs-common/src/main/java|test-projects/sscs-tribunals-case-api/definitions/benefit/sheets|Benefit|uk.gov.hmcts.reform.sscs.ccd.domain.SscsCaseData|uk.gov.hmcts.reform.sscs.ccd.domain||CCD_DEF_ENV=nonprod CCD_DEF_PUBLISH=N"
  "et-ccd-callbacks|test-projects/et-ccd-callbacks|test-projects/et-ccd-callbacks/et-shared/src/main/java|test-projects/et-ccd-callbacks/ccd-definitions/jurisdictions/england-wales/json|ET_EnglandWales|uk.gov.hmcts.et.common.model.ccd.CaseData|uk.gov.hmcts.et.common.ccd.config||CCD_DEF_ENV=nonprod"
  "prl-cos-api|test-projects/prl-cos-api|test-projects/prl-cos-api/src/main/java|test-projects/prl-ccd-definitions/definitions/private-law/json|PRLAPPS|uk.gov.hmcts.reform.prl.models.dto.ccd.CaseData|uk.gov.hmcts.reform.prl.ccd.config||CCD_DEF_ENV=nonprod"
  "civil-service|test-projects/civil-service|test-projects/civil-service/src/main/java|test-projects/civil-ccd-definition/ccd-definition/civil|CIVIL|uk.gov.hmcts.reform.civil.model.CaseData|uk.gov.hmcts.reform.civil.model||CCD_DEF_ENV=nonprod"
)

run_lane() {
  local spec="$1"
  IFS='|' read -r lane modelrepo srcroot defs casetype modelclass configpkg overlays env <<<"${spec}"
  local clone="${REPORTS}/${lane}"
  local modelpkg="${modelclass%.*}"
  local modelsimple="${modelclass##*.}"
  local out="${CONVERTER_DIR}/build/regen/${casetype}"
  echo "########## ${lane} (${casetype}) ##########"
  rm -rf "${out}"; mkdir -p "${out}/companion" "${out}/report"

  local args=(--retrofit)
  IFS=',' read -ra defarr <<<"${defs}"
  for d in "${defarr[@]}"; do args+=(--input "${REPO_ROOT}/${d}"); done
  # No --config-package: the CLI derives the root package from the model package (cut at the first
  # model/models/domain segment, append .ccd unless already present), so companions land beside the
  # model.
  args+=(--case-type "${casetype}"
    --model-source-root "${REPO_ROOT}/${srcroot}"
    --model-repo-root "${REPO_ROOT}/${modelrepo}"
    --model-package "${modelpkg}" --model-class "${modelsimple}"
    --output-src "${out}/companion" --report-dir "${out}/report"
    --allow-gaps)
  for o in ${overlays}; do args+=(--overlay-suffix "$o"); done

  ( cd "${REPO_ROOT}" && "${GRADLEW}" -q -p sdk :ccd-definition-converter:run \
      --args="$(printf '%q ' "${args[@]}")" )

  # Sync companion sources + patch into the clone, preserving narratives. Companions go into the
  # service's REAL main source tree (maintainer feedback point 1) — they show as untracked files in
  # the clone's git status, exactly like code the team would own. Retire the old parking dirs.
  rm -rf "${clone}/generated-config" "${clone}/companion"
  local clonesrc="${clone}/src/main/java"
  [[ "${lane}" == "fpl-ccd-configuration" ]] && clonesrc="${clone}/service/src/main/java"
  [[ "${lane}" == "et-ccd-callbacks" ]] && clonesrc="${clone}/et-shared/src/main/java"
  mkdir -p "${clonesrc}"
  # Remove any stale ConverterGeneratedApplication.java left over from a prior regen (the converter no
  # longer emits it into service trees — a service must not carry two @SpringBootApplication classes).
  find "${clonesrc}" -name 'ConverterGeneratedApplication.java' -delete
  cp -a "${out}/companion/." "${clonesrc}/"
  mkdir -p "${PATCHES}"
  cp "${out}/report/retrofit.patch" "${PATCHES}/retrofit-${casetype}.patch"
  echo ">> ${lane}: companion + patch refreshed"
}

if [[ $# -gt 0 ]]; then
  for want in "$@"; do
    for spec in "${LANES[@]}"; do [[ "${spec%%|*}" == "${want}" ]] && run_lane "${spec}"; done
  done
else
  for spec in "${LANES[@]}"; do run_lane "${spec}"; done
fi
