"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.defineCaseBindings = defineCaseBindings;
exports.createCcdClient = createCcdClient;
exports.toPrefixStem = toPrefixStem;
function defineCaseBindings(bindings) {
    return bindings;
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
    return {
        eventId,
        get data() {
            return currentData;
        },
        async validate(pageId, patch, validateCaseId) {
            const validationHeaders = await config.getAuthHeaders();
            const mergedData = { ...toRecord(currentData), ...toRecord(patch) };
            const callbackBaseUrl = config.callbackBaseUrl ?? config.baseUrl;
            const callbackBody = {
                case_details: buildCallbackCaseDetails(bindings.caseTypeId, marshal(bindings, eventId, mergedData), validateCaseId ?? caseId),
                case_details_before: buildCallbackCaseDetails(bindings.caseTypeId, marshal(bindings, eventId, currentData), validateCaseId ?? caseId),
                event_id: eventId,
                ignore_warning: false,
            };
            const response = (await config.transport.post(buildMidEventUrl(callbackBaseUrl, eventId, pageId), callbackBody, validationHeaders));
            currentData = unmarshal(bindings, eventId, response.data ?? {});
            return {
                data: currentData,
                errors: response.errors ?? [],
                warnings: response.warnings ?? [],
            };
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
            return (await config.transport.post(buildEventSubmitUrl(config.baseUrl, submitCaseId ?? caseId, bindings.caseTypeId), payload, submitHeaders));
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
function buildCallbackCaseDetails(caseTypeId, caseData, caseId) {
    return {
        ...(caseId !== undefined ? { id: caseId } : {}),
        case_type_id: caseTypeId,
        case_data: caseData,
    };
}
function marshal(bindings, eventId, data) {
    const event = bindings.events[eventId];
    const source = toRecord(data);
    const marshalled = {};
    const prefixStem = toPrefixStem(event.fieldNamespace);
    for (const field of event.fields) {
        if (Object.prototype.hasOwnProperty.call(source, field)) {
            marshalled[prefixStem + capitalise(field)] = source[field];
        }
    }
    return marshalled;
}
function unmarshal(bindings, eventId, ccdData) {
    const event = bindings.events[eventId];
    const prefixStem = toPrefixStem(event.fieldNamespace);
    const unmarshalled = {};
    for (const field of event.fields) {
        const prefixedField = prefixStem + capitalise(field);
        if (Object.prototype.hasOwnProperty.call(ccdData, prefixedField)) {
            unmarshalled[field] = ccdData[prefixedField];
        }
    }
    return unmarshalled;
}
function toPrefixStem(fieldNamespace) {
    const segments = fieldNamespace.split(".");
    return segments
        .map((segment, index) => (index === 0 ? segment : capitalise(segment)))
        .join("");
}
function capitalise(value) {
    return value.length === 0 ? value : value[0].toUpperCase() + value.slice(1);
}
function toRecord(value) {
    return (value ?? {});
}
