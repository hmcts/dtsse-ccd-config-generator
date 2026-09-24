# External events: design

Usage is in [decentralised-runtime.md](decentralised-runtime.md#external-events). This records why it is shaped the way it is.

## Problem

Services build their own frontends (citizen and judicial journeys) that drive events through CCD's API. The decentralised event API assumes EXUI: handlers take and return the whole case, and events carry pages, CaseEventToFields and an end button. A bespoke frontend wants none of that. It wants to fetch a view model when it starts, post a command when it submits, and get a clear yes or no back. Services were working around this by passing ad hoc envelopes through case fields.

## Decisions

| Decision | Why |
|---|---|
| Own builder, `externalEvent(...)`, with no page, field or end-button methods | EXUI configuration on these events is meaningless. A separate builder makes it impossible to write rather than something to ignore. |
| No CaseEventToFields rows. The generator skips them. | CCD accepts the payload field without them. This is verified end to end in `TestWithCCD`. |
| `ExternalEventId<Start, Submit>` typed id | States the contract once. The same constant configures the event, and tests and callers use it to drive the event. |
| Separate start and submit types | What a frontend needs to render is rarely what it sends back. |
| Payload travels as a JSON string in one SDK-defined TextArea field, `eventPayload`. The SDK grants the event's roles create and read on it. | Gets CCD's field validation and access checks without a complex-type definition per event. |
| The payload field is never persisted | An external event's submission doesn't write case data, and the runtime drops the field if another event posts it. |
| Handlers get the case reference, not the case | The case model is often not what the handler needs, and deserialising it costs time and ties the handler to it. Handlers load what they need. The start callback doesn't read the case at all. |
| Handlers take and return wrapper types (`ExternalStart`, `ExternalSubmit<I>`, responses) | Context can be added later without changing handler signatures. |
| Sealed responses: started or rejected, and accepted (optionally `movingTo(state)`) or rejected | Every outcome is explicit. There is no half-filled `SubmitResponse` to misread. |
| An unreadable payload is a rejection (422 with the reason in `callbackErrors`) | CCD turns a 4xx from the service's persistence endpoint into a 500. A rejection reaches the frontend with the reason. |
| Submit handler required; start handler optional | Every event must do something. Many events need nothing sent on start (`ExternalEventId.of(id, Submit.class)`). |
| Ids must start `ext:` | Marks the event for EXUI to hand off to the service's frontend rather than render it. |

Internally, `Event` holds one handler slot per phase plus the start and submit types. The existing `decentralisedEvent` API is unchanged.

## Alternatives rejected

- **An overload of `decentralisedEvent` that takes a DTO class.** It keeps pages, fields and the whole-case handler signature on an event that has no use for them.
- **A service-defined envelope field in the case model.** It leaks transport into the case definition and gets redone differently by each service.
- **Passing case data to the start handler.** The handler then depends on the case view deserialising, which isn't needed to build a view model.

## Out of scope and open questions

- Creating cases. External events act on existing cases.
- Mid-event callbacks and warnings (`ignore_warning`). Neither is supported yet.
- EXUI support for the `ext:` hand-off.
- Sharing the start and submit types with frontends, for example by generating TypeScript types.
- `movingTo(state)` is not checked against the states the event declares, as with decentralised events generally.
- The builder offers only what bespoke frontends need so far: no retries, `grantHistoryOnly` or access-control grants.
