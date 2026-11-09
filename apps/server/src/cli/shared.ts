import { DesktopBackendBootstrap, LocalServerConnection } from "@t3tools/contracts";
import * as HostProcess from "@t3tools/shared/HostProcess";
import * as LocalServer from "@t3tools/shared/LocalServer";
import * as Console from "effect/Console";
import * as Effect from "effect/Effect";
import * as Option from "effect/Option";
import * as Schema from "effect/Schema";
import * as Layer from "effect/Layer";
import { Argument, Command, Flag } from "effect/cli";
import { FetchHttpClient } from "effect/http";

import { readBootstrapEnvelope } from "../bootstrap.ts";
import { resolveBaseDir } from "../os-jank.ts";
import { baseDirFlag } from "./config.ts";
import { resolveEntryPath } from "./invocation.ts";

const encodeConnection = Schema.encodeEffect(Schema.fromJsonString(LocalServerConnection));

export const sharedCommand = Command.make("shared", {
  baseDir: baseDirFlag,
  stop: Flag.Boolean("stop").pipe(Flag.withDefault(false)),
  bootstrapFd: Flag.Int("bootstrap-fd").pipe(Flag.optional),
  cwd: Argument.String("cwd").pipe(Argument.optional),
}).pipe(
  Command.withDescription("Connect to or start a shared background server. --stop shuts it down."),
  Command.withHandler((flags) =>
    Effect.gen(function* () {
      const server = yield* LocalServer.LocalServer;
      const baseDir = yield* resolveBaseDir(Option.getOrUndefined(flags.baseDir));
      if (flags.stop) {
        yield* server.stop(baseDir);
        yield* Console.log("Shared server shutdown requested.");
        return;
      }
      const bootstrap = Option.isSome(flags.bootstrapFd)
        ? yield* readBootstrapEnvelope(DesktopBackendBootstrap, flags.bootstrapFd.value)
        : Option.none();
      const isExecutable = yield* HostProcess.IsExecutable;
      const connection = yield* server.ensure({
        executablePath: yield* HostProcess.ExecutablePath,
        ...(isExecutable ? {} : { entryPath: yield* resolveEntryPath }),
        cwd: Option.getOrElse(flags.cwd, () => process.cwd()),
        baseDir,
        ...(Option.isSome(bootstrap) ? { bootstrap: bootstrap.value } : {}),
        bootstrapProject: Option.isSome(flags.cwd),
        env: yield* HostProcess.Environment,
      });
      yield* Console.log(`sharedServer: ${yield* encodeConnection(connection)}`);
    }).pipe(Effect.provide(LocalServer.layer.pipe(Layer.provide(FetchHttpClient.layer)))),
  ),
);
