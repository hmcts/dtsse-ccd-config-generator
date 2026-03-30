export interface CcdTransport {
  get(url: string, headers: Record<string, string>): Promise<unknown>;
  post(url: string, data: unknown, headers: Record<string, string>): Promise<unknown>;
}

type MaybePromise<T> = T | Promise<T>;
type StringKeys<T> = Extract<keyof T, string>;
type BindingEvents<T extends object> = {
  [K in StringKeys<T>]: Record<string, never>;
};
type BoundDtoMap<TBindings extends CcdCaseBindings<any>> = NonNullable<TBindings["__dtoMap"]>;
type BoundEventId<TBindings extends CcdCaseBindings<any>> =
  Extract<keyof BoundDtoMap<TBindings>, string> & Extract<keyof TBindings["events"], string>;
type BoundEventData<
  TBindings extends CcdCaseBindings<any>,
  TEventId extends BoundEventId<TBindings>,
> = BoundDtoMap<TBindings>[TEventId];

export interface CcdCaseBindings<T extends object> {
  caseTypeId: string;
  events: BindingEvents<T>;
  readonly __dtoMap?: T;
}

export interface CcdClientConfig {
  baseUrl: string;
  callbackBaseUrl?: string;
  caseTypeId?: string;
  getAuthHeaders: () => MaybePromise<Record<string, string>>;
  transport: CcdTransport;
}

export interface CcdValidationResult<T> {
  data: T;
  errors: string[];
  warnings: string[];
}

export interface CcdSubmitResult {
  [key: string]: unknown;
}

export interface CcdEventFlow<
  TBindings extends CcdCaseBindings<any>,
  TEventId extends BoundEventId<TBindings>,
> {
  readonly eventId: TEventId;
  readonly data: BoundEventData<TBindings, TEventId>;
  validate(
    patch: Partial<BoundEventData<TBindings, TEventId>>
  ): Promise<CcdValidationResult<BoundEventData<TBindings, TEventId>>>;
  submit(data: BoundEventData<TBindings, TEventId>): Promise<CcdSubmitResult>;
}

interface EventTriggerResponse {
  token?: string;
  case_details?: {
    case_data?: Record<string, unknown>;
    state?: string;
  };
}

interface CallbackResponse {
  data?: Record<string, unknown>;
  errors?: string[];
  warnings?: string[];
}

interface CallbackCaseDetails {
  id?: string | number;
  case_type_id: string;
  case_data: Record<string, unknown>;
  state?: string;
}

const PAYLOAD_FIELD = "payload";

export function defineCaseBindings<T extends object>() {
  return function <const TBindings extends CcdCaseBindings<T>>(
    bindings: TBindings
  ): TBindings & CcdCaseBindings<T> {
    return bindings as TBindings & CcdCaseBindings<T>;
  };
}

export function createCcdClient<const TBindings extends CcdCaseBindings<any>>(
  config: CcdClientConfig,
  bindings: TBindings
) {
  if (config.caseTypeId && config.caseTypeId !== bindings.caseTypeId) {
    throw new Error(
      `config.caseTypeId ${config.caseTypeId} does not match generated bindings ${bindings.caseTypeId}`
    );
  }

  const resolvedConfig = {
    ...config,
    caseTypeId: bindings.caseTypeId,
  };

  return {
    event<TEventId extends BoundEventId<TBindings>>(eventId: TEventId) {
      return {
        start(caseId?: string | number): Promise<CcdEventFlow<TBindings, TEventId>> {
          return startEvent(resolvedConfig, bindings, eventId, caseId);
        },
      };
    },
  };
}

async function startEvent<
  TBindings extends CcdCaseBindings<any>,
  TEventId extends BoundEventId<TBindings>,
>(
  config: Required<Pick<CcdClientConfig, "baseUrl" | "transport" | "getAuthHeaders">> & Pick<CcdClientConfig, "callbackBaseUrl" | "caseTypeId">,
  bindings: TBindings,
  eventId: TEventId,
  caseId?: string | number
): Promise<CcdEventFlow<TBindings, TEventId>> {
  const headers = await config.getAuthHeaders();
  const triggerResponse = (await config.transport.get(
    buildEventTriggerUrl(config.baseUrl, eventId, caseId, bindings.caseTypeId),
    headers
  )) as EventTriggerResponse;
  const eventToken = triggerResponse.token;
  if (!eventToken) {
    throw new Error(`Missing event token for ${String(eventId)}`);
  }

  let currentData: BoundEventData<TBindings, TEventId> = unmarshal(
    triggerResponse.case_details?.case_data ?? {}
  );
  const currentState = triggerResponse.case_details?.state;

  return {
    eventId,
    get data() {
      return currentData;
    },
    async validate(patch) {
      const validationHeaders = await config.getAuthHeaders();
      const mergedData = {
        ...toRecord(currentData),
        ...toRecord(patch),
      } as BoundEventData<TBindings, TEventId>;
      const callbackBaseUrl = config.callbackBaseUrl ?? config.baseUrl;
      const callbackBody = {
        case_details: buildCallbackCaseDetails(
          bindings.caseTypeId,
          marshal(mergedData),
          caseId,
          currentState
        ),
        case_details_before: buildCallbackCaseDetails(
          bindings.caseTypeId,
          marshal(currentData),
          caseId,
          currentState
        ),
        event_id: eventId,
        ignore_warning: false,
      };
      const response = (await config.transport.post(
        buildMidEventUrl(callbackBaseUrl, eventId),
        callbackBody,
        validationHeaders
      )) as CallbackResponse;
      currentData = response.data !== undefined
        ? unmarshal(response.data)
        : mergedData;
      return {
        data: currentData,
        errors: response.errors ?? [],
        warnings: response.warnings ?? [],
      } satisfies CcdValidationResult<BoundEventData<TBindings, TEventId>>;
    },
    async submit(data) {
      const submitHeaders = await config.getAuthHeaders();
      currentData = data;
      const payload = {
        data: marshal(data),
        event: { id: eventId },
        event_token: eventToken,
        ignore_warning: false,
      };
      return (await config.transport.post(
        buildEventSubmitUrl(config.baseUrl, caseId, bindings.caseTypeId),
        payload,
        submitHeaders
      )) as CcdSubmitResult;
    },
  };
}

function buildEventTriggerUrl(
  baseUrl: string,
  eventId: string,
  caseId: string | number | undefined,
  caseTypeId: string
): string {
  if (caseId !== undefined) {
    return `${baseUrl}/cases/${caseId}/event-triggers/${eventId}?ignore-warning=false`;
  }
  return `${baseUrl}/case-types/${caseTypeId}/event-triggers/${eventId}`;
}

function buildEventSubmitUrl(baseUrl: string, caseId: string | number | undefined, caseTypeId: string): string {
  if (caseId !== undefined) {
    return `${baseUrl}/cases/${caseId}/events`;
  }
  return `${baseUrl}/case-types/${caseTypeId}/cases`;
}

function buildMidEventUrl(baseUrl: string, eventId: string): string {
  return `${baseUrl}/callbacks/mid-event?eventId=${encodeURIComponent(eventId)}`;
}

function buildCallbackCaseDetails(
  caseTypeId: string,
  caseData: Record<string, unknown>,
  caseId?: string | number,
  state?: string
): CallbackCaseDetails {
  return {
    ...(caseId !== undefined ? { id: caseId } : {}),
    case_type_id: caseTypeId,
    case_data: caseData,
    ...(state !== undefined ? { state } : {}),
  };
}

function marshal<
  TBindings extends CcdCaseBindings<any>,
  TEventId extends BoundEventId<TBindings>,
>(
  data: BoundEventData<TBindings, TEventId>
): Record<string, unknown> {
  return {
    [PAYLOAD_FIELD]: JSON.stringify(toRecord(data)),
  };
}

function unmarshal<
  TBindings extends CcdCaseBindings<any>,
  TEventId extends BoundEventId<TBindings>,
>(
  ccdData: Record<string, unknown>
): BoundEventData<TBindings, TEventId> {
  const payloadValue = ccdData[PAYLOAD_FIELD];
  if (payloadValue === undefined || payloadValue === null) {
    return {} as BoundEventData<TBindings, TEventId>;
  }
  if (typeof payloadValue === "string") {
    return JSON.parse(payloadValue) as BoundEventData<TBindings, TEventId>;
  }
  return payloadValue as BoundEventData<TBindings, TEventId>;
}

function toRecord(value: unknown): Record<string, unknown> {
  return (value ?? {}) as Record<string, unknown>;
}
