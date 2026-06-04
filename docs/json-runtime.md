# JSON Runtime

## Context

Some services, including ET, define CCD configuration in JSON and wire callbacks through Spring controllers rather
than through SDK config classes.

For this design, only `about-to-submit` and `submitted` are in scope.

`about-to-start` and mid-event callbacks remain unchanged and continue through existing CCD paths.

## Problem

Decentralised submission must preserve CCD guarantees for legacy JSON-wired services:

* `about-to-submit` is atomic with persistence
* service-owned table writes from `about-to-submit` commit or roll back with the event
* `submitted` runs after commit

A loopback HTTP approach (`http://localhost/...`) is not suitable for local callbacks because it creates a separate
request and transaction boundary.

## Design goals

* Keep the existing decentralised submission flow as the source of truth.
* Populate the normal SDK runtime config registry from JSON callback definitions.
* Keep `about-to-submit` transactional with persistence.
* Keep `submitted` post-commit.
* Fail fast when a JSON callback URL is expected to be local but no supported controller route exists.
* Keep external callbacks, such as AAC callbacks, external.

## Non-goals

* Rewriting ET callback business logic.
* Migrating services to SDK-generated callback endpoints.
* Changing `about-to-start` or mid-event execution paths.

## Runtime approach

JSON-backed services register a [`JsonBackedCCDConfig`][jbcc] bean for each JSON case type they need to run through
the decentralised runtime.

`JsonBackedCCDConfig` reads the service CCD JSON rows and builds a normal SDK `CCDConfig`:

* `CaseType` supplies the case type and jurisdiction IDs.
* `CaseEvent` supplies the event IDs.
* `CallBackURLAboutToSubmitEvent` becomes an SDK `about-to-submit` callback.
* `CallBackURLSubmittedEvent` becomes an SDK `submitted` callback.
* `RetriesTimeoutURLSubmittedEvent` is copied onto the SDK event retry metadata.

The resulting events are resolved into [`ResolvedConfigRegistry`][rcr], so existing decentralised runtime components
continue to use their normal config lookup, audit, projection, idempotency and persistence behaviour.

This avoids maintaining a second submission pipeline for JSON services. The branch still keeps the callback URL
learning from the earlier runtime work, but routes it through the existing SDK callback machinery.

### Callback invocation

[`JsonCallbackBridge`][jcb] adapts JSON callback URLs into SDK callback handlers.

When a callback runs, the adapter builds the CCD callback request shape expected by existing JSON services:

* `case_details`
* `case_details_before`
* `event_id`

The current SDK `CaseDetails<T, S>` is converted into that CCD payload shape before invocation. For ET this is a
transitional cost rather than the long-term target architecture. ET already deserialises incoming CCD callback payloads
into its domain models, so the bridge duplicates an existing conversion path without introducing a new conversion pair.

### Local and external callbacks

`JsonCallbackBridge` indexes Spring MVC `POST` routes from `RequestMappingHandlerMapping`.

Local callback URLs are invoked directly against the resolved controller method. This avoids HTTP loopback and keeps
`about-to-submit` inside the same transaction as case persistence.

External callback URLs are invoked over HTTP. This is required for JSON definitions that call other services, for
example AAC.

The local/external boundary is explicit:

* URLs that start with `decentralisation.local-callback-base-url` are local
* all other absolute or resolvable placeholder URLs are external

This prevents an external callback from being dispatched locally simply because its path matches a local controller.

Callback paths are normalised before route matching:

* the configured local callback base URL is stripped from local URLs
* scheme, host and port are stripped from absolute URLs for route lookup
* query strings and fragments are stripped
* duplicate slashes and trailing slashes are normalised

If a JSON callback is classified as local, startup/config construction validates that exactly one supported Spring
`POST` handler exists for the normalised path. Missing or ambiguous local handlers fail fast. External callbacks are
not required to have local handlers.

### Supported controller method shape

Controller methods must use Spring MVC annotations for callback inputs.

Supported parameters are:

* `@RequestBody` on a CCD callback request type or another type Jackson can build from the callback payload
* `@RequestHeader("Authorization") String`
* `@RequestHeader("ServiceAuthorization") String`
* `@RequestHeader HttpHeaders`

Supported return shapes are:

* a callback response object
* `ResponseEntity` whose body is a callback response object
* `void` or `null` for `submitted` callbacks that do not need confirmation content

For `about-to-submit`, a matched callback returning `null` fails the submission because there is no response to apply.

## Submit flow

The submit flow remains the standard decentralised runtime flow:

1. `CaseSubmissionService` retrieves the user and acquires the idempotency/case lock inside the transaction.
2. `LegacyCallbackSubmissionHandler` finds the resolved SDK event for the case type and event ID.
3. For JSON-backed events, the SDK callback delegates to `JsonCallbackBridge`.
4. The adapter invokes the local controller directly or an external callback over HTTP.
5. Returned data, state, security classification, errors and warnings are mapped back into the SDK callback response.
6. Validation errors from `about-to-submit` are returned to CCD and the transaction is rolled back.
7. If validation passes, case data, metadata, event history, projection and outbox records are persisted.
8. The transaction commits.
9. The deferred response supplier invokes the `submitted` callback and applies confirmation header/body values.

If no JSON callback URL is configured for an event phase, that phase is a no-op.

## ET compatibility notes

The SDK tooling is typed around a case data class and state enum. JSON-backed ET configuration does not exactly follow
the same shape:

* ET uses clear domain models for each case family, but England/Wales and Scotland both use `CaseData`.
* ET callback controllers already bind incoming CCD callback payloads to those domain models.
* Runtime roles are not materially used by this bridge beyond the SDK generic type.
* State enums are still needed because SDK callback execution and case projection are typed by state.

Where a single `CaseView` generic type can match more than one case type, the view can override `caseTypeIds()` to
declare the supported CCD case type IDs explicitly.

## Validation and testing

Covered behaviour includes:

* JSON event rows becoming SDK callbacks
* local callback route invocation
* external callback invocation
* authorization and service authorization propagation
* local/external URL classification
* missing local callback route failure
* duplicate local callback route failure
* typed `@RequestBody` conversion
* `ResponseEntity` status handling
* `about-to-submit` validation errors rolling back persistence
* submitted retry behaviour using SDK event retry metadata
* ET-style shared domain model projection via explicit case type IDs

[rcr]: ../sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/ResolvedConfigRegistry.java
[jbcc]: ../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/json/JsonBackedCCDConfig.java
[jcb]: ../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/impl/json/JsonCallbackBridge.java
