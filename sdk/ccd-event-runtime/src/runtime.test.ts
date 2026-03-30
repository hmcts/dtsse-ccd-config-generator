import assert from "node:assert/strict";

import {
  createCcdClient,
  defineCaseBindings,
  type CcdCaseBindings,
  type CcdTransport,
} from "./index";

interface EventDtoMap {
  "create-widget": {
    propertyAddress?: string;
    note?: string;
  };
}

const bindings = defineCaseBindings<EventDtoMap>()({
  caseTypeId: "TEST_CASE",
  events: {
    "create-widget": {},
  },
} as const satisfies CcdCaseBindings<EventDtoMap>);

async function run(): Promise<void> {
  await startAndSubmitRoundTrip();
  await validateRoundTrip();
  await validateWithoutDataKeepsMergedState();
  await mismatchedCaseTypeFails();
}

async function startAndSubmitRoundTrip(): Promise<void> {
  const requests: Array<{ method: string; url: string; body?: unknown }> = [];
  const transport: CcdTransport = {
    async get(url) {
      requests.push({ method: "GET", url });
      return {
        token: "event-token",
        case_details: {
          case_data: {
            ccdSdkDtoEventData: JSON.stringify({
              note: "[start]",
              propertyAddress: "10 Example Street",
            }),
          },
        },
      };
    },
    async post(url, body) {
      requests.push({ method: "POST", url, body });
      return { id: "12345" };
    },
  };

  const client = createCcdClient(
    {
      baseUrl: "http://ccd",
      getAuthHeaders: () => ({ Authorization: "Bearer token" }),
      transport,
    },
    bindings
  );

  const flow = await client.event("create-widget").start("12345");
  assert.equal(flow.data.note, "[start]");
  assert.equal(flow.data.propertyAddress, "10 Example Street");

  const result = await flow.submit({
    note: "updated",
    propertyAddress: "12 Example Street",
  });

  assert.deepEqual(result, { id: "12345" });
  assert.equal(requests[0]?.url, "http://ccd/cases/12345/event-triggers/create-widget?ignore-warning=false");
  assert.equal(requests[1]?.url, "http://ccd/cases/12345/events");
  assert.deepEqual(requests[1]?.body, {
    data: {
      ccdSdkDtoEventData: JSON.stringify({
        note: "updated",
        propertyAddress: "12 Example Street",
      }),
    },
    event: { id: "create-widget" },
    event_token: "event-token",
    ignore_warning: false,
  });
}

async function validateRoundTrip(): Promise<void> {
  const requests: Array<{ method: string; url: string; body?: unknown }> = [];
  const transport: CcdTransport = {
    async get(url) {
      requests.push({ method: "GET", url });
      return {
        token: "event-token",
        case_details: {
          state: "Draft",
          case_data: {
            ccdSdkDtoEventData: JSON.stringify({
              note: "before",
            }),
          },
        },
      };
    },
    async post(url, body) {
      requests.push({ method: "POST", url, body });
      return {
        data: {
          ccdSdkDtoEventData: JSON.stringify({
            note: "after",
            propertyAddress: "99 Example Road",
          }),
        },
        errors: ["warn"],
        warnings: ["heads-up"],
      };
    },
  };

  const client = createCcdClient(
    {
      baseUrl: "http://ccd",
      callbackBaseUrl: "http://service",
      getAuthHeaders: async () => ({ Authorization: "Bearer token" }),
      transport,
    },
    bindings
  );

  const flow = await client.event("create-widget").start();
  const validation = await flow.validate({ propertyAddress: "99 Example Road" });

  assert.deepEqual(validation, {
    data: {
      note: "after",
      propertyAddress: "99 Example Road",
    },
    errors: ["warn"],
    warnings: ["heads-up"],
  });
  assert.equal(requests[1]?.url, "http://service/callbacks/mid-event?eventId=create-widget");
  assert.deepEqual(requests[1]?.body, {
    case_details: {
      state: "Draft",
      case_type_id: "TEST_CASE",
      case_data: {
        ccdSdkDtoEventData: JSON.stringify({
          note: "before",
          propertyAddress: "99 Example Road",
        }),
      },
    },
    case_details_before: {
      state: "Draft",
      case_type_id: "TEST_CASE",
      case_data: {
        ccdSdkDtoEventData: JSON.stringify({
          note: "before",
        }),
      },
    },
    event_id: "create-widget",
    ignore_warning: false,
  });
}

async function validateWithoutDataKeepsMergedState(): Promise<void> {
  const transport: CcdTransport = {
    async get() {
      return {
        token: "event-token",
        case_details: {
          case_data: {
            ccdSdkDtoEventData: JSON.stringify({
              note: "before",
            }),
          },
        },
      };
    },
    async post() {
      return {
        errors: ["still-invalid"],
      };
    },
  };

  const client = createCcdClient(
    {
      baseUrl: "http://ccd",
      callbackBaseUrl: "http://service",
      getAuthHeaders: async () => ({ Authorization: "Bearer token" }),
      transport,
    },
    bindings
  );

  const flow = await client.event("create-widget").start();
  const validation = await flow.validate({ propertyAddress: "99 Example Road" });

  assert.deepEqual(validation, {
    data: {
      note: "before",
      propertyAddress: "99 Example Road",
    },
    errors: ["still-invalid"],
    warnings: [],
  });
  assert.deepEqual(flow.data, validation.data);
}

async function mismatchedCaseTypeFails(): Promise<void> {
  assert.throws(
    () =>
      createCcdClient(
        {
          baseUrl: "http://ccd",
          caseTypeId: "WRONG_CASE",
          getAuthHeaders: () => ({}),
          transport: {
            get: async () => ({}),
            post: async () => ({}),
          },
        },
        bindings
      ),
    /does not match generated bindings/
  );
}

void run();
