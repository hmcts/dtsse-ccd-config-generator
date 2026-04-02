import { createCcdClient, defineCaseBindings, type CcdCaseBindings } from "./index";

interface EventDtoMap {
  "create-widget": {
    note?: string;
  };
}

const bindings = defineCaseBindings<EventDtoMap>()({
  caseTypeId: "TEST_CASE",
  events: {
    "create-widget": {},
  },
} as const satisfies CcdCaseBindings<EventDtoMap>);

const client = createCcdClient(
  {
    baseUrl: "http://ccd",
    getAuthHeaders: async () => ({}),
    transport: {
      get: async () => ({ token: "token" }),
      post: async () => ({}),
    },
  },
  bindings
);

const validFlowPromise = client.event("create-widget").start();

// @ts-expect-error invalid event id should fail at compile time
void client.event("wrong-event");

async function runTypeChecks(): Promise<void> {
  const flow = await validFlowPromise;
  await flow.validate({ note: "ok" });

  // @ts-expect-error invalid field should fail at compile time
  await flow.validate({ bogusField: "nope" });
}

void runTypeChecks();
