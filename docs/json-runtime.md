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

### Packaging JSON definitions

Services should copy JSON CCD definitions into the Docker image as filesystem files and configure
`JsonBackedCCDConfig` with that location:

```text
/opt/app/json-ccd-definitions/CaseTypeA
```

For example:

```java
Path definitions = Path.of("/opt/app/json-ccd-definitions/CaseTypeA");
return new JsonBackedCCDConfig<MyCaseData, MyState, MyRole>(
    support,
    "case-type-a",
    definitions.toUri().toString()
) {};
```

A `classpath:` location works too, including from inside the service's jar.

The definition is read as ccd-definition-processor reads it. Each sheet is either a file (`CaseType.json`) or a folder
(`CaseEvent/`) whose JSON files, in subfolders at any depth, are read together. A file may hold an array of rows or a
single row object.

A definition that keeps per-environment variants, such as `*-prod.json` and `*-nonprod.json`, passes the file name
patterns this environment leaves out. They work like ccd-definition-processor's `-e` option:

```java
return new JsonBackedCCDConfig<MyCaseData, MyState, MyRole>(
    support,
    "case-type-a",
    "classpath:ccd-definitions/case-type-a",
    List.of("*-nonprod.json", "*-testing*.json")
) {};
```

### Sharing and overriding events in Java

A Java `CCDConfig` can apply to multiple JSON-backed case types by overriding `caseTypeIds()`, for example:

```java
@Override
public Set<String> caseTypeIds() {
    return Set.of("ET_EnglandWales", "ET_Scotland");
}
```

Use the same case data, state and role types as the corresponding JSON-backed configs.

Leave Java overrides at the default Spring order so they run after JSON-backed configs, which use
`Ordered.HIGHEST_PRECEDENCE`.

A Java event applied after JSON with the same ID replaces the complete JSON-derived runtime event, including its
callbacks and retry settings, so declare everything the replacement needs in Java. A callback-based replacement
must retain the original JSON callbacks and those of the event it replaces; dropping a phase fails configuration
resolution, including after an intermediate decentralised replacement.

`decentralisedEvent` replaces both callback phases with its submit handler. Retain JSON callback URLs and endpoints
until older instances and outstanding callback requests have drained.

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

`JsonCallbackBridge` indexes Spring MVC `POST` routes from `RequestMappingHandlerMapping`. A service can instead
register a [`JsonCallbackHandler`][jch] bean; see [Service callback handlers](#service-callback-handlers).

Local callback URLs are invoked directly against the resolved controller method. This avoids HTTP loopback and keeps
`about-to-submit` inside the same transaction as case persistence.

External callback URLs are invoked over HTTP. This is required for JSON definitions that call other services, for
example AAC.

The local/external boundary is placeholder based:

* callback URLs using `decentralisation.local-callback-placeholder` are local
* callback URLs using a key in `decentralisation.external-callback-base-urls` are external
* all other absolute URLs are external
* unknown placeholder URLs fail fast

For ET this keeps the CCD definition convention intact while making runtime substitution explicit:

```yaml
decentralisation:
  local-callback-placeholder: ET_COS_URL
  external-callback-base-urls:
    "[CCD_DEF_AAC_URL]": ${AAC_URL:http://localhost:4454}
```

With that configuration, `${ET_COS_URL}/callback` is dispatched locally by path, and
`${CCD_DEF_AAC_URL}/noc/check-noc-approval` is invoked over HTTP using the configured AAC base URL. The bridge does not
resolve arbitrary `${...}` values from application properties or environment variables.

Callback paths are normalised before route matching:

* configured local placeholder bases such as `${ET_COS_URL}` are stripped for route lookup
* query strings and fragments are stripped
* duplicate slashes and trailing slashes are normalised

If a JSON callback is classified as local, startup/config construction validates that a `JsonCallbackHandler` handles
the normalised path, or else that exactly one supported Spring `POST` handler exists for it. Missing or ambiguous
local handlers fail fast. External callbacks are not required to have local handlers.

### Service callback handlers

A service whose callbacks share templated paths, or that dispatches callbacks itself, registers a
[`JsonCallbackHandler`][jch] bean. For example, Civil maps every event to `/cases/callbacks/{callback-type}` and
`/cases/callbacks/version/{version}/{callback-type}`, and dispatches them to its own handlers.

* The bridge asks registered handlers first for every local callback, both at startup validation and at submission.
  It falls back to the Spring route lookup only when no handler claims the path.
* More than one handler claiming a path fails.
* `handle` receives the normalised path, the CCD callback request, and the `Authorization`, `ServiceAuthorization` and
  `Client-Context` headers.
* It returns the callback response body, or a `ResponseEntity` around it to send a `Client-Context` header back.

### Supported controller method shape

Controller methods must use Spring MVC annotations for callback inputs.

Supported parameters are:

* `@RequestBody` on a CCD callback request type or another type Jackson can build from the callback payload
* `@RequestHeader("Authorization") String`
* `@RequestHeader("ServiceAuthorization") String`
* `@RequestHeader(value = "Client-Context", required = false) String`

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

For events without a Java override, a phase with no JSON callback URL is a no-op.

### Client-Context

ExUI and WA use the `Client-Context` header, for example for a callback to ask ExUI to complete a task. The runtime
carries it as data-store does for centralised callbacks:

* data-store forwards the submission's `Client-Context` to the runtime.
* Every JSON callback is sent the current context: service handlers, local controllers and external URLs.
* A context a callback returns is merged into the one it was sent, replacing top-level fields, as data-store's
  `ClientContextUtil` does. Later callbacks are sent the merged context.
* A returned context that is not a base64-encoded JSON object is ignored.
* The submit response carries the merged context back for data-store to merge and return to the frontend. If no
  callback returned one, no header is sent.
* The final context is saved with the event, so an idempotent replay of the submission returns the same context.

This needs data-store to forward and merge the header for decentralised submissions
([hmcts/ccd-data-store-api#3077](https://github.com/hmcts/ccd-data-store-api/pull/3077)).

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
* definitions read from subfolders, single-row files, excluded variants and classpath jars
* `JsonCallbackHandler` precedence and ambiguity
* `Client-Context` carry and merge through data-store, callbacks and idempotent replays

[rcr]: ../sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/ResolvedConfigRegistry.java
[jch]: ../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/json/JsonCallbackHandler.java
[jbcc]: ../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/json/JsonBackedCCDConfig.java
[jcb]: ../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/impl/json/JsonCallbackBridge.java
