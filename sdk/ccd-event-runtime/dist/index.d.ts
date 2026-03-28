export interface CcdTransport {
    get(url: string, headers: Record<string, string>): Promise<unknown>;
    post(url: string, data: unknown, headers: Record<string, string>): Promise<unknown>;
}
type MaybePromise<T> = T | Promise<T>;
type StringKeys<T> = Extract<keyof T, string>;
type BindingEvents<T extends object> = {
    [K in StringKeys<T>]: {
        fieldPrefix: string;
        pages: readonly string[];
    };
};
type BoundDtoMap<TBindings extends CcdCaseBindings<any>> = NonNullable<TBindings["__dtoMap"]>;
type BoundEventId<TBindings extends CcdCaseBindings<any>> = Extract<keyof BoundDtoMap<TBindings>, string> & Extract<keyof TBindings["events"], string>;
type BoundEventData<TBindings extends CcdCaseBindings<any>, TEventId extends BoundEventId<TBindings>> = BoundDtoMap<TBindings>[TEventId];
type BoundPageId<TBindings extends CcdCaseBindings<any>, TEventId extends BoundEventId<TBindings>> = TBindings["events"][TEventId]["pages"][number];
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
export interface CcdEventFlow<TBindings extends CcdCaseBindings<any>, TEventId extends BoundEventId<TBindings>> {
    readonly eventId: TEventId;
    readonly data: BoundEventData<TBindings, TEventId>;
    validate(pageId: BoundPageId<TBindings, TEventId>, patch: Partial<BoundEventData<TBindings, TEventId>>): Promise<CcdValidationResult<BoundEventData<TBindings, TEventId>>>;
    submit(data: BoundEventData<TBindings, TEventId>): Promise<CcdSubmitResult>;
}
export declare function defineCaseBindings<T extends object>(): <const TBindings extends CcdCaseBindings<T>>(bindings: TBindings) => TBindings & CcdCaseBindings<T>;
export declare function createCcdClient<const TBindings extends CcdCaseBindings<any>>(config: CcdClientConfig, bindings: TBindings): {
    event<TEventId extends BoundEventId<TBindings>>(eventId: TEventId): {
        start(caseId?: string | number): Promise<CcdEventFlow<TBindings, TEventId>>;
    };
};
export {};
