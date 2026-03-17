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
type BoundDtoMap<TBindings extends CcdCaseBindings<any>> = TBindings extends CcdCaseBindings<infer TMap> ? TMap : never;
type BoundEventId<TBindings extends CcdCaseBindings<any>> = Extract<keyof TBindings["events"], string>;
type BoundEventData<TBindings extends CcdCaseBindings<any>, TEventId extends BoundEventId<TBindings>> = BoundDtoMap<TBindings>[TEventId & keyof BoundDtoMap<TBindings>];
type BoundPageId<TBindings extends CcdCaseBindings<any>, TEventId extends BoundEventId<TBindings>> = TBindings["events"][TEventId]["pages"][number];
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
export interface CcdEventFlow<TBindings extends CcdCaseBindings<any>, TEventId extends BoundEventId<TBindings>> {
    readonly eventId: TEventId;
    readonly data: BoundEventData<TBindings, TEventId>;
    validate(pageId: BoundPageId<TBindings, TEventId>, patch: Partial<BoundEventData<TBindings, TEventId>>, caseId?: string | number): Promise<CcdValidationResult<BoundEventData<TBindings, TEventId>>>;
    submit(data: BoundEventData<TBindings, TEventId>, caseId?: string | number): Promise<CcdSubmitResult>;
}
export declare function defineCaseBindings<T extends object>(bindings: CcdCaseBindings<T>): CcdCaseBindings<T>;
export declare function createCcdClient<TBindings extends CcdCaseBindings<any>>(config: CcdClientConfig, bindings: TBindings): {
    event<TEventId extends BoundEventId<TBindings>>(eventId: TEventId): {
        start(caseId?: string | number): Promise<CcdEventFlow<TBindings, TEventId>>;
    };
};
export declare function toPrefixStem(fieldNamespace: string): string;
export {};
