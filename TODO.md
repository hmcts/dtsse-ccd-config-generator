# SDK callback dispatch todos

## Callback URL lookup

- Keep callback lookup path-based in `SpringHandlerLegacyCallbackResolver`.
- Do not require callback URLs to use a local host or scheme.
- The decentralised runtime is replacing what CCD used to perform as a remote HTTP callback with an in-process dispatch, so the URL host is not part of handler selection.

## Resolver behaviour

- Keep the existing legacy submit flow as the integration point.
- Preserve resolver precedence: SDK callback resolution first, JSON/Spring handler resolution after.
- Document that precedence close to the resolver chain if it is not already obvious from the code.

## Case projection

- Require decentralised services to define a `CaseView` for every known case type they own.
- Do not special-case JSON-defined case types with an implicit raw-data fallback.
- Let JSON-defined services provide minimal SDK case-type metadata so existing `CaseView` binding can be reused.
- That metadata must be inert: it should bind case type, case data, state enum, and view only; it must not generate or replace the imported CCD definition.
- Prefer an explicit "external/imported definition" flag over inferring this from missing events.
- Keep SDK case types without a matching `CaseView` as a fail-fast startup/runtime error.
- For ET-style services, the view can initially be a pass-through over their existing common-model case data.
