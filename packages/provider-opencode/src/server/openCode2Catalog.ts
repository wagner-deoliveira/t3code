import type { OpenCodeClient } from "@opencode/client/effect";
import * as Effect from "effect/Effect";
import * as Option from "effect/Option";

import * as OpenCodeRuntime from "./OpenCodeRuntime.ts";
import type { OpenCode2Catalog } from "./status.ts";

// Cold catalogs take seconds; model reads already retry empty catalogs.
const DECORATION_TIMEOUT = "3 seconds";

/** Provider names and agents are optional decoration; bound their reads so models cannot stall. */
export const loadOpenCode2Catalog = (
  client: OpenCodeClient,
  directory: string,
): Effect.Effect<OpenCode2Catalog, OpenCodeRuntime.OpenCodeRuntimeError> => {
  const location = { directory };
  return Effect.all(
    {
      models: client.model.list({ location }),
      providers: client.provider
        .list({ location })
        .pipe(Effect.timeout(DECORATION_TIMEOUT), Effect.option),
      agents: client.agent
        .list({ location })
        .pipe(Effect.timeout(DECORATION_TIMEOUT), Effect.option),
    },
    { concurrency: "unbounded" },
  ).pipe(
    Effect.map(({ models, providers, agents }) => {
      const providerNames = new Map(
        (Option.getOrUndefined(providers)?.data ?? []).map((provider) => [
          provider.id,
          provider.name,
        ]),
      );
      return {
        models: models.data.map((model) => {
          const providerName = providerNames.get(model.providerID);
          return {
            providerID: model.providerID,
            id: model.id,
            name: model.name,
            ...(providerName ? { providerName } : {}),
            variants: model.variants.map((variant) => ({ id: variant.id })),
            family: model.family,
            released: model.time.released,
            deprecated: model.status === "deprecated",
          };
        }),
        // Only primary agents can run a thread's turns; subagents are the model's own tools.
        agents: (Option.getOrUndefined(agents)?.data ?? [])
          .filter((agent) => !agent.hidden && agent.mode !== "subagent")
          .map((agent) => ({ id: agent.id, name: agent.name })),
      };
    }),
    Effect.mapError(
      (cause) =>
        new OpenCodeRuntime.OpenCodeRuntimeError({
          operation: "model.list",
          detail: "The OpenCode server could not list its models.",
          cause,
        }),
    ),
  );
};
