# JSON Legacy Callback Dispatch Takeover Plan

## Summary

Start a fresh takeover branch from `origin/master`, commit incrementally, and keep the scope to callback dispatch
inside the existing legacy submit flow.

Use two levels of real test coverage:

- a small e2e cftlib fixture for fast, controlled regression coverage;
- a real `et-ccd-callbacks` submodule integration layer to prove ET controllers, ET definitions, and decentralised
  persistence routing work.

Mocks should be avoided wherever possible. Prefer cftlib/API behaviour tests and narrow pure unit tests.

## Branch And Commit Shape

- Create parent branch `json-legacy-callback-dispatch` from `origin/master`.
- First commit: add `PLAN.md`.
- Commit in focused stages:
  - legacy dispatcher abstraction and submit-flow integration;
  - JSON/Spring handler lookup and validation;
  - handler invocation and structural response adaptation;
  - small `e2e` cftlib fixture/tests;
  - add ET submodule and SDK plugin wiring;
  - ET cftlib tests using real ET controllers/definitions;
  - cleanup/docs.
- For ET changes, create a branch inside the `et-ccd-callbacks` submodule, commit there first, then commit the parent
  repo gitlink update.

## Implementation Changes

- Keep `LegacyCallbackSubmissionHandler` as the single about-to-submit/submitted lifecycle.
- Add an internal dispatcher abstraction used by that handler.
- Keep the SDK callback dispatcher for generated SDK configs.
- Add a JSON dispatcher that resolves CCD definition callback URLs to Spring `HandlerMethod`s via
  `RequestMappingHandlerMapping`.
- Validate startup resolution for every local JSON about-to-submit/submitted callback: exactly one POST handler or fail
  clearly.
- Invoke matched controller methods directly inside the existing legacy flow.
- Support ET-style Spring signatures and return values without requiring ET controllers to implement SDK interfaces.
- Adapt direct objects and `ResponseEntity` structurally into internal legacy callback results.
- Remove/avoid `JsonDefinitionSubmissionHandler`, public callback interfaces, duplicate submitted response types, public
  `LegacySubmitOutcome`, and duplicate retry models.
- Use the existing legacy submitted retry handling.

## Test Strategy

- Aim for full coverage of new dispatch behaviour with the minimum mocks possible.
- Use cftlib/API tests before Mockito tests.
- Keep unit tests only for pure behaviour that is awkward or wasteful to cover through cftlib:
  - URL/path normalisation;
  - missing/duplicate handler validation;
  - response adaptation for direct objects and `ResponseEntity`.
- Add a new cftlib test class in `test-projects/e2e`; do not expand the large `TestWithCCD.java`.
- The small fixture should include an ET-shaped controller and JSON CCD definition, then test through real CCD APIs:
  - about-to-submit mutates and persists data;
  - about-to-submit errors block persistence;
  - submitted confirmation body/header is returned;
  - submitted runs after commit;
  - submitted retry uses legacy retry config;
  - duplicate/idempotent submission does not rerun submitted callbacks.

## ET Submodule Integration

- Add `test-projects/et-ccd-callbacks` as a git submodule from `hmcts/et-ccd-callbacks`, matching the existing PR's
  submodule shape.
- Add it as an included build from the parent repo.
- In the ET submodule, apply `hmcts.ccd.sdk` using local `../../sdk` composite build wiring, as Ellis did.
- Configure the SDK plugin for decentralised runtime support, while keeping ET's existing JSON/XLSX definitions as the
  source of truth for these tests.
- Add ET cftlib tests in the submodule that run against real ET controllers and imported ET definitions.
- Prove decentralised persistence calls are routed through the resolved ET handlers, not through SDK callback reflection.
- Keep ET cftlib tests on a separate verification task initially, not wired into default `check`, because the real ET
  fixture is heavier.

## Verification

- Run targeted unit tests for pure dispatch components.
- Run `./gradlew -i e2e:cftlibTest`.
- Run the separate ET cftlib verification task, for example via the parent wrapper or
  `./gradlew -p test-projects/et-ccd-callbacks cftlibTest`.
- Check coverage for new dispatch code; add more real-behaviour tests before resorting to mocks.
- Run `./gradlew check` or `./gradlew -i allTests` before handoff where practical.

## Assumptions

- Scope remains about-to-submit and submitted callbacks only.
- CCD definition callback URLs are the source of truth.
- ET compatibility means no full ET callback rewrite and no required SDK callback interfaces.
- The small e2e fixture remains the fast regression suite; ET submodule tests are the real compatibility proof.
