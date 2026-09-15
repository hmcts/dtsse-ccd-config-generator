
# Decentralisation for existing services

Decentralisation changes both how CCD saves a case and how it reads one. Your service becomes part of the read path,
including when a user simply opens a case in XUI without triggering an event or callback.

## Reading case data

### As-is: CCD reads from its database

When a user opens a case, XUI requests it from CCD. CCD loads the case data from its own database and returns the
authorised case view to XUI. The service is not involved in this case-data read; it is called separately when an event
requires a callback.

```mermaid
sequenceDiagram
    participant XUI
    participant CCD
    participant CCDDB as CCD database
    participant Service

    XUI->>CCD: Open case
    CCD->>CCDDB: Load case data
    CCDDB-->>CCD: Case data
    CCD-->>XUI: Authorised case view
    Note over Service: Not involved in this read
```

### Decentralised: CCD reads through your service

XUI continues to request the case from CCD. For a decentralised case type, CCD delegates the case-data read to your
service's persistence API. The SDK loads the stored case and uses your `CaseView` to produce the case JSON that CCD
needs. CCD continues to enforce access rules before returning the case view to XUI.

```mermaid
sequenceDiagram
    participant XUI
    participant CCD
    participant Service as Service + SDK
    participant ServiceDB as Service database

    XUI->>CCD: Open case
    CCD->>Service: Read case data
    Service->>ServiceDB: Load stored case data
    ServiceDB-->>Service: Case data
    Note over Service: CaseView produces the CCD case JSON
    Service-->>CCD: Case JSON
    CCD-->>XUI: Authorised case view
```

An existing service can initially have its `CaseView` return the stored JSON unchanged. It can later assemble the same
CCD JSON shape from service-owned tables. See [Case views](./decentralised-runtime.md#case-views).

### Your service is now in the read path

Case reads now depend on your service, its database and its `CaseView` implementation, even when no callback is running.

* Service or database downtime can prevent users from opening cases, even if CCD is healthy.
* Slow database queries or additional dependencies in `CaseView` affect how quickly users can open a case. Keep views
  read-only and account for read traffic when sizing the service and its database connection pool.
* Monitor case-read errors and latency alongside callback and submission failures. Include existing-case reads in
  release checks alongside callback tests.

CCD's [routing configuration](./routing-configuration.md) determines which case types use this path. These diagrams
describe loading an individual case; search is covered [below](#case-search).

## CCD Event submission

### As-is recap

```mermaid
graph LR
    CCDNode[CCD]
    ServiceNode[Service]
    CCDDatabase[(CCD database)]

    CCDNode -- AboutToSubmit --> ServiceNode
    CCDNode -- Submitted --> ServiceNode
    CCDNode -. Save case data .-> CCDDatabase
```

#### AboutToSubmit callbacks

During event submission, CCD invokes the service's AboutToSubmit callback (if defined).

The callback is passed the complete case data as the payload, and the modified response is persisted by CCD verbatim.

#### Submitted callbacks

Submitted callbacks (if defined) are invoked by CCD after CCD's database transaction commits.

### Decentralised

```mermaid
graph LR
    CCDNode[CCD]
    ServiceNode[Service]
    ServiceDatabase[(Service database)]

    CCDNode -- Submit --> ServiceNode
    ServiceNode -->|Submitted callback| ServiceNode
    ServiceNode -->|AboutToSubmit| ServiceNode
    ServiceNode -. Save case data .-> ServiceDatabase
```

AboutToSubmit and Submitted callbacks are consolidated into a single 'Submit' operation.


Submit combines validation and persistence in a single step; services can validate the incoming event payload, rejecting it or accepting and persisting it.


## Case search

CCD Data Store still exposes a legacy case-search path backed by the central Postgres `case_data` table.

The following CCD endpoints cannot be used to search for cases owned by a decentralised service:

| Method and path |
| --- |
| [`GET /caseworkers/{uid}/jurisdictions/{jid}/case-types/{ctid}/cases`](https://github.com/hmcts/ccd-data-store-api/blob/master/src/main/java/uk/gov/hmcts/ccd/endpoint/std/CaseDetailsEndpoint.java#L431) |
| [`GET /citizens/{uid}/jurisdictions/{jid}/case-types/{ctid}/cases`](https://github.com/hmcts/ccd-data-store-api/blob/master/src/main/java/uk/gov/hmcts/ccd/endpoint/std/CaseDetailsEndpoint.java#L451) |
| [`GET /caseworkers/{uid}/jurisdictions/{jid}/case-types/{ctid}/cases/pagination_metadata`](https://github.com/hmcts/ccd-data-store-api/blob/master/src/main/java/uk/gov/hmcts/ccd/endpoint/std/CaseDetailsEndpoint.java#L475) |
| [`GET /citizens/{uid}/jurisdictions/{jid}/case-types/{ctid}/cases/pagination_metadata`](https://github.com/hmcts/ccd-data-store-api/blob/master/src/main/java/uk/gov/hmcts/ccd/endpoint/std/CaseDetailsEndpoint.java#L487) |
| [`GET /aggregated/caseworkers/{uid}/jurisdictions/{jid}/case-types/{ctid}/cases`](https://github.com/hmcts/ccd-data-store-api/blob/master/src/main/java/uk/gov/hmcts/ccd/endpoint/ui/QueryEndpoint.java#L156) |

Decentralised services should either use CCD's elasticsearch endpoints or, for system access, query their database directly.

## Callback emulation

To keep existing applications working without large-scale changes, the SDK provides callback emulation.

Under this model, case events proceed as follows:

* In a database transaction
  * AboutToSubmit callbacks are invoked (if defined)
  * The resultant case data is persisted
  * A case_event audit history is written
* Post-transaction commit
  * Submitted callbacks are invoked (if defined)

From the perspective of application development, callbacks therefore continue to function as before.

> **Note**
>
> CDAM attachment is separate from upload/storage. A document can be uploaded before it is attached to a case.
>
> CCD can attach documents it can see before it delegates a decentralised submit to the service. The SDK also mirrors
> CCD's central attach behaviour for documents newly introduced by an about-to-submit callback response, provided the
> callback returns the CDAM `document_hash` alongside the document reference.
>
> The invariant is: do not commit case data containing a new document reference until CDAM attach has succeeded for the
> same case id, case type and jurisdiction. The SDK only attaches documents that are new in the about-to-submit callback
> result; documents already present as event input are not re-attached by the SDK.
>
> When CDAM attach is enabled, the decentralised runtime requires the service application to provide an
> `AuthTokenGenerator` bean. The SDK does not create an S2S token generator itself; it uses the service's configured S2S
> identity for the `ServiceAuthorization` header on the CDAM attach call. The attach call itself uses the standard
> `ccd-case-document-am-client`; services do not need to implement their own CDAM attach client. That service identity
> still needs scoped CDAM `ATTACH` permission. See the
> [decentralised runtime transaction boundary](./decentralised-runtime.md#transaction-control), and the ET/SP Tribs
> permission example:
> [hmcts/ccd-case-document-am-api#776](https://github.com/hmcts/ccd-case-document-am-api/pull/776).
>
> A typical service configuration is:
>
> ```java
> @Bean
> AuthTokenGenerator serviceAuthTokenGenerator(
>     @Value("${idam.s2s-auth.secret}") String secret,
>     @Value("${idam.s2s-auth.microservice}") String microService,
>     ServiceAuthorisationApi serviceAuthorisationApi
> ) {
>   return AuthTokenGeneratorFactory.createDefaultGenerator(
>       secret,
>       microService,
>       serviceAuthorisationApi,
>       Duration.ofMinutes(5)
>   );
> }
> ```
