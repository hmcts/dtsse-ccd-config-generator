import assert from "node:assert/strict";
import { createServer } from "node:http";
import { after, before, describe, it } from "node:test";

import express from "express";
import request from "supertest";

import { createTemplateProxy } from "../src/express.js";

describe("template proxy", () => {
  let upstream: ReturnType<typeof createServer>;
  let upstreamUrl: string;

  before(async () => {
    upstream = createServer((incoming, response) => {
      response.setHeader("content-type", "application/json");
      response.end(JSON.stringify({
        authorization: incoming.headers.authorization,
        serviceAuthorization: incoming.headers.serviceauthorization,
        url: incoming.url,
      }));
    });
    await new Promise<void>((resolve) => upstream.listen(0, "127.0.0.1", resolve));
    const address = upstream.address();
    assert.ok(address && typeof address === "object");
    upstreamUrl = `http://127.0.0.1:${address.port}/docweave/templates`;
  });

  after(async () => {
    await new Promise<void>((resolve, reject) =>
      upstream.close((error) => error ? reject(error) : resolve())
    );
  });

  it("forwards only supported routes with server supplied tokens", async () => {
    const app = express();
    app.use(express.json());
    app.use("/docweave/templates", createTemplateProxy({
      upstream: upstreamUrl,
      getUserToken: () => "user-token",
      getServiceToken: () => "service-token",
    }));

    const response = await request(app)
      .get("/docweave/templates?query=costs")
      .expect(200);

    assert.deepEqual(response.body, {
      authorization: "Bearer user-token",
      serviceAuthorization: "Bearer service-token",
      url: "/docweave/templates?query=costs",
    });
    assert.equal(response.headers["cache-control"], "private, no-store");
    await request(app).get("/docweave/templates/not/a/template").expect(404);
    await request(app).get(
      "/docweave/templates/11111111-1111-1111-1111-111111111111",
    ).expect(404);
    await request(app).delete("/docweave/templates").expect(404);
    await request(app).post(
      "/docweave/templates/11111111-1111-1111-1111-111111111111",
    ).expect(404);
    await request(app).put("/docweave/templates").expect(404);
  });
});
