# JSON Runtime

## Context

Some services, including ET, do not use the SDK config generator to organise callbacks.

They define callback URLs in CCD JSON definition data and implement handlers as manually mapped Spring controllers, for
example with `@RequestMapping` and `@PostMapping`.

For this design, only `about-to-submit` and `submitted` are in scope.

`about-to-start` and mid-event callbacks are unchanged and continue to run through existing paths.

## Problem

Decentralised submission must preserve CCD callback guarantees for legacy JSON-wired services:

* `about-to-submit` is atomic with persistence
* service-owned table writes from `about-to-submit` commit or roll back with the event
* `submitted` runs after commit

Loopback HTTP callback invocation (`http://localhost/...`) breaks this, because it introduces a separate request and
transaction boundary.

## Design goals

* Support JSON/manual callback wiring without forcing SDK callback registration.
* Discover callback URLs from dumped CCD definitions.
* Dispatch to existing controllers without per-controller custom wiring.
* Keep `about-to-submit` transactional with persistence.
* Keep `submitted` post-commit.

## Non-goals

* Rewriting ET callback business logic.
* Migrating services to SDK-generated callback endpoints.
* Changing `about-to-start` or mid-event execution paths.

## Existing SDK building blocks

This design should extend, not replace, existing registry concepts:

* [ResolvedConfigRegistry](https://github.com/hmcts/dtsse-ccd-config-generator/blob/master/sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/ResolvedConfigRegistry.java):
  generated SDK event/callback registry for config-generator services.
* [DefinitionRegistry](https://github.com/hmcts/dtsse-ccd-config-generator/blob/master/sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/impl/DefinitionRegistry.java):
  loaded case type definitions from `build/cftlib/definition-snapshots`.

Definition snapshots are produced by cftlib's
[dumpCCDDefinitions task wiring](https://github.com/hmcts/rse-cft-lib/blob/main/cftlib/rse-cft-lib-plugin/src/main/java/uk/gov/hmcts/rse/CftLibPlugin.java)
and
[dumpDefinitionSnapshots implementation](https://github.com/hmcts/rse-cft-lib/blob/main/cftlib/lib/runtime/src/main/java/uk/gov/hmcts/rse/ccd/lib/CFTLibApiImpl.java).

The callback URL fields come from the case definition model:

* [CaseTypeDefinition](https://github.com/hmcts/rse-cft-lib/blob/main/projects/aac-manage-case-assignment/src/main/java/uk/gov/hmcts/reform/managecase/client/datastore/model/CaseTypeDefinition.java)
* [CaseEventDefinition](https://github.com/hmcts/rse-cft-lib/blob/main/projects/aac-manage-case-assignment/src/main/java/uk/gov/hmcts/reform/managecase/client/datastore/model/CaseEventDefinition.java)

## Proposed approach

### 1. Look up controller from DefinitionRegistry

Use `DefinitionRegistry` case type data to resolve controller callback targets by:

* `caseTypeId`
* `eventId`
* `phase` (`ABOUT_TO_SUBMIT`, `SUBMITTED`)

Resolved values:

* normalized callback target (`path + query`, host ignored)
* submitted retry metadata

### 2. Dispatch internally through Spring MVC

Invoke callbacks in-process using Spring handler mapping/adapter:

* resolve target `path + query`
* construct internal request with callback payload
* resolve mapped handler
* invoke handler directly in JVM
* deserialize callback response

This reuses existing controller mappings while avoiding network hops.

### 3. Keep transaction semantics

Submit flow:

1. Start submission transaction and acquire case lock.
2. Execute `about-to-submit` via internal dispatcher.
3. Apply callback response and persist case/event data.
4. Commit.
5. Execute `submitted` after commit.

Outcome:

* `about-to-submit` remains atomic with persistence.
* callback DB writes in the same transaction share commit/rollback outcome.
* `submitted` remains post-commit and non-atomic with persistence.

## Why not loopback HTTP

Loopback HTTP is not acceptable for `about-to-submit`:

* separate request lifecycle
* separate transaction context
* partial commit risk if callback side effects persist before event rollback
* added lock contention risk

## Error handling and retries

* `about-to-submit`: callback errors fail submission and roll back transaction.
* `submitted`: execute post-commit; apply CCD-compatible retry behaviour; do not roll back committed persistence.

## Validation and testing

Required tests:

* definition callback URL extraction for `ABOUT_TO_SUBMIT` and `SUBMITTED`
* handler resolution for registered callback URLs
* transaction integration test proving atomicity of callback writes + event persistence
* rollback test on `about-to-submit` callback failure
* post-commit retry behaviour test for `submitted`
* ET submit path regressions
