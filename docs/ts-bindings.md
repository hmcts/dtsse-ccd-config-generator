# TypeScript Bindings for DTO Events

## Overview

`generateCCDConfig` can generate TypeScript bindings for DTO-backed decentralised events in the same run that writes CCD
JSON config.

The generator now emits:

- `dto-types.ts`
- `event-contracts.ts`
- `index.ts`

It does not generate `client.ts`.

Frontend and integration-test consumers compose the generated bindings with the shared runtime package
`@hmcts/ccd-event-runtime`.

For the Java-side DTO event model, see [isolated-event-dtos.md](./isolated-event-dtos.md).

## What developers get

- generated DTO interfaces and enums
- a generated event manifest mapping event IDs to their DTO types
- a typed runtime client built from `createCcdClient(config, caseBindings)`

The runtime handles CCD transport marshalling so callers work with plain DTO-shaped objects.

- `start()` returns DTO-shaped data
- `validate(pageId, patch)` accepts DTO field names and returns DTO field names
- `submit(data)` accepts DTO field names

## Enable generation

Configure the SDK plugin in the service project that owns the CCD config:

```groovy
ccd {
  configDir = file('build/definitions')

  tsBindings {
    enabled = true
    outputDir.set(file('../my-frontend/src/main/generated/ccd'))
    moduleName = 'my-module'
  }
}
```

`tsBindings` options:

- `enabled` default `false`
- `outputDir` default `build/ts-bindings`
- `moduleName` default `ccd-bindings`

## Build output

Run:

```bash
./gradlew generateCCDConfig
```

When `ccd.tsBindings.enabled=true`, the same task produces:

- CCD JSON definition output under `ccd.configDir`
- TypeScript bindings output under `ccd.tsBindings.outputDir`

No separate TypeScript generation task is required.

## Generated files

Per case type, the generator writes:

- `dto-types.ts`
- `event-contracts.ts`
- `index.ts`

`event-contracts.ts` contains:

- `EventDtoMap` — maps event IDs to their DTO types
- `caseBindings` — runtime configuration for the typed client

Only DTO-backed decentralised events are included in these bindings in this change. Legacy non-DTO decentralised
events are omitted.

## Runtime package

Install or reference the shared runtime package:

```json
{
  "dependencies": {
    "@hmcts/ccd-event-runtime": "file:../../sdk/ccd-event-runtime"
  }
}
```

The runtime exports:

- `createCcdClient`
- `defineCaseBindings`
- `CcdCaseBindings`
- `CcdClientConfig`
- `CcdTransport`

## Example

```ts
import Axios from 'axios';
import { createCcdClient, type CcdClientConfig } from '@hmcts/ccd-event-runtime';
import { caseBindings, type CreateClaimData } from '../generated/ccd/MY_CASE_TYPE';

const api = Axios.create({ baseURL: process.env.DATA_STORE_URL_BASE });

const config: CcdClientConfig = {
  baseUrl: process.env.DATA_STORE_URL_BASE || '',
  callbackBaseUrl: process.env.CCD_CALLBACK_BASE_URL,
  caseTypeId: 'MY_CASE_TYPE',
  getAuthHeaders: () => ({
    Authorization: `Bearer ${process.env.BEARER_TOKEN}`,
    ServiceAuthorization: `Bearer ${process.env.SERVICE_AUTH_TOKEN}`,
    experimental: 'experimental',
    'Content-Type': 'application/json',
    Accept: '*/*',
  }),
  transport: {
    get: async (url, headers) => (await api.get(url, { headers })).data,
    post: async (url, data, headers) => (await api.post(url, data, { headers })).data,
  },
};

const client = createCcdClient(config, caseBindings);
const flow = await client.event('createPossessionClaim').start();

const data: CreateClaimData = flow.data;
data.feeAmount = '£404';

const midEvent = await flow.validate('enterPropertyAddress', {
  propertyAddress: data.propertyAddress,
});

if (midEvent.errors.length > 0) {
  throw new Error(midEvent.errors.join('; '));
}

await flow.submit(midEvent.data);
```

## Mid-event callbacks

`validate(pageId, patch)` uses the generated event contract for the selected event.

You pass:

- a page ID from the generated `pages` union
- a partial DTO payload containing changed fields

You get back:

- typed DTO data for the event
- `errors` and `warnings` returned by CCD callback validation

Serialisation to and from the CCD payload field is handled inside `@hmcts/ccd-event-runtime` — your code works with
plain DTO-shaped objects.
