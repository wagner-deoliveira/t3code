// @effect-diagnostics nodeBuiltinImport:off -- Detached processes and named pipes have no scoped spawner equivalent.
import * as NodeChildProcess from "node:child_process";
import * as NodeCrypto from "node:crypto";
import * as NodeFS from "node:fs";
import * as NodeFSP from "node:fs/promises";
import * as NodeNet from "node:net";
import * as NodeOS from "node:os";
import * as NodePath from "node:path";

import { LocalServerConnection, type DesktopBackendBootstrap } from "@t3tools/contracts";
import * as Context from "effect/Context";
import * as Effect from "effect/Effect";
import * as Layer from "effect/Layer";
import * as Schedule from "effect/Schedule";
import * as Schema from "effect/Schema";
import * as Option from "effect/Option";
import { HttpClient } from "effect/http";

const SHARED_DESKTOP_SECRET_NAME = "shared-desktop-bootstrap";

export interface LocalServerStart {
  readonly executablePath: string;
  readonly entryPath?: string;
  readonly cwd: string;
  readonly baseDir: string;
  readonly bootstrap?: DesktopBackendBootstrap;
  readonly bootstrapProject?: boolean;
  readonly env: Readonly<Record<string, string | undefined>>;
}

export class LocalServerError extends Schema.TaggedError<LocalServerError>()("LocalServerError", {
  operation: Schema.Literals(["start", "stop"]),
  baseDir: Schema.String,
  cause: Schema.Defect(),
}) {
  override get message() {
    return `Could not ${this.operation} the shared T3 server for ${this.baseDir}.`;
  }
}

export class LocalServer extends Context.Service<
  LocalServer,
  {
    readonly ensure: (
      input: LocalServerStart,
    ) => Effect.Effect<LocalServerConnection, LocalServerError>;
    readonly stop: (baseDir: string) => Effect.Effect<void, LocalServerError>;
  }
>()("@t3tools/shared/LocalServer") {}

const RuntimeRecord = Schema.Struct({
  pid: Schema.Int,
  origin: Schema.String,
  startedAt: Schema.String,
  devUrl: Schema.optional(Schema.String),
  host: Schema.optional(Schema.String),
  tailscaleServeEnabled: Schema.optional(Schema.Boolean),
  tailscaleServePort: Schema.optional(Schema.Int),
});
const decodeRecord = Schema.decodeUnknownSync(RuntimeRecord);

function controlAddress(baseDir: string, pid: number) {
  // oxlint-disable-next-line t3code/no-global-process-runtime -- The local control transport follows the host OS.
  const windows = process.platform === "win32";
  const canonical = NodeFS.realpathSync.native(baseDir);
  const id = NodeCrypto.createHash("sha256")
    .update(windows ? canonical.toLowerCase() : canonical)
    .digest("hex")
    .slice(0, 24);
  if (windows) return { address: `\\\\.\\pipe\\t3-shared-${id}-${pid}`, directory: null };
  const directory = NodePath.join(NodeOS.tmpdir(), `t3-shared-${process.getuid?.() ?? "user"}`);
  return { address: NodePath.join(directory, `${id}-${pid}.sock`), directory };
}

async function secretForHome(baseDir: string) {
  const directory = NodePath.join(baseDir, "userdata", "secrets");
  const filename = NodePath.join(directory, `${SHARED_DESKTOP_SECRET_NAME}.bin`);
  await NodeFSP.mkdir(directory, { recursive: true, mode: 0o700 });
  try {
    const existing = await NodeFSP.readFile(filename);
    if (existing.length !== 32) throw new Error("Invalid shared desktop bootstrap secret.");
    return existing.toString("hex");
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  }
  const temporary = NodePath.join(
    directory,
    `${SHARED_DESKTOP_SECRET_NAME}.${NodeCrypto.randomUUID()}.tmp`,
  );
  await NodeFSP.writeFile(temporary, NodeCrypto.randomBytes(32), { mode: 0o600, flag: "wx" });
  try {
    await NodeFSP.link(temporary, filename).catch((error: NodeJS.ErrnoException) => {
      if (error.code !== "EEXIST") throw error;
    });
  } finally {
    await NodeFSP.unlink(temporary);
  }
  const secret = await NodeFSP.readFile(filename);
  if (secret.length !== 32) throw new Error("Invalid shared desktop bootstrap secret.");
  return secret.toString("hex");
}

async function readRecord(baseDir: string) {
  try {
    return decodeRecord(
      JSON.parse(
        await NodeFSP.readFile(NodePath.join(baseDir, "userdata", "server-runtime.json"), "utf8"),
      ),
    );
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw error;
  }
}

async function liveRecord(baseDir: string) {
  const record = await readRecord(baseDir);
  if (!record) return null;
  if (record.pid <= 0) throw new Error("Invalid shared server PID.");
  if (!processIsAlive(record.pid)) return null;
  const origin = new URL(record.devUrl ?? record.origin);
  if (
    origin.protocol !== "http:" ||
    !["127.0.0.1", "localhost", "[::1]"].includes(origin.hostname) ||
    origin.username ||
    origin.password ||
    origin.pathname !== "/" ||
    origin.search ||
    origin.hash
  ) {
    throw new Error("A shared local server must use a loopback origin.");
  }
  const environmentId = (
    await NodeFSP.readFile(NodePath.join(baseDir, "userdata", "environment-id"), "utf8")
  ).trim();
  return { ...record, origin: origin.origin, environmentId };
}

function processIsAlive(pid: number) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function availablePort() {
  return new Promise<number>((resolve, reject) => {
    const server = NodeNet.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        server.close();
        reject(new Error("No local port."));
        return;
      }
      server.close((error) => (error ? reject(error) : resolve(address.port)));
    });
  });
}

async function launch(input: LocalServerStart, secret: string) {
  const logs = NodePath.join(input.baseDir, "userdata", "logs");
  await NodeFSP.mkdir(logs, { recursive: true });
  const log = await NodeFSP.open(NodePath.join(logs, "shared-server.log"), "a", 0o600);
  const port = input.bootstrap?.port ?? (await availablePort());
  const bootstrap = {
    ...input.bootstrap,
    mode: "desktop",
    noBrowser: true,
    t3Home: input.baseDir,
    host: input.bootstrap?.host ?? "127.0.0.1",
    port,
    desktopBootstrapSecret: secret,
    desktopBootstrapToken: "shared-server-bootstrap",
    tailscaleServeEnabled: input.bootstrap?.tailscaleServeEnabled ?? false,
    tailscaleServePort: input.bootstrap?.tailscaleServePort ?? 443,
    desktopTelemetryFd: undefined,
    desktopTelemetryControlFd: undefined,
    desktopBrowserFd: undefined,
    desktopBrowserControlFd: undefined,
  };
  const env: Record<string, string | undefined> = {
    ...input.env,
    T3CODE_HOME: input.baseDir,
    T3CODE_NO_BROWSER: "true",
  };
  for (const key of [
    "NODE_OPTIONS",
    "VITE_DEV_SERVER_URL",
    "T3CODE_DEV_AUTH_TOKEN",
    "T3CODE_SERVICE_LAUNCHER_CONTEXT",
    "T3CODE_PORT",
    "T3CODE_HOST",
    "T3CODE_LAN",
    "T3CODE_MODE",
    "T3CODE_TAILSCALE_SERVE",
    "T3CODE_TAILSCALE_SERVE_PORT",
  ])
    delete env[key];
  if (input.entryPath) env.ELECTRON_RUN_AS_NODE = "1";
  else delete env.ELECTRON_RUN_AS_NODE;
  let child: NodeChildProcess.ChildProcess;
  try {
    child = NodeChildProcess.spawn(
      input.executablePath,
      [
        ...(input.entryPath ? [input.entryPath] : []),
        "__shared-server",
        "--bootstrap-fd",
        "3",
        "--base-dir",
        input.baseDir,
        "--mode",
        "web",
        "--no-browser",
        ...(input.bootstrapProject ? ["--auto-bootstrap-project-from-cwd", input.cwd] : []),
      ],
      {
        cwd: input.cwd,
        env,
        detached: true,
        windowsHide: true,
        stdio: ["ignore", log.fd, log.fd, "pipe"],
      },
    );
    await new Promise<void>((resolve, reject) => {
      child.once("error", reject);
      child.once("spawn", resolve);
    });
  } finally {
    await log.close();
  }
  let exit: number | null | undefined;
  const exited = new Promise<number | null>((resolve) =>
    child.once("exit", (code) => {
      exit = code;
      resolve(code);
    }),
  );
  const bootstrapPipe = child.stdio[3];
  if (!bootstrapPipe || !("end" in bootstrapPipe)) throw new Error("No bootstrap pipe.");
  bootstrapPipe.on("error", () => undefined);
  bootstrapPipe.end(`${JSON.stringify(bootstrap)}\n`);
  child.unref();
  return { pid: child.pid, exitCode: () => exit, exited };
}

const make = Effect.gen(function* () {
  const client = yield* HttpClient.HttpClient;
  const io = Effect.fn("LocalServer.io")(
    <A>(operation: "start" | "stop", baseDir: string, action: () => Promise<A>) =>
      Effect.tryPromise({
        try: action,
        catch: (cause) => new LocalServerError({ operation, baseDir, cause }),
      }),
  );
  const runningServer = Effect.fn("LocalServer.discover")(function* (
    baseDir: string,
    operation: "start" | "stop",
  ) {
    const record = yield* io(operation, baseDir, () => liveRecord(baseDir));
    if (!record) return null;
    const descriptor = yield* client
      .get(new URL("/.well-known/t3/environment", record.origin))
      .pipe(
        Effect.flatMap((response) =>
          response.status === 200 ? response.json : Effect.succeed(null),
        ),
        Effect.timeoutOption("2500 millis"),
        Effect.catch(() => Effect.succeedNone),
      );
    if (Option.isNone(descriptor) || descriptor.value === null) return null;
    const value = descriptor.value;
    if (
      typeof value !== "object" ||
      !("environmentId" in value) ||
      value.environmentId !== record.environmentId
    ) {
      return yield* new LocalServerError({
        operation,
        baseDir,
        cause: new Error("The local server does not own this T3 home."),
      });
    }
    return record;
  });
  const ensure: LocalServer["Service"]["ensure"] = Effect.fn("LocalServer.ensure")(
    function* (input) {
      const secret = yield* io("start", input.baseDir, () => secretForHome(input.baseDir));
      let record = yield* runningServer(input.baseDir, "start");
      if (!record) {
        const child = yield* io("start", input.baseDir, () => launch(input, secret));
        record = yield* Effect.gen(function* () {
          const active = yield* runningServer(input.baseDir, "start");
          if (active) return active;
          const code = child.exitCode();
          if (code !== undefined && code !== 78) {
            return yield* new LocalServerError({
              operation: "start",
              baseDir: input.baseDir,
              cause: new Error(`Shared server exited (${code}). See shared-server.log.`),
            });
          }
          return yield* new ServerStarting({});
        }).pipe(
          Effect.retry({
            while: (error) => error._tag === "ServerStarting",
            schedule: Schedule.spaced("100 millis").pipe(Schedule.upTo({ times: 1_800 })),
          }),
          Effect.timeout("3 minutes"),
        );
        if (record.pid !== child.pid) {
          yield* io("start", input.baseDir, () => child.exited).pipe(Effect.timeout("3 minutes"));
        }
      }
      return {
        version: 1,
        origin: record.origin,
        pid: record.pid,
        bootstrapSecret: secret,
        host: record.host,
        tailscaleServeEnabled: record.tailscaleServeEnabled,
        tailscaleServePort: record.tailscaleServePort,
      } satisfies LocalServerConnection;
    },
    (effect, input) =>
      effect.pipe(
        Effect.mapError(
          (cause) => new LocalServerError({ operation: "start", baseDir: input.baseDir, cause }),
        ),
      ),
  );

  const stop: LocalServer["Service"]["stop"] = Effect.fn("LocalServer.stop")(
    function* (baseDir) {
      const record = yield* runningServer(baseDir, "stop");
      if (!record) return;
      const secret = yield* io("stop", baseDir, () => secretForHome(baseDir));
      yield* io(
        "stop",
        baseDir,
        () =>
          new Promise<void>((resolve, reject) => {
            const socket = NodeNet.createConnection(controlAddress(baseDir, record.pid).address);
            socket.setEncoding("utf8");
            socket.setTimeout(5_000, () =>
              socket.destroy(new Error("Shared server control timed out.")),
            );
            socket.once("error", reject);
            socket.once("connect", () => socket.end(`${secret}\n`));
            let response = "";
            socket.on("data", (chunk) => {
              response += chunk;
            });
            socket.once("end", () =>
              response.trim() === "OK"
                ? resolve()
                : reject(new Error("Shared server refused shutdown.")),
            );
          }),
      );
      yield* Effect.gen(function* () {
        const current = yield* io("stop", baseDir, () => readRecord(baseDir));
        const alive = yield* Effect.sync(() => processIsAlive(record.pid));
        if (current?.pid === record.pid || alive) return yield* new ServerStarting({});
      }).pipe(
        Effect.retry({
          while: (error) => error._tag === "ServerStarting",
          schedule: Schedule.spaced("100 millis"),
        }),
        Effect.timeout("30 seconds"),
      );
    },
    (effect, baseDir) =>
      effect.pipe(
        Effect.mapError((cause) => new LocalServerError({ operation: "stop", baseDir, cause })),
      ),
  );
  return LocalServer.of({ ensure, stop });
});

export const layer = Layer.effect(LocalServer, make);

class ServerStarting extends Schema.TaggedError<ServerStarting>()("ServerStarting", {}) {}

export async function openSharedServerControl(baseDir: string) {
  const secret = await secretForHome(baseDir);
  const address = controlAddress(baseDir, process.pid);
  if (address.directory) {
    await NodeFSP.mkdir(address.directory, { recursive: true, mode: 0o700 });
    const stat = await NodeFSP.lstat(address.directory);
    if (!stat.isDirectory() || stat.uid !== process.getuid?.() || (stat.mode & 0o077) !== 0) {
      throw new Error("The shared server control directory is not private.");
    }
    await NodeFSP.unlink(address.address).catch((error: NodeJS.ErrnoException) => {
      if (error.code !== "ENOENT") throw error;
    });
  }
  const server = NodeNet.createServer((socket) => {
    socket.setEncoding("utf8");
    socket.setTimeout(5_000, () => socket.destroy());
    let input = "";
    socket.on("data", (chunk) => {
      input += chunk;
      if (input.length > 256) {
        socket.destroy();
        return;
      }
      if (!input.includes("\n")) return;
      const supplied = Buffer.from(input.trim());
      const expected = Buffer.from(secret);
      if (supplied.length !== expected.length || !NodeCrypto.timingSafeEqual(supplied, expected)) {
        socket.end("DENIED\n");
        return;
      }
      socket.end("OK\n", () => {
        if (!process.emit("SIGTERM")) process.exit(0);
      });
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(address.address, resolve);
  });
  process.once("exit", () => {
    if (address.directory) {
      try {
        NodeFS.unlinkSync(address.address);
      } catch {
        /* Already removed during shutdown. */
      }
    }
  });
}
