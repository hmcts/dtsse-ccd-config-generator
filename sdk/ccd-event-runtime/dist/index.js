"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.defineCaseBindings = defineCaseBindings;
exports.createCcdClient = createCcdClient;
function defineCaseBindings() {
    return function (bindings) {
        return bindings;
    };
}
function createCcdClient(config, bindings) {
    if (config.caseTypeId && config.caseTypeId !== bindings.caseTypeId) {
        throw new Error(`config.caseTypeId ${config.caseTypeId} does not match generated bindings ${bindings.caseTypeId}`);
    }
    const resolvedConfig = {
        ...config,
        caseTypeId: bindings.caseTypeId,
    };
    return {
        event(eventId) {
            return {
                start(caseId) {
                    return startEvent(resolvedConfig, bindings, eventId, caseId);
                },
            };
        },
    };
}
async function startEvent(config, bindings, eventId, caseId) {
    const headers = await config.getAuthHeaders();
    const triggerResponse = (await config.transport.get(buildEventTriggerUrl(config.baseUrl, eventId, caseId, bindings.caseTypeId), headers));
    const eventToken = triggerResponse.token;
    if (!eventToken) {
        throw new Error(`Missing event token for ${String(eventId)}`);
    }
    let currentData = unmarshal(bindings, eventId, triggerResponse.case_details?.case_data ?? {});
    const currentState = triggerResponse.case_details?.state;
    return {
        eventId,
        get data() {
            return currentData;
        },
        async validate(pageId, patch) {
            const validationHeaders = await config.getAuthHeaders();
            const mergedData = {
                ...toRecord(currentData),
                ...toRecord(patch),
            };
            const callbackBaseUrl = config.callbackBaseUrl ?? config.baseUrl;
            const callbackBody = {
                case_details: buildCallbackCaseDetails(bindings.caseTypeId, marshal(bindings, eventId, mergedData), caseId, currentState),
                case_details_before: buildCallbackCaseDetails(bindings.caseTypeId, marshal(bindings, eventId, currentData), caseId, currentState),
                event_id: eventId,
                ignore_warning: false,
            };
            const response = (await config.transport.post(buildMidEventUrl(callbackBaseUrl, eventId, pageId), callbackBody, validationHeaders));
            currentData = response.data !== undefined
                ? unmarshal(bindings, eventId, response.data)
                : mergedData;
            return {
                data: currentData,
                errors: response.errors ?? [],
                warnings: response.warnings ?? [],
            };
        },
        async submit(data) {
            const submitHeaders = await config.getAuthHeaders();
            currentData = data;
            const payload = {
                data: marshal(bindings, eventId, data),
                event: { id: eventId },
                event_token: eventToken,
                ignore_warning: false,
            };
            return (await config.transport.post(buildEventSubmitUrl(config.baseUrl, caseId, bindings.caseTypeId), payload, submitHeaders));
        },
    };
}
function buildEventTriggerUrl(baseUrl, eventId, caseId, caseTypeId) {
    if (caseId !== undefined) {
        return `${baseUrl}/cases/${caseId}/event-triggers/${eventId}?ignore-warning=false`;
    }
    return `${baseUrl}/case-types/${caseTypeId}/event-triggers/${eventId}`;
}
function buildEventSubmitUrl(baseUrl, caseId, caseTypeId) {
    if (caseId !== undefined) {
        return `${baseUrl}/cases/${caseId}/events`;
    }
    return `${baseUrl}/case-types/${caseTypeId}/cases`;
}
function buildMidEventUrl(baseUrl, eventId, pageId) {
    return `${baseUrl}/callbacks/mid-event?page=${encodeURIComponent(pageId)}&eventId=${encodeURIComponent(eventId)}`;
}
function buildCallbackCaseDetails(caseTypeId, caseData, caseId, state) {
    return {
        ...(caseId !== undefined ? { id: caseId } : {}),
        case_type_id: caseTypeId,
        case_data: caseData,
        ...(state !== undefined ? { state } : {}),
    };
}
function marshal(bindings, eventId, data) {
    const event = bindings.events[eventId];
    const source = toRecord(data);
    const marshalled = {};
    const fieldKeyPrefix = toFieldKeyPrefix(event.fieldPrefix);
    for (const [field, value] of Object.entries(source)) {
        marshalled[fieldKeyPrefix + field] = value;
    }
    return marshalled;
}
function unmarshal(bindings, eventId, ccdData) {
    const event = bindings.events[eventId];
    const fieldKeyPrefix = toFieldKeyPrefix(event.fieldPrefix);
    const unmarshalled = {};
    for (const [field, value] of Object.entries(ccdData)) {
        if (field.startsWith(fieldKeyPrefix) && field.length > fieldKeyPrefix.length) {
            unmarshalled[field.slice(fieldKeyPrefix.length)] = value;
        }
    }
    return unmarshalled;
}
function toFieldKeyPrefix(fieldPrefix) {
    return `${fieldPrefix}_`;
}
function toRecord(value) {
    return (value ?? {});
}
