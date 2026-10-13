import { createHmac } from "node:crypto";

export class LocalAuthError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "LocalAuthError";
  }
}

interface LocalAuthOptions {
  idamUrl: string;
  idamClientId: string;
  idamClientSecret: string;
  testUserEmail: string;
  testUserPassword: string;
  s2sServiceName: string;
  s2sDummySecret: string;
  fetch?: typeof globalThis.fetch;
}

interface IdamTokenResponse {
  access_token?: string;
  expires_in?: number;
}

const tokenRefreshMarginMs = 30_000;
const defaultTokenTtlSeconds = 3_600;

function base64Url(value: string): string {
  return Buffer.from(value, "utf8").toString("base64url");
}

export function createDummyS2SToken(
  serviceName: string,
  secret: string,
): string {
  const header = base64Url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = base64Url(JSON.stringify({
    sub: serviceName,
    iat: Math.floor(Date.now() / 1_000),
  }));
  const signature = createHmac("sha256", secret)
    .update(`${header}.${payload}`)
    .digest("base64url");
  return `${header}.${payload}.${signature}`;
}

export function createLocalAuthTokenSource(options: LocalAuthOptions): {
  getUserToken: () => Promise<string>;
  getServiceToken: () => string;
  checkReadiness: () => Promise<void>;
} {
  const request = options.fetch ?? globalThis.fetch;
  let cached: { token: string; expiresAt: number } | undefined;
  let pending: Promise<{ token: string; expiresAt: number }> | undefined;

  async function fetchUserToken(): Promise<{
    token: string;
    expiresAt: number;
  }> {
    const tokenUrl = `${options.idamUrl.replace(/\/+$/u, "")}/o/token`;
    const body = new URLSearchParams({
      grant_type: "password",
      client_id: options.idamClientId,
      client_secret: options.idamClientSecret,
      username: options.testUserEmail,
      password: options.testUserPassword,
      scope: "openid profile roles",
    });

    let response: Response;
    try {
      response = await request(tokenUrl, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: body.toString(),
      });
    } catch (error) {
      throw new LocalAuthError(
        `Unable to reach the local IDAM service at ${tokenUrl}. ` +
          "Start the CCD/e2e stack with ./gradlew e2e:bootWithCCD before running in backend mode.",
        { cause: error },
      );
    }

    if (!response.ok) {
      const text = await response.text().catch(() => "");
      throw new LocalAuthError(
        `Local IDAM token request for "${options.testUserEmail}" failed ` +
          `(${response.status}): ${text}`,
      );
    }

    const payload = await response.json() as IdamTokenResponse;
    if (!payload.access_token) {
      throw new LocalAuthError(
        "Local IDAM token response did not include an access_token",
      );
    }
    return {
      token: `Bearer ${payload.access_token}`,
      expiresAt: Date.now() +
        (payload.expires_in ?? defaultTokenTtlSeconds) * 1_000,
    };
  }

  async function getUserToken(): Promise<string> {
    if (cached && cached.expiresAt - tokenRefreshMarginMs > Date.now()) {
      return cached.token;
    }
    pending ??= fetchUserToken().finally(() => {
      pending = undefined;
    });
    cached = await pending;
    return cached.token;
  }

  return {
    getUserToken,
    getServiceToken: () => createDummyS2SToken(
      options.s2sServiceName,
      options.s2sDummySecret,
    ),
    checkReadiness: async () => {
      await getUserToken();
    },
  };
}
