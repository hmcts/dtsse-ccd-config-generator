export interface CcdTransport {
  get(url: string, headers: Record<string, string>): Promise<unknown>;
  post(url: string, data: unknown, headers: Record<string, string>): Promise<unknown>;
}

type MaybePromise<T> = T | Promise<T>;
type StringKeys<T> = Extract<keyof T, string>;
type BindingEvents<T extends object> = {
  [K in StringKeys<T>]: {
    fieldNamespace: string;
    fields: readonly StringKeys<T[K] & object>[];
    pages: readonly string[];
  };
};
type BoundDtoMap<TBindings extends CcdCaseBindings<any>> =
  TBindings extends CcdCaseBindings<infer TMap> ? TMap : never;
type BoundEventId<TBindings extends CcdCaseBindings<any>> = Extract<keyof TBindings["events"], string>;
type BoundEventData<
  TBindings extends CcdCaseBindings<any>,
  TEventId extends BoundEventId<TBindings>,
> = BoundDtoMap<TBindings>[TEventId & keyof BoundDtoMap<TBindings>];
type BoundPageId<
  TBindings extends CcdCaseBindings<any>,
  TEventId extends BoundEventId<TBindings>,
> = TBindings["events"][TEventId]["pages"][number];

export interface CcdCaseBindings<T extends object> {
  caseTypeId: string;
  events: BindingEvents<T>;
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
    pageId: BoundPageId<TBindings, TEventId>,
    patch: Partial<BoundEventData<TBindings, TEventId>>,
    caseId?: string | number
  ): Promise<CcdValidationResult<BoundEventData<TBindings, TEventId>>>;
  submit(data: BoundEventData<TBindings, TEventId>, caseId?: string | number): Promise<CcdSubmitResult>;
}

interface EventTriggerResponse {
  token?: string;
  case_details?: {
    case_data?: Record<string, unknown>;
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
}

export function defineCaseBindings<T extends object>(
  bindings: CcdCaseBindings<T>
): CcdCaseBindings<T> {
  return bindings;
}

export function createCcdClient<TBindings extends CcdCaseBindings<any>>(config: CcdClientConfig, bindings: TBindings) {
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

async function startEvent<TBindings extends CcdCaseBindings<any>, TEventId extends BoundEventId<TBindings>>(
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
    bindings,
    eventId,
    triggerResponse.case_details?.case_data ?? {}
  );

  return {
    eventId,
    get data() {
      return currentData;
    },
    async validate(pageId, patch, validateCaseId) {
      const validationHeaders = await config.getAuthHeaders();
      const mergedData = { ...toRecord(currentData), ...toRecord(patch) } as BoundEventData<TBindings, TEventId>;
      const callbackBaseUrl = config.callbackBaseUrl ?? config.baseUrl;
      const callbackBody = {
        case_details: buildCallbackCaseDetails(bindings.caseTypeId, marshal(bindings, eventId, mergedData), validateCaseId ?? caseId),
        case_details_before: buildCallbackCaseDetails(bindings.caseTypeId, marshal(bindings, eventId, currentData), validateCaseId ?? caseId),
        event_id: eventId,
        ignore_warning: false,
      };
      const response = (await config.transport.post(
        buildMidEventUrl(callbackBaseUrl, eventId, pageId),
        callbackBody,
        validationHeaders
      )) as CallbackResponse;
      currentData = unmarshal(bindings, eventId, response.data ?? {});
      return {
        data: currentData,
        errors: response.errors ?? [],
        warnings: response.warnings ?? [],
      } satisfies CcdValidationResult<BoundEventData<TBindings, TEventId>>;
    },
    async submit(data, submitCaseId) {
      const submitHeaders = await config.getAuthHeaders();
      currentData = data;
      const payload = {
        data: marshal(bindings, eventId, data),
        event: { id: eventId },
        event_token: eventToken,
        ignore_warning: false,
      };
      return (await config.transport.post(
        buildEventSubmitUrl(config.baseUrl, submitCaseId ?? caseId, bindings.caseTypeId),
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

function buildMidEventUrl(baseUrl: string, eventId: string, pageId: string): string {
  return `${baseUrl}/callbacks/mid-event?page=${encodeURIComponent(pageId)}&eventId=${encodeURIComponent(eventId)}`;
}

function buildCallbackCaseDetails(
  caseTypeId: string,
  caseData: Record<string, unknown>,
  caseId?: string | number
): CallbackCaseDetails {
  return {
    ...(caseId !== undefined ? { id: caseId } : {}),
    case_type_id: caseTypeId,
    case_data: caseData,
  };
}

function marshal<TBindings extends CcdCaseBindings<any>, TEventId extends BoundEventId<TBindings>>(
  bindings: TBindings,
  eventId: TEventId,
  data: BoundEventData<TBindings, TEventId>
): Record<string, unknown> {
  const event = bindings.events[eventId as keyof typeof bindings.events] as TBindings["events"][TEventId];
  const source = toRecord(data);
  const marshalled: Record<string, unknown> = {};
  const prefixStem = toPrefixStem(event.fieldNamespace);
  for (const field of event.fields) {
    if (Object.prototype.hasOwnProperty.call(source, field)) {
      marshalled[prefixStem + capitalise(field)] = source[field];
    }
  }
  return marshalled;
}

function unmarshal<TBindings extends CcdCaseBindings<any>, TEventId extends BoundEventId<TBindings>>(
  bindings: TBindings,
  eventId: TEventId,
  ccdData: Record<string, unknown>
): BoundEventData<TBindings, TEventId> {
  const event = bindings.events[eventId as keyof typeof bindings.events] as TBindings["events"][TEventId];
  const prefixStem = toPrefixStem(event.fieldNamespace);
  const unmarshalled: Record<string, unknown> = {};
  for (const field of event.fields) {
    const prefixedField = prefixStem + capitalise(field);
    if (Object.prototype.hasOwnProperty.call(ccdData, prefixedField)) {
      unmarshalled[field] = ccdData[prefixedField];
    }
  }
  return unmarshalled as BoundEventData<TBindings, TEventId>;
}

export function toPrefixStem(fieldNamespace: string): string {
  const segments = fieldNamespace.split(".");
  return segments
    .map((segment, index) => (index === 0 ? segment : capitalise(segment)))
    .join("");
}

function capitalise(value: string): string {
  return value.length === 0 ? value : value[0].toUpperCase() + value.slice(1);
}

function toRecord(value: unknown): Record<string, unknown> {
  return (value ?? {}) as Record<string, unknown>;
}
