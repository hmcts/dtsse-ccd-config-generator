import assert from "node:assert/strict";

import {
  createCcdClient,
  defineCaseBindings,
  toPrefixStem,
  type CcdCaseBindings,
  type CcdTransport,
} from "./index";

interface EventDtoMap {
  "create-widget": {
    propertyAddress?: string;
    note?: string;
  };
}

const bindings = defineCaseBindings<EventDtoMap>({
  caseTypeId: "TEST_CASE",
  events: {
    "create-widget": {
      fieldNamespace: "claim.create",
      pages: ["details"],
    },
  },
} satisfies CcdCaseBindings<EventDtoMap>);

async function run(): Promise<void> {
  await startAndSubmitRoundTrip();
  await validateRoundTrip();
  await mismatchedCaseTypeFails();
  namespaceConversionMatchesJava();
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
            claimCreateNote: "[start]",
            claimCreatePropertyAddress: "10 Example Street",
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
      claimCreateNote: "updated",
      claimCreatePropertyAddress: "12 Example Street",
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
          case_data: {
            claimCreateNote: "before",
          },
        },
      };
    },
    async post(url, body) {
      requests.push({ method: "POST", url, body });
      return {
        data: {
          claimCreateNote: "after",
          claimCreatePropertyAddress: "99 Example Road",
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
  const validation = await flow.validate("details", { propertyAddress: "99 Example Road" });

  assert.deepEqual(validation, {
    data: {
      note: "after",
      propertyAddress: "99 Example Road",
    },
    errors: ["warn"],
    warnings: ["heads-up"],
  });
  assert.equal(requests[1]?.url, "http://service/callbacks/mid-event?page=details&eventId=create-widget");
  assert.deepEqual(requests[1]?.body, {
    case_details: {
      case_type_id: "TEST_CASE",
      case_data: {
        claimCreateNote: "before",
        claimCreatePropertyAddress: "99 Example Road",
      },
    },
    case_details_before: {
      case_type_id: "TEST_CASE",
      case_data: {
        claimCreateNote: "before",
      },
    },
    event_id: "create-widget",
    ignore_warning: false,
  });
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

function namespaceConversionMatchesJava(): void {
  assert.equal(toPrefixStem("claim.create"), "claimCreate");
  assert.equal(toPrefixStem("citizen.application.update"), "citizenApplicationUpdate");
}

void run();
