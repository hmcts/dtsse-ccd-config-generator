# Routing Configuration

CCD Data Store uses a Spring-parsed multimap to discover which case types are decentralised and where their service is hosted. This is configured via environment variables with the naming convention:

```
CCD_DECENTRALISED_CASE-TYPE-SERVICE-URLS_<CaseType>: <service-url>
```

For example, to decentralise the `ET_EnglandWales` case type:

```yaml
CCD_DECENTRALISED_CASE-TYPE-SERVICE-URLS_ET_EnglandWales: https://et-cos-preview.service.core-compute-preview.internal
```

Spring automatically parses the suffix after `CASE-TYPE-SERVICE-URLS_` as the map key (the case type ID) and the value as the service URL. Multiple case types can be decentralised independently by adding additional env vars:

```yaml
CCD_DECENTRALISED_CASE-TYPE-SERVICE-URLS_ET_EnglandWales: https://et-cos.service.internal
CCD_DECENTRALISED_CASE-TYPE-SERVICE-URLS_ET_Scotland: https://et-cos.service.internal
CCD_DECENTRALISED_CASE-TYPE-SERVICE-URLS_CriminalInjuriesCompensation: https://cica-service.internal
```

These env vars are set on the **CCD Data Store API** deployment (not your service). In practice this means:

- **Preview / PR environments:** Add the env var to the CCD Data Store section of your service's preview Helm chart.
- **Higher environments (AAT, prod, etc.):** Add the env var via [Flux configuration](https://github.com/hmcts/cnp-flux-config/blob/master/apps/ccd/ccd-data-store-api/aat.yaml#L49) for the CCD Data Store API in the relevant environment.

When CCD Data Store receives a request for a case type that appears in this map, it delegates persistence and read operations to the configured service URL instead of using its own database. Case types not present in the map continue to use centralised CCD persistence.

## Parameterised URLs for preview environments

Routing supports prefix matching with a `%s` template placeholder in the URL. This lets a single configuration entry on the AAT CCD Data Store route to any preview PR, removing the need to spin up a dedicated CCD instance per PR and saving preview resources.

For example, configuring the AAT CCD Data Store with:

```yaml
CCD_DECENTRALISED_CASE-TYPE-SERVICE-URLS_PCS-PR-: "https://pcs-api-pr-%s.preview.platform.hmcts.net"
```

When CCD receives a request for case type `PCS-PR-123`, it matches the prefix `PCS-PR-` and substitutes the suffix `123` into the template, routing to `https://pcs-api-pr-123.preview.platform.hmcts.net`.

The matching rules are:
- Keys are matched as **prefixes** against the case type ID (case-insensitive).
- If the URL contains `%s`, it is replaced with the case type suffix (the part after the prefix).
- If multiple prefixes match, the longest prefix wins. Ambiguous equal-length matches are rejected.

The routing logic lives in [`PersistenceStrategyResolver`](https://github.com/hmcts/ccd-data-store-api/blob/master/src/main/java/uk/gov/hmcts/ccd/domain/service/common/PersistenceStrategyResolver.java).
