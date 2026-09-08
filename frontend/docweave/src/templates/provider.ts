import { type Node as ProseMirrorNode } from "prosemirror-model";

import { editorSchema } from "../schema.js";

export const TEMPLATE_CONTENT_VERSION = 1;
export const TEMPLATE_MAX_TITLE_LENGTH = 200;
export const TEMPLATE_MAX_CONTENT_BYTES = 64 * 1024;
export const TEMPLATE_MAX_DEPTH = 10;

export interface TemplateFragment {
  schema: "docweave-template";
  version: 1;
  content: Record<string, unknown>;
}

export interface Template {
  id: string;
  title: string;
  revision: number;
  updatedAt: string;
  content: TemplateFragment;
}

export interface TemplateSearchResult {
  items: Template[];
}

export interface SaveTemplateInput {
  title: string;
  content: TemplateFragment;
}

export interface TemplateProvider {
  search(query: string): Promise<TemplateSearchResult>;
  create(input: SaveTemplateInput): Promise<Template>;
  update(
    id: string,
    input: SaveTemplateInput & { expectedRevision: number },
  ): Promise<Template>;
  delete(id: string, expectedRevision: number): Promise<void>;
}

export class TemplateRequestError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
    this.name = "TemplateRequestError";
  }
}

const allowedNodes = new Set([
  "doc",
  "paragraph",
  "heading",
  "ordered_list",
  "list_item",
  "text",
]);
const allowedMarks = new Set(["strong", "em"]);
const encoder = new TextEncoder();

function asObject(
  value: unknown,
  description: string,
): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${description} must be an object`);
  }
  return value as Record<string, unknown>;
}

function validateNode(value: unknown, depth: number): void {
  if (depth > TEMPLATE_MAX_DEPTH) {
    throw new Error(`Template content exceeds depth ${TEMPLATE_MAX_DEPTH}`);
  }

  const node = asObject(value, "Template node");
  const type = node.type;
  if (typeof type !== "string" || !allowedNodes.has(type)) {
    throw new Error(`Unsupported template node: ${String(type)}`);
  }

  const attrs = node.attrs;
  if (attrs !== undefined) {
    const attributes = asObject(attrs, "Template node attributes");
    if (attributes.id !== undefined && attributes.id !== null) {
      throw new Error("Managed node IDs cannot be stored in a template");
    }
  }

  if (node.marks !== undefined) {
    if (!Array.isArray(node.marks)) {
      throw new Error("Template marks must be an array");
    }
    for (const value of node.marks) {
      const mark = asObject(value, "Template mark");
      if (typeof mark.type !== "string" || !allowedMarks.has(mark.type)) {
        throw new Error(`Unsupported template mark: ${String(mark.type)}`);
      }
    }
  }

  if (node.content !== undefined) {
    if (!Array.isArray(node.content)) {
      throw new Error("Template node content must be an array");
    }
    for (const child of node.content) validateNode(child, depth + 1);
  }
}

export function parseTemplateFragment(
  value: unknown,
): { fragment: TemplateFragment; document: ProseMirrorNode } {
  const envelope = asObject(value, "Template fragment");
  if (envelope.schema !== "docweave-template" || envelope.version !== 1) {
    throw new Error("Unsupported template content version");
  }

  const serialized = JSON.stringify(envelope.content);
  if (encoder.encode(serialized).byteLength > TEMPLATE_MAX_CONTENT_BYTES) {
    throw new Error(
      `Template content exceeds ${TEMPLATE_MAX_CONTENT_BYTES} bytes`,
    );
  }

  validateNode(envelope.content, 0);
  const document = editorSchema.nodeFromJSON(envelope.content);
  document.check();

  if (document.type !== editorSchema.nodes.doc) {
    throw new Error("Template content must have a document root");
  }

  const fragment: TemplateFragment = {
    schema: "docweave-template",
    version: TEMPLATE_CONTENT_VERSION,
    content: document.toJSON() as Record<string, unknown>,
  };
  return { fragment, document };
}

export function createTemplateFragment(
  document: ProseMirrorNode,
): TemplateFragment {
  return parseTemplateFragment({
    schema: "docweave-template",
    version: TEMPLATE_CONTENT_VERSION,
    content: document.toJSON(),
  }).fragment;
}

export interface HttpTemplateProviderOptions {
  url: string;
  csrfToken?: string | (() => string | undefined);
  fetch?: typeof globalThis.fetch;
}

function csrfToken(
  option: HttpTemplateProviderOptions["csrfToken"],
): string | undefined {
  return typeof option === "function" ? option() : option;
}

export function createHttpTemplateProvider(
  options: HttpTemplateProviderOptions,
): TemplateProvider {
  const request = options.fetch ?? globalThis.fetch;
  const baseUrl = options.url.replace(/\/+$/u, "");

  async function send<T>(
    path: string,
    init: RequestInit = {},
  ): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set("Accept", "application/json");
    if (init.body !== undefined) headers.set("Content-Type", "application/json");
    const token = csrfToken(options.csrfToken);
    if (token && init.method && init.method !== "GET") {
      headers.set("x-csrf-token", token);
    }

    const response = await request(`${baseUrl}${path}`, {
      ...init,
      headers,
      credentials: "same-origin",
    });
    if (!response.ok) {
      const body = await response.json().catch(() => undefined) as
        | { message?: string; error?: { message?: string } }
        | undefined;
      throw new TemplateRequestError(
        body?.message ?? body?.error?.message ??
          `Template request failed (${response.status})`,
        response.status,
      );
    }
    if (response.status === 204) return undefined as T;
    return await response.json() as T;
  }

  return {
    search(query) {
      return send<TemplateSearchResult>(
        `?${new URLSearchParams({ query })}`,
      );
    },
    create(input) {
      return send<Template>("", {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
    update(id, input) {
      return send<Template>(`/${encodeURIComponent(id)}`, {
        method: "PUT",
        body: JSON.stringify(input),
      });
    },
    delete(id, expectedRevision) {
      const parameters = new URLSearchParams({
        expectedRevision: String(expectedRevision),
      });
      return send<void>(`/${encodeURIComponent(id)}?${parameters}`, {
        method: "DELETE",
      });
    },
  };
}
