# UserProfile sheet investigation

Read-only investigation into what the CCD definition `UserProfile` sheet does, whether it's a live feature or a historical artifact, and whether the converter's current hard-fail (added in commit `4976fe5f`) is the right call. Four questions, each answered with citations. No recommendation is finalized here — the maintainer decides.

## 1. Exact semantics of a row

A `UserProfile` row maps one IDAM user to the jurisdiction/case-type/state that should be pre-selected as their workbasket filter the first time they open the case list in ExUI/XUI.

Columns (from the importer's column mapping and confirmed against fixture files):

- `UserIDAMId` — the user's IDAM email/username.
- `WorkBasketDefaultJurisdiction` — e.g. `PUBLICLAW`, `PROBATE`, `CIVIL`, `SSCS`, `PRIVATELAW`.
- `WorkBasketDefaultCaseType` — e.g. `CARE_SUPERVISION_EPO`, `GrantOfRepresentation`, `CIVIL`, `Benefit`, `PRLAPPS`.
- `WorkBasketDefaultState` — e.g. `Open`, `Submitted`, `CaseCreated`, `AWAITING_RESPONDENT_ACKNOWLEDGEMENT`, `appealCreated`.
- `LiveFrom` — optional date, present in fpl/sscs/prl fixtures, absent in civil/probate.

Source: `ccd-definition-store-api/excel-importer/src/main/java/uk/gov/hmcts/ccd/definition/store/excel/util/mapper/ColumnName.java:79,82-84`, `.../util/mapper/SheetName.java:21`, `.../parser/UserProfilesParser.java:38-55`. A row with any of the four required fields blank is silently dropped (`UserProfilesParser.java:43-47`).

Rows validate against jurisdictions/case-types/states parsed elsewhere in the same import (`domain/.../workbasket/WorkBasketUserDefaultService.java:46-53`, `UserProfileValidatorImpl` + the four `UserProfileInvalid*ValidationError` classes) — e.g. it will reject a row whose jurisdiction doesn't match a jurisdiction defined in the same spreadsheet.

## 2. Is it imported and honoured today, or vestigial?

**Imported: yes, and mandatory.** `UserProfilesParser.java:26-27` throws `MapperException("A definition must contain a UserProfile worksheet")` if the sheet is absent from an uploaded definition — every definition import fails without it. No `@Deprecated` annotation, feature flag, or TODO guards any part of this path. `ccd-definition-processor`'s canonical template (`ccd-definition-processor/data/ccd-template.xlsx`, dated 2024-09-17) still lists `UserProfile` as one of its 29 sheets.

**Honoured: yes, end-to-end, through a live microservice** — with one caveat on the very last hop into current XUI code.

The full chain:

1. **Definition-store writes it out.** On import, `ImportServiceImpl.java` (~line 291) calls `WorkBasketUserDefaultService.saveWorkBasketUserDefaults(...)`, which PUTs the parsed rows to an external service at `ccd.user-profile.host` + `/user-profile/users` (`ApplicationParams.java:57-59`, `application.properties:52`). There is **no local DB table** for this in `ccd-definition-store-api` — it's a fire-and-forget HTTP call.

2. **`ccd-user-profile-api` owns the data.** A standalone, actively deployed Spring Boot service at `apps/ccd/ccd-user-profile-api/`, with its own Postgres (`user_profile` table, `UserProfileEntity.java`) and its own Helm/Flux config. Flux evidence of live deployment: `platops/cnp-flux-config/apps/ccd/ccd-user-profile-api/{aat,demo,ithc,perftest,prod}.yaml`, image pinned as recently as `prod-1da084d-20260619172631`. Git log shows commits into 2026-05, comparable cadence to `ccd-data-store-api`/`ccd-definition-store-api`. This is a first-class, currently-maintained member of the CCD stack, not an orphaned service.

3. **Data-store reads it back live.** `ccd-data-store-api/src/main/java/uk/gov/hmcts/ccd/data/user/DefaultUserRepository.java` (`getUserDefaultSettings`) does a `GET` against `USER_PROFILE_HOST` + `/user-profile/users` on every relevant request. `UserService.getUserProfile()` (~lines 43-82) folds the result into a `WorkbasketDefault`, exposed at `GET /internal/profile` via `UIUserProfileController`.

4. **XUI fetches it, but the current UI code doesn't appear to act on it.** `ccd-case-ui-toolkit`'s `ProfileService.get()` (`profile.service.ts`) does call `GET /internal/profile` and deserializes `profile.default.workbasket.{jurisdiction_id,case_type_id,state_id}`. But grepping both `ccd-case-ui-toolkit` and `rpx-xui-webapp` found **no code path that reads `profile.default.workbasket`** — consumers of `ProfileService` only use role checks (`isSolicitor()`, `isCourtAdmin()`) and the jurisdictions list. The case-list workbasket filter (`case-list.component.ts` `setCaseListFilterDefaults()`) instead falls back to `localStorage` or simply "first jurisdiction returned by the role-based jurisdictions endpoint." The newer work-allocation/task UI bypasses this mechanism entirely, driven by AM role-assignment data instead.

**Bottom line:** the sheet is mandatory, actively parsed, and flows through a fully live microservice round-trip on the backend — this is not vestigial infrastructure. But the practical payoff — pre-selecting a caseworker's workbasket filter — looks functionally dead in the current XUI codebase, which computes that default a different way. So: alive end-to-end at the infrastructure/API level; the one place a human would actually notice the effect (the case-list filter) appears to no longer consume it. Note this was a repo-wide grep, not a runtime trace — worth a quick sanity check with the CCD team before treating it as fully confirmed dead-on-arrival in the UI.

Separately, an unrelated HLD note (Confluence-sourced, `apps/ccd/docs/.work/confluence/explanation-architecture/_summary.md:24,45`, flagged as confluence-only/no source-level markers) lists the **User Profile microservice** as a roadmap item for future decommissioning — that's a forward-looking architectural intent, not evidence it's deprecated today.

## 3. Real user emails in source control

Fixture rows mix genuinely disposable/synthetic addresses with what look like real individual staff/contractor emails:

- **probate** (38 rows): mostly `gmail.com` test accounts, but `krishma.patel@hmcts.net` and `nigel.dunne@solirius.com` read as real individual names at real institutional domains (HMCTS and a delivery-partner domain), not disposable test accounts.
- **fpl** (22 rows): a mix of `mailinator.com`/`mailnesia.com` disposable addresses and first-name-only addresses at real council domains (`damian@swansea.gov.uk`, `sam@hillingdon.gov.uk`, `raghu@wiltshire.gov.uk`) — plausibly real council staff test accounts from a shared demo environment.
- **civil, sscs, prl**: overwhelmingly synthetic (`example.com`, gmail plus-addressing, `mailinator.com`) — low PII concern.

This is real, if partial, PII sitting in git history via committed definition fixtures, keyed to an environment-specific concept (which user, on which environment, sees which workbasket by default) rather than to the case-type model. That's a genuine reason to think this data doesn't belong in a portable Java case-type definition shared across environments and teams: baking `nigel.dunne@solirius.com` into generated Java would fix that email into the model's source *and* make it harder to vary or redact per-environment than the current spreadsheet-per-environment approach. Notably, `ccd-definition-processor`'s own README (`README.md:208`) already treats `UserProfile.json` as an excludable, environment-varying file (`-e 'UserProfile.json, *-nonprod.json'`) for non-prod builds — i.e. upstream CCD tooling already treats this sheet as environment-specific config, not case-type model, independent of anything this converter does.

## 4. Recommendation input: keep hard-fail vs. restore passthrough

Both options were considered previously — this repo's own docs (`docs/passthrough-reduction-plan.md`, `docs/json-conversion-fidelity.md`) already documented the hard-fail decision made in commit `4976fe5f`, on the grounds that the SDK has no API for per-user workbasket defaults and silently carrying them through as raw JSON passthrough hid a construct the Java definition can't express (`DefaultDefinitionLinker.java:1937-1942`).

What this investigation adds, for the maintainer's judgment:

- **For keeping the hard-fail:** the data is genuinely environment/deployment config (which real person sees which default on which environment), not case-type model — reinforced by finding that upstream CCD tooling (`ccd-definition-processor`) already excludes it per-environment rather than versioning it uniformly, and by the presence of real staff/contractor emails that shouldn't be baked into a shared Java definition. The backend plumbing for this feature is live, but its one user-visible effect (case-list default filter) doesn't appear to be consumed by current XUI code — weakening the case that migrating teams lose real functionality by not modeling it in Java.
- **For restoring passthrough:** the sheet is still mandatory at import time. Teams migrating from spreadsheet/JSON to this SDK need *some* way to keep shipping a valid `UserProfile` sheet to definition-store, since a definition upload without one throws `MapperException` and fails outright. If the converter's hard-fail (`OMITTED_FAIL` / `UNSUPPORTED_SHEET`, requiring `--allow-gaps`) doesn't come with a companion hand-authoring path for `UserProfile.json`, teams need to know that and have a clear route to produce it standalone — worth confirming this is documented for adopting teams, since the current design already anticipates a "hand-authored UserProfile" per `docs/json-conversion-fidelity.md`.

No further code, config, or git changes were made as part of this investigation.
