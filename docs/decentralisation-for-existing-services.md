
# CCD Event submission

## As-is recap

```mermaid
graph LR
    CCDNode[CCD]
    ServiceNode[Service]
    CCDDatabase[(CCD database)]

    CCDNode -- AboutToSubmit --> ServiceNode
    CCDNode -- Submitted --> ServiceNode
    CCDNode -. Save case data .-> CCDDatabase
```

### AboutToSubmit callbacks

During event submission, CCD invokes the service's AboutToSubmit callback (if defined).

The callback is passed the complete case data as the payload, and the modified response is persisted by CCD verbatim.

### Submitted callbacks

Submitted callbacks (if defined) are invoked by CCD after CCD's database transaction commits.

## Decentralised

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
