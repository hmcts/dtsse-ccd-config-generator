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

* Avoid HTTP loopback.
* Keep callback ownership explicit in CCD definition JSON.
* Reuse Spring MVC request mapping, argument resolution and return value handling instead of duplicating it.
* Keep `about-to-submit` transactional with persistence.
* Keep `submitted` post-commit.
* Fail fast on ambiguous handler bindings and callback execution failures.
* Treat missing handler bindings as an explicit no-op outcome.

## Non-goals

* Rewriting ET callback business logic.
* Migrating services to SDK-generated callback endpoints.
* Changing `about-to-start` or mid-event execution paths.

## Existing SDK building blocks

This runtime uses existing registry concepts:

* [ResolvedConfigRegistry][rcr]
* [DefinitionRegistry][dr]

Definition snapshots are produced by cftlib:

* `dumpCCDDefinitions` task wiring
* `dumpDefinitionSnapshots` implementation

## Runtime approach

The JSON runtime is enabled by setting:

```properties
decentralisation.legacy-json-service=true
```

When enabled, [`CaseSubmissionService`][css] uses [`JsonDefinitionSubmissionHandler`][jdsh] for every submit request.

The runtime does not make HTTP loopback calls.
Instead, [`CallbackDispatchService`][cds] builds an explicit in-memory dispatch map from CCD definition callback URLs
to local callback endpoints. When a callback is required, it wraps the current `HttpServletRequest` with the callback
path, query string, JSON body and auth header, then invokes Spring MVC's `DispatcherServlet` in-process.

The servlet response is captured rather than written to the original CCD response, so Spring MVC handles controller
mapping, `@RequestBody`, `@RequestHeader`, `@RequestParam`, validation, message converters and `ResponseEntity`
semantics without a second HTTP request or a second transaction boundary.

### Definition-driven bindings

At startup, `CallbackDispatchService` loads case type definitions through `DefinitionRegistry`.

`DefinitionRegistry` looks for cftlib definition snapshots in:

* `build/cftlib/definition-snapshots`
* `/opt/app/build/cftlib/definition-snapshots`
* `classpath*:definition-snapshots/*.json`, if no filesystem snapshots are found

Each snapshot filename is treated as the case type ID.

For every case event, the dispatcher creates callback bindings from:

* `CallBackURLAboutToSubmitEvent`
* `CallBackURLSubmittedEvent`

The binding key is:

* `caseTypeId`
* `eventId`
* callback type, either `aboutToSubmit` or `submitted`

Duplicate bindings fail startup.

### Local callback selection

Callback URLs are bound locally when either:

* `decentralisation.local-callback-base-urls` is blank
* the URL is relative
* the URL begins with a Spring-style placeholder, such as `${CCD_DEF_CASE_SERVICE_BASE_URL}/callbacks/submit`
* the absolute URL host and effective port match one of the comma-separated
  `decentralisation.local-callback-base-urls` values

If `decentralisation.local-callback-base-urls` is set, absolute URLs for other hosts are ignored and behave as if
there is no local handler for that event.

The default base URL property also reads `ET_COS_URL`:

```properties
decentralisation.local-callback-base-urls=${ET_COS_URL:}
```

### Spring MVC dispatch

Callback definition URLs are normalised to servlet request paths before dispatch:

* property placeholders are stripped from the URL prefix
* scheme, host and port are stripped from absolute URLs
* query strings are preserved for Spring MVC request parameters
* fragments are stripped
* duplicate slashes and trailing slashes are normalised

If a local callback URL from the definition cannot be matched by Spring MVC, callback invocation fails with the captured
non-success response status.

### Supported controller method shape

Controller methods can use normal Spring MVC `@PostMapping` handler signatures.

Supported return shapes are:

* a `CallbackResponse<?>`, directly or as a `ResponseEntity` body
* a legacy CCD callback response POJO, directly or as a `ResponseEntity` body, with fields/getters such as `data`,
  `errors`, `warnings`, `state`, `security_classification`, `confirmation_header` and `confirmation_body`
* `void` or `null`, mainly for `submitted` callbacks that do not need confirmation content

For `submitted`, the returned object must also implement
[`SubmittedCallbackResponse`][scr] when confirmation header/body values are required, unless it is a legacy POJO using
`confirmation_header` and `confirmation_body`.

Example:

```java
@RestController
@RequestMapping("/tseAdmin")
class TseAdminController {

  @PostMapping("/aboutToSubmit")
  CallbackResponse<?> aboutToSubmit(@RequestBody CallbackRequest request,
                                    @RequestHeader("Authorization") String authToken) {
    // runs inside the submit transaction
  }

  @PostMapping("/submitted")
  CallbackResponse<?> submitted(@RequestHeader("Authorization") String authToken,
                                @RequestBody CallbackRequest request) {
    // runs after the submit transaction commits
  }
}
```

## Submit flow

The submit flow is:

1. `CaseSubmissionService` starts the submission transaction, retrieves the user and acquires the idempotency/case lock.
2. `JsonDefinitionSubmissionHandler` builds a CCD `CallbackRequest` from the decentralised case event.
3. `CallbackDispatchService` resolves the `aboutToSubmit` binding by case type, event ID and callback type.
4. If a handler exists, it is invoked through Spring MVC in-process. Any returned data, state, security classification,
   errors and warnings are copied back onto the event.
5. Validation errors from `about-to-submit` are returned to CCD and the transaction is rolled back.
6. If validation passes, case data, metadata, event history, projection and outbox records are persisted.
7. The transaction commits.
8. The deferred response supplier invokes the `submitted` callback, if one is bound locally, and applies confirmation
   header/body values to the CCD response.

If no binding exists for a callback phase, the phase is treated as a no-op.

## Transaction model

`about-to-submit` runs inside the same transaction as persistence.

This preserves CCD's atomicity guarantee: any service-owned writes performed by the controller commit or roll back with
the event.

`submitted` runs after the transaction has committed. Failures from `submitted` do not roll back committed case data.

## Error handling and retries

* `about-to-submit`: handler exceptions fail submission and roll back the transaction.
* `about-to-submit`: returned errors are converted to CCD validation errors and roll back the transaction.
* `about-to-submit`: a matched handler returning `null` fails the submission, because there is no response to persist.
* `submitted`: handler exceptions are retried after commit according to the event definition.
* `submitted`: when `RetriesTimeoutURLSubmittedEvent` contains more than one entry, the runtime attempts the callback
  three times in total; otherwise it attempts once.
* `submitted`: if all attempts fail, the exception is propagated to the caller after persistence has already committed.

## Compatibility impact

This supports ET and similar services with large manually organised callback surfaces while avoiding HTTP loopback.

Existing controller methods can remain in place. The key compatibility requirement is that the callback URL path in
the CCD JSON definition must be routable by local Spring MVC after normalisation.

Services using this mode still need a `CaseView` for each case type so the decentralised runtime can load and project
saved case data.

## Validation and testing

Covered behaviour includes:

* definition URL to Spring MVC dispatch
* duplicate binding failure
* missing binding no-op behaviour
* local/external callback URL filtering
* request body, auth header and query parameter handling
* typed request conversion from CCD callback JSON
* `ResponseEntity` return handling
* submitted retry and retry exhaustion behaviour
* ET-style controller and URL patterns

[rcr]: ../sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/ResolvedConfigRegistry.java
[dr]: ../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/impl/DefinitionRegistry.java
[css]: ../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/impl/CaseSubmissionService.java
[jdsh]: ../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/impl/JsonDefinitionSubmissionHandler.java
[cds]: ../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/impl/CallbackDispatchService.java
[scr]: ../sdk/decentralised-runtime/src/main/java/uk/gov/hmcts/ccd/sdk/SubmittedCallbackResponse.java
