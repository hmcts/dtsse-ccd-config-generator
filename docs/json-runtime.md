# JSON Runtime

## Context

Some services, including ET, do not use the SDK config generator to organise callbacks.

They define callback URLs in CCD definition JSON and implement handlers in manually wired Spring controllers.

For this design, only `about-to-submit` and `submitted` are in scope.

`about-to-start` and mid-event callbacks remain unchanged and continue through existing paths.

## Problem

Decentralised submission must preserve CCD guarantees for legacy JSON-wired services:

* `about-to-submit` is atomic with persistence
* service-owned table writes from `about-to-submit` commit or roll back with the event
* `submitted` runs after commit

A loopback HTTP approach (`http://localhost/...`) is not acceptable because it creates a separate request
and transaction boundary.

## Design goals

* Avoid implicit Spring handler mapping dispatch.
* Use explicit callback ownership in code.
* Keep `about-to-submit` transactional with persistence.
* Keep `submitted` post-commit.
* Fail fast on ambiguous or missing handler bindings.

## Non-goals

* Rewriting ET callback business logic.
* Migrating services to SDK-generated callback endpoints.
* Changing `about-to-start` or mid-event execution paths.

## Existing SDK building blocks

This design extends existing registry concepts:

* [ResolvedConfigRegistry][resolved-config-registry]
* [DefinitionRegistry][definition-registry]

Definition snapshots are produced by cftlib:

* [dumpCCDDefinitions task wiring][cftlib-plugin]
* [dumpDefinitionSnapshots implementation][cftlib-api]

## Proposed approach

### 1. Explicit callback handler interfaces

Define explicit submit-phase contracts.

* `AboutToSubmitCallbackHandler`
* `SubmittedCallbackHandler`

Each handler declares the callback it owns via a binding key:

* `caseTypeId`
* `eventId`

Example shape:

```java
public record CallbackBinding(String caseTypeId, String eventId) {}

public interface AboutToSubmitCallbackHandler {
  CallbackBinding binding();
  AboutToStartOrSubmitResponse handle(CallbackRequest request);
}

public interface SubmittedCallbackHandler {
  CallbackBinding binding();
  SubmittedCallbackResponse handle(CallbackRequest request);
}
```

### 2. Runtime injection and registry build

At startup, runtime injects all handler beans and builds two maps:

* `Map<CallbackBinding, AboutToSubmitCallbackHandler>`
* `Map<CallbackBinding, SubmittedCallbackHandler>`

Startup validation:

* fail on duplicate bindings
* optionally cross-check against `DefinitionRegistry` callback configuration to detect drift

### 3. Transaction model

Submit flow:

1. Start submission transaction and acquire case lock.
2. Resolve and execute `about-to-submit` handler by `(caseTypeId,eventId)`.
3. Apply callback response and persist case/event data.
4. Commit.
5. Resolve and execute `submitted` handler after commit.

Outcome:

* `about-to-submit` remains atomic with persistence.
* callback DB writes in the same transaction share commit/rollback outcome.
* `submitted` remains post-commit and non-atomic with persistence.

## Error handling and retries

* `about-to-submit`: handler errors fail submission and roll back transaction.
* `submitted`: execute post-commit; apply CCD-compatible retry behaviour; do not roll back committed persistence.

## Compatibility impact

This supports ET and similar services with large manually organised callback surfaces while avoiding implicit routing
magic.

Handlers are explicit, testable, and discoverable in code ownership terms.

## Validation and testing

Required tests:

* binding uniqueness and startup validation tests
* missing binding failure tests
* optional definition-to-binding consistency tests
* transaction integration test proving atomicity of callback writes + event persistence
* rollback test on `about-to-submit` handler failure
* post-commit retry behaviour test for `submitted`
* ET submit path regressions

[resolved-config-registry]: https://github.com/hmcts/dtsse-ccd-config-generator/blob/master/sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/ResolvedConfigRegistry.java
[definition-registry]: https://github.com/hmcts/dtsse-ccd-config-generator/blob/master/sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/impl/DefinitionRegistry.java
[cftlib-plugin]: https://github.com/hmcts/rse-cft-lib/blob/main/cftlib/rse-cft-lib-plugin/src/main/java/uk/gov/hmcts/rse/CftLibPlugin.java
[cftlib-api]: https://github.com/hmcts/rse-cft-lib/blob/main/cftlib/lib/runtime/src/main/java/uk/gov/hmcts/rse/ccd/lib/CFTLibApiImpl.java
