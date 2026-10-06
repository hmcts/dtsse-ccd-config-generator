import assert from "node:assert/strict";
import { createServer } from "node:http";
import { after, before, describe, it } from "node:test";

import express from "express";
import request from "supertest";

import { createTemplateProxy } from "../src/express.js";
import { createHttpTemplateProvider, type SaveTemplateInput } from "../src/templates/provider.js";

describe("template proxy", () => {
  let upstream: ReturnType<typeof createServer>;
  let upstreamUrl: string;

  before(async () => {
    upstream = createServer(async (incoming, response) => {
      const chunks: Buffer[] = [];
      for await (const chunk of incoming) chunks.push(chunk);
      const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : undefined;
      response.setHeader("content-type", "application/json");
      response.end(JSON.stringify({
        authorization: incoming.headers.authorization,
        serviceAuthorization: incoming.headers.serviceauthorization,
        url: incoming.url,
        body,
      }));
    });
    await new Promise<void>((resolve) => upstream.listen(0, "127.0.0.1", resolve));
    const address = upstream.address();
    assert.ok(address && typeof address === "object");
    upstreamUrl = `http://127.0.0.1:${address.port}/docweave/templates`;
  });

  it("sends searchable wording through the proxy on create and update", async (t) => {
    const app = express();
    app.use(express.json());
    app.use("/docweave/templates", createTemplateProxy({
      upstream: upstreamUrl,
      getUserToken: () => "user-token",
      getServiceToken: () => "service-token",
    }));
    const server = app.listen(0, "127.0.0.1");
    t.after(() => new Promise<void>((resolve, reject) =>
      server.close((error) => error ? reject(error) : resolve())
    ));
    await new Promise<void>((resolve) => server.once("listening", resolve));
    const address = server.address();
    assert.ok(address && typeof address === "object");
    const provider = createHttpTemplateProvider({
      url: `http://127.0.0.1:${address.port}/docweave/templates`,
    });
    const input: SaveTemplateInput = {
      title: "Directions",
      content: {
        schema: "docweave-template", version: 1,
        content: { type: "doc", content: [
          { type: "paragraph", content: [
            { type: "text", text: "The pos", marks: [{ type: "strong" }] },
            { type: "text", text: "session claim is adjourned." },
          ] },
          { type: "ordered_list", content: [{ type: "list_item", content: [
            { type: "paragraph", content: [
              { type: "text", text: "File evidence by " },
              { type: "template_date", attrs: { name: "date", label: "Hearing date" } },
              { type: "text", text: "." },
            ] },
          ] }] },
        ] },
      },
    };
    const created = await provider.create(input);
    assert.deepEqual(created, {
      authorization: "Bearer user-token",
      serviceAuthorization: "Bearer service-token",
      url: "/docweave/templates",
      body: { ...input, searchableText: "The possession claim is adjourned. File evidence by ." },
    });

    const update = {
      ...input,
      content: { ...input.content, content: { type: "doc", content: [
        { type: "paragraph", content: [{ type: "text", text: "Costs reserved." }] },
      ] } },
      expectedRevision: 3,
    };
    const id = "11111111-1111-1111-1111-111111111111";
    const updated = await provider.update(id, update);
    assert.deepEqual(updated, {
      authorization: "Bearer user-token",
      serviceAuthorization: "Bearer service-token",
      url: `/docweave/templates/${id}`,
      body: { ...update, searchableText: "Costs reserved." },
    });
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
