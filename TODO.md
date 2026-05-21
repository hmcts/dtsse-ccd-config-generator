# SDK callback dispatch todos

## Callback URL lookup

- Keep callback lookup path-based in `SpringHandlerLegacyCallbackDispatcher`.
- Do not require callback URLs to use a local host or scheme.
- The decentralised runtime is replacing what CCD used to perform as a remote HTTP callback with an in-process dispatch, so the URL host is not part of handler selection.

## Response handling

- Fix `LegacyCallbackResponseAdapter` so non-2xx `ResponseEntity` results are not treated as empty successful callback responses.
- Add focused behaviour coverage for non-2xx `ResponseEntity` callback results.

## Dispatcher behaviour

- Keep the existing legacy submit flow as the integration point.
- Preserve dispatcher precedence: SDK callback dispatch first, JSON/Spring handler dispatch after.
- Document that precedence close to the dispatcher chain if it is not already obvious from the code.

## Case projection

- Require decentralised services to define a `CaseView` for every known case type they own.
- Do not special-case JSON-defined case types with an implicit raw-data fallback.
- Let JSON-defined services provide minimal SDK case-type metadata so existing `CaseView` binding can be reused.
- That metadata must be inert: it should bind case type, case data, state enum, and view only; it must not generate or replace the imported CCD definition.
- Prefer an explicit "external/imported definition" flag over inferring this from missing events.
- Keep SDK case types without a matching `CaseView` as a fail-fast startup/runtime error.
- For ET-style services, the view can initially be a pass-through over their existing common-model case data.

## Naming cleanup

- Consider whether `LegacyCallbackDispatcher` should be renamed or documented as a resolver, since it resolves callback handlers rather than directly performing the full legacy submit.
- Only do this if it reduces confusion without increasing churn.
