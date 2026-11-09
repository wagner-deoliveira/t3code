import * as NodeServices from "@effect/platform-node/NodeServices";
import { assert, describe, it } from "@effect/vitest";
import * as Effect from "effect/Effect";
import * as FileSystem from "effect/FileSystem";
import * as Layer from "effect/Layer";
import * as Path from "effect/Path";
import { FetchHttpClient, HttpClient } from "effect/http";

import * as LocalServer from "./LocalServer.ts";

const layer = LocalServer.layer.pipe(
  Layer.provideMerge(NodeServices.layer),
  Layer.provideMerge(FetchHttpClient.layer),
);

const fixture = Effect.gen(function* () {
  const fs = yield* FileSystem.FileSystem;
  const path = yield* Path.Path;
  const root = yield* fs.makeTempDirectoryScoped({ prefix: "t3-shared-local-server-" });
  const entry = path.join(root, "fixture.mjs");
  const controlModule = new URL("./LocalServer.ts", import.meta.url).href;
  yield* fs.writeFileString(
    entry,
    `
    import fs from "node:fs";
    import path from "node:path";
    import http from "node:http";
    import net from "node:net";
    import readline from "node:readline";
    import { openSharedServerControl } from ${JSON.stringify(controlModule)};
    const base = process.argv[process.argv.indexOf("--base-dir") + 1];
    const data = path.join(base, "userdata");
    const pipe = new net.Socket({ fd: 3, readable: true, writable: false });
    const lines = readline.createInterface({ input: pipe });
    const bootstrap = await new Promise(resolve => lines.once("line", line => resolve(JSON.parse(line))));
    lines.close(); pipe.destroy();
    await openSharedServerControl(base);
    const record = path.join(data, "server-runtime.json");
    const candidate = path.join(data, "record-" + process.pid);
    fs.writeFileSync(candidate, JSON.stringify({ pid: process.pid, origin: "http://127.0.0.1:" + bootstrap.port, startedAt: new Date().toISOString() }));
    try { fs.linkSync(candidate, record); } catch (error) {
      if (error.code === "EEXIST") process.exit(78);
      throw error;
    } finally { fs.rmSync(candidate, { force: true }); }
    fs.writeFileSync(path.join(data, "environment-id"), "fixture-environment");
    const threadsFile = path.join(data, "threads.json");
    let threads = fs.existsSync(threadsFile) ? JSON.parse(fs.readFileSync(threadsFile, "utf8")) : [];
    const server = http.createServer((request, response) => {
      response.setHeader("Content-Type", "application/json");
      if (request.url === "/.well-known/t3/environment") {
        response.end(JSON.stringify({ environmentId: "fixture-environment" })); return;
      }
      if (request.method === "POST") {
        threads.push("shared-thread");
        fs.writeFileSync(threadsFile, JSON.stringify(threads));
      }
      response.end(JSON.stringify(threads));
    });
    process.on("SIGTERM", () => server.close(() => {
      if (JSON.parse(fs.readFileSync(record, "utf8")).pid === process.pid) fs.unlinkSync(record);
      fs.writeFileSync(path.join(data, "stopped"), "yes");
      process.exit(0);
    }));
    server.listen(bootstrap.port, bootstrap.host);
  `,
  );
  return {
    executablePath: process.execPath,
    entryPath: entry,
    cwd: root,
    baseDir: path.join(root, "home"),
    env: process.env,
  } satisfies LocalServer.LocalServerStart;
});

describe("LocalServer", () => {
  it.live(
    "racing clients share one server, keep its threads after disconnect, and stop it gracefully",
    () =>
      Effect.gen(function* () {
        const input = yield* fixture;
        const server = yield* LocalServer.LocalServer;
        const client = yield* HttpClient.HttpClient;
        const path = yield* Path.Path;
        yield* Effect.addFinalizer(() => server.stop(input.baseDir).pipe(Effect.ignore));
        const [first, second] = yield* Effect.all([server.ensure(input), server.ensure(input)], {
          concurrency: "unbounded",
        });
        assert.equal(first.pid, second.pid);
        assert.equal(first.origin, second.origin);
        assert.equal(first.bootstrapSecret, second.bootstrapSecret);
        yield* client
          .post(new URL("/threads", first.origin))
          .pipe(Effect.flatMap((response) => response.json));
        assert.deepEqual(
          yield* client
            .get(new URL("/threads", second.origin))
            .pipe(Effect.flatMap((response) => response.json)),
          ["shared-thread"],
        );
        const reconnected = yield* Effect.scoped(server.ensure(input));
        assert.equal(reconnected.pid, first.pid);
        yield* server.stop(input.baseDir);
        assert.isFalse(
          yield* (yield* FileSystem.FileSystem).exists(
            path.join(input.baseDir, "userdata", "server-runtime.json"),
          ),
        );
        const restarted = yield* server.ensure(input);
        assert.notEqual(restarted.pid, first.pid);
        assert.deepEqual(
          yield* client
            .get(new URL("/threads", restarted.origin))
            .pipe(Effect.flatMap((response) => response.json)),
          ["shared-thread"],
        );
      }).pipe(Effect.scoped, Effect.provide(layer)),
  );

  it.live("refuses a live port serving another environment instead of attaching to it", () =>
    Effect.gen(function* () {
      const input = yield* fixture;
      const server = yield* LocalServer.LocalServer;
      const fs = yield* FileSystem.FileSystem;
      const path = yield* Path.Path;
      yield* Effect.addFinalizer(() => server.stop(input.baseDir).pipe(Effect.ignore));
      yield* server.ensure(input);
      const idPath = path.join(input.baseDir, "userdata", "environment-id");
      yield* fs.writeFileString(idPath, "another-environment");
      const failure = yield* Effect.flip(server.ensure(input)).pipe(
        Effect.ensuring(fs.writeFileString(idPath, "fixture-environment").pipe(Effect.orDie)),
      );
      assert.equal(failure._tag, "LocalServerError");
      assert.isTrue((yield* server.ensure(input)).pid > 0);
    }).pipe(Effect.scoped, Effect.provide(layer)),
  );
});
