import type { RequestHandler } from "express";

import { createLocalAuthTokenSource } from "./local-auth.js";

export class BackendModeConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "BackendModeConfigError";
  }
}

export class BackendModeUnavailableError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "BackendModeUnavailableError";
  }
}

export interface BackendModeConfig {
  upstreamUrl: string;
  idamUrl: string;
  idamClientId: string;
  idamClientSecret: string;
  testUserEmail: string;
  testUserPassword: string;
  s2sServiceName: string;
  s2sDummySecret: string;
}

export function isBackendModeSelected(env: NodeJS.ProcessEnv): boolean {
  return (env.DOCWEAVE_DEMO_MODE ?? "").trim().toLowerCase() === "backend";
}

function trimmed(value: string | undefined): string | undefined {
  const result = value?.trim();
  return result ? result : undefined;
}

export function resolveBackendModeConfig(
  env: NodeJS.ProcessEnv,
): BackendModeConfig {
  const upstreamUrl = trimmed(env.DOCWEAVE_DEMO_UPSTREAM_URL);
  if (!upstreamUrl) {
    throw new BackendModeConfigError(
      "DOCWEAVE_DEMO_MODE=backend requires DOCWEAVE_DEMO_UPSTREAM_URL to " +
        "point at the e2e application's /docweave/templates endpoint " +
        "(for example http://localhost:4013/docweave/templates).",
    );
  }
  return {
    upstreamUrl,
    idamUrl: trimmed(env.IDAM_API_BASEURL) ?? "http://localhost:5062",
    idamClientId: trimmed(env.IDAM_CLIENT_ID) ?? "divorce",
    idamClientSecret: trimmed(env.IDAM_CLIENT_SECRET) ?? "123456",
    testUserEmail: trimmed(env.DOCWEAVE_DEMO_TEST_USER_EMAIL) ??
      "TEST_CASE_WORKER_USER@mailinator.com",
    testUserPassword: trimmed(env.DOCWEAVE_DEMO_TEST_USER_PASSWORD) ??
      "password",
    s2sServiceName: trimmed(env.DOCWEAVE_DEMO_S2S_SERVICE) ??
      "nfdiv_case_api",
    s2sDummySecret: trimmed(env.DOCWEAVE_DEMO_S2S_DUMMY_SECRET) ?? "secret",
  };
}

export async function createBackendTemplateProxy(
  config: BackendModeConfig,
  dependencies: { fetch?: typeof globalThis.fetch } = {},
): Promise<RequestHandler> {
  const { createTemplateProxy } = await import("@hmcts-cft/docweave/express");
  const auth = createLocalAuthTokenSource({
    ...config,
    fetch: dependencies.fetch,
  });

  await auth.checkReadiness();
  const userToken = await auth.getUserToken();
  const serviceToken = auth.getServiceToken();
  const readinessUrl = new URL(config.upstreamUrl);
  readinessUrl.searchParams.set("query", "docweave-readiness-probe");

  let readinessResponse: Response;
  try {
    readinessResponse = await (dependencies.fetch ?? globalThis.fetch)(
      readinessUrl,
      {
        headers: {
          Accept: "application/json",
          Authorization: userToken,
          ServiceAuthorization: serviceToken,
        },
      },
    );
  } catch (error) {
    throw new BackendModeUnavailableError(
      `Unable to reach the Docweave backend at ${config.upstreamUrl}`,
      { cause: error },
    );
  }
  if (!readinessResponse.ok) {
    throw new BackendModeUnavailableError(
      `Docweave backend readiness check failed (${readinessResponse.status})`,
    );
  }

  return createTemplateProxy({
    upstream: config.upstreamUrl,
    getUserToken: () => auth.getUserToken(),
    getServiceToken: () => auth.getServiceToken(),
  });
}
