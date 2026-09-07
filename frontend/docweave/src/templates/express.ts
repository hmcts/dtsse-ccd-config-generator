import type { Request, RequestHandler } from "express";

const DEFAULT_TIMEOUT_MS = 10_000;

export interface TemplateProxyOptions {
  upstream: string;
  getUserToken(request: Request): string | undefined | Promise<string | undefined>;
  getServiceToken(): string | undefined | Promise<string | undefined>;
  timeoutMs?: number;
}

const templatePath = /^\/[0-9a-f-]+$/iu;

function isSupportedRoute(method: string, path: string): boolean {
  return path === "/"
    ? method === "GET" || method === "POST"
    : templatePath.test(path) &&
      (method === "PUT" || method === "DELETE");
}

export function createTemplateProxy(
  options: TemplateProxyOptions,
): RequestHandler {
  const upstream = options.upstream.replace(/\/+$/u, "");
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  return async (request, response, next) => {
    response.setHeader("cache-control", "private, no-store");

    if (!isSupportedRoute(request.method, request.path)) {
      response.status(404).json({ message: "Template route not found" });
      return;
    }

    try {
      const [userToken, serviceToken] = await Promise.all([
        options.getUserToken(request),
        options.getServiceToken(),
      ]);
      if (!userToken) {
        response.status(401).json({ message: "Authentication required" });
        return;
      }
      if (!serviceToken) {
        response.status(503).json({ message: "Service authentication unavailable" });
        return;
      }

      const query = request.url.includes("?")
        ? request.url.slice(request.url.indexOf("?"))
        : "";
      const path = request.path === "/" ? "" : request.path;
      const target = new URL(`${upstream}${path}${query}`);
      const headers = new Headers({
        Accept: "application/json",
        Authorization: userToken.startsWith("Bearer ")
          ? userToken
          : `Bearer ${userToken}`,
        ServiceAuthorization: serviceToken.startsWith("Bearer ")
          ? serviceToken
          : `Bearer ${serviceToken}`,
      });
      const hasBody = request.method === "POST" || request.method === "PUT";
      if (hasBody) headers.set("Content-Type", "application/json");

      const upstreamResponse = await fetch(target, {
        method: request.method,
        headers,
        body: hasBody ? JSON.stringify(request.body) : undefined,
        signal: AbortSignal.timeout(timeoutMs),
      });
      response.status(upstreamResponse.status);
      const contentType = upstreamResponse.headers.get("content-type");
      if (contentType) response.setHeader("content-type", contentType);
      response.send(Buffer.from(await upstreamResponse.arrayBuffer()));
    } catch (error) {
      if (error instanceof Error && error.name === "TimeoutError") {
        response.status(504).json({ message: "Template service timed out" });
        return;
      }
      if (response.headersSent) {
        next(error);
        return;
      }
      response.status(502).json({ message: "Template service unavailable" });
    }
  };
}
