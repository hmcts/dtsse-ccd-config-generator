# TypeScript Bindings for CCD Events

## Overview

`generateCCDConfig` can optionally generate TypeScript bindings in the same run that writes CCD JSON config.

This feature is for frontend and integration-test developers who want a typed CCD event client with plain DTO-shaped
objects.

This is not specific to isolated event DTOs and should be useful for any service that wants a typed CCD integration.
When isolated event DTOs are used, the same bindings also hide event field prefixing/unprefixing automatically.

For isolated event DTO behavior in Java and generated config, see
[isolated-event-dtos.md](./isolated-event-dtos.md).

## What developers get

- generated DTO TypeScript interfaces
- typed event entry points under `client.events.<eventId>`
- typed event flow for `start()`, `validate()` (mid-event), and `submit()`
- automatic transport marshalling so developers use plain DTO field names

`start()` returns unprefixed DTO fields.
`validate()` accepts and returns unprefixed DTO fields while calling CCD mid-event callbacks.
`submit()` accepts unprefixed DTO fields and prefixes them for CCD transport behind the scenes.

## Enable generation

Configure the SDK plugin in the service project that owns CCD config:

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

- `enabled` (default `false`)
- `outputDir` (default `build/ts-bindings`)
- `moduleName` (default `ccd-bindings`)

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

Per case type:

- `dto-types.ts`
- `event-contracts.ts`
- `client.ts`
- `index.ts`

## Example

```ts
import Axios from 'axios';
import { GeneratedCcdClient, type CcdClientConfig } from '../generated/ccd/MY_CASE_TYPE';

const api = Axios.create({ baseURL: process.env.DATA_STORE_URL_BASE });

const config: CcdClientConfig = {
  baseUrl: process.env.DATA_STORE_URL_BASE || '',
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

const client = new GeneratedCcdClient(config);
const flow = await client.events.createPossessionClaim.start();

// Typed DTO object. If about-to-start is configured for this event, it may already contain pre-populated values.
const createClaimData = flow.data;
createClaimData.feeAmount = '£404';

// Mid-event callback for the current page.
// Returned data is typed and already unmarshalled back to plain DTO fields.
const midEventResult = await flow.validate('enterPropertyAddress', {
  propertyAddress: createClaimData.propertyAddress,
});

if (midEventResult.errors.length > 0) {
  throw new Error(midEventResult.errors.join('; '));
}

await flow.submit(midEventResult.data);
```

## Mid-event callbacks

Mid-event callbacks use the same event contracts as `start()` and `submit()`.

You pass:

- `pageId` for the page being validated/saved
- a partial DTO payload containing changed fields

You get back:

- typed DTO data for the event (including any callback mutations)
- `errors` and `warnings` from CCD callback validation

All prefixing/unprefixing is handled inside the generated client.
