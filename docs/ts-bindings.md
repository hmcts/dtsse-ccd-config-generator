# TypeScript Bindings for CCD Events

## Overview

`generateCCDConfig` can now generate TypeScript event bindings as an optional output
in the same run that writes JSON config.

This is aimed at frontend developers who need a typed way to call CCD event
`start` and `submit` in a type safe way without handling prefixed field ids manually.

## Developers get

Developers work with plain DTO-shaped objects.

- generated DTO TypeScript interfaces
- typed event entry points under `client.events.<eventId>`
- typed `start`/`submit` event flow
- automatic prefix/unprefix marshalling behind the client API


## Enable in Gradle

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

- `enabled` (default `false`)
- `outputDir` (default `build/ts-bindings`)
- `moduleName` (default `ccd-bindings`)

## Generate outputs

Run the normal task:

```bash
./gradlew generateCCDConfig
```

When `ccd.tsBindings.enabled=true`, this single task produces both:

- JSON CCD definition output under `ccd.configDir`
- TypeScript bindings output under `ccd.tsBindings.outputDir`

No separate TS generation task is required.

## Generated files

Per case type, bindings include:

- `dto-types.ts`
- `event-contracts.ts`
- `client.ts`
- `index.ts`

## Example usage

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
flow.data.feeAmount = '£404';
await flow.submit(flow.data);
```
