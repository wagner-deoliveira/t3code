import {
  ProviderInteractionMode,
  ProviderOptionSelectionValue,
  ProviderInstanceId,
  RuntimeMode,
  type OrchestratorMcpDelegateTaskInput,
  type OrchestratorMcpTarget,
} from "@t3tools/contracts";
import * as Effect from "effect/Effect";
import * as FileSystem from "effect/FileSystem";
import * as Option from "effect/Option";
import * as Path from "effect/Path";
import * as Schema from "effect/Schema";
import { parse as parseYaml } from "yaml";

export const AGENT_DEFINITIONS_DIRECTORY = ".agents/agents";
/** Relative to the T3 home; holds the agents every project can use. */
export const USER_AGENT_DEFINITIONS_DIRECTORY = "agents";

const MAX_AGENT_FILES = 50;
const MAX_AGENT_FILE_BYTES = 64 * 1024;
const AGENT_NAME_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/;
const FRONTMATTER_PATTERN = /^---\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/;

const AgentFrontmatter = Schema.Struct({
  description: Schema.optional(Schema.String),
  provider: Schema.optional(ProviderInstanceId),
  model: Schema.optional(Schema.String),
  options: Schema.optional(Schema.Record(Schema.String, ProviderOptionSelectionValue)),
  runtimeMode: Schema.optional(RuntimeMode),
  interactionMode: Schema.optional(ProviderInteractionMode),
  isolation: Schema.optional(Schema.Literals(["shared", "worktree"])),
});
const decodeFrontmatter = Schema.decodeUnknownOption(AgentFrontmatter);

/** A named agent a user defined in `.agents/agents/<name>.md`; its body is the agent's instructions. */
export interface AgentDefinition {
  readonly name: string;
  readonly description: string;
  readonly instructions: string;
  readonly target:
    | Pick<OrchestratorMcpTarget, "providerInstanceId" | "model" | "options">
    | undefined;
  readonly runtimeMode: OrchestratorMcpDelegateTaskInput["runtimeMode"];
  readonly interactionMode: OrchestratorMcpDelegateTaskInput["interactionMode"];
  readonly isolation: OrchestratorMcpDelegateTaskInput["isolation"];
}

/** Null for a file that is not a usable definition, so one bad file never hides the others. */
export function parseAgentDefinition(name: string, source: string): AgentDefinition | null {
  if (!AGENT_NAME_PATTERN.test(name)) return null;
  const contents = source.charCodeAt(0) === 0xfeff ? source.slice(1) : source;
  const match = FRONTMATTER_PATTERN.exec(contents);
  let raw: unknown = {};
  if (match) {
    try {
      raw = parseYaml(match[1] ?? "") ?? {};
    } catch {
      return null;
    }
  }
  const frontmatter = decodeFrontmatter(raw);
  if (Option.isNone(frontmatter)) return null;
  const { description, provider, model, options, runtimeMode, interactionMode, isolation } =
    frontmatter.value;
  const instructions = (match ? contents.slice(match[0].length) : contents).trim();
  if (instructions === "") return null;
  const target =
    provider === undefined && model === undefined && options === undefined
      ? undefined
      : {
          ...(provider === undefined ? {} : { providerInstanceId: provider }),
          ...(model === undefined ? {} : { model }),
          ...(options === undefined
            ? {}
            : { options: Object.entries(options).map(([id, value]) => ({ id, value })) }),
        };
  return {
    name,
    description: description?.trim() ?? "",
    instructions,
    target,
    runtimeMode,
    interactionMode,
    isolation,
  };
}

const readDirectoryDefinitions = Effect.fn("DelegatedAgents.readDirectory")(function* (
  directory: string,
) {
  const fileSystem = yield* FileSystem.FileSystem;
  const path = yield* Path.Path;
  const entries = yield* fileSystem
    .readDirectory(directory)
    .pipe(Effect.orElseSucceed((): ReadonlyArray<string> => []));
  const definitions = yield* Effect.forEach(
    entries
      .filter((entry) => entry.endsWith(".md"))
      .toSorted()
      .slice(0, MAX_AGENT_FILES),
    (entry) =>
      fileSystem.readFileString(path.join(directory, entry)).pipe(
        Effect.map((contents) =>
          contents.length > MAX_AGENT_FILE_BYTES
            ? null
            : parseAgentDefinition(entry.slice(0, -".md".length), contents),
        ),
        Effect.orElseSucceed(() => null),
      ),
    { concurrency: 8 },
  );
  return definitions.filter((definition) => definition !== null);
});

/**
 * Reads the definitions in a checkout plus the user's own, ordered by name. The project's
 * definition wins when both define a name. A missing directory has none.
 */
export const loadAgentDefinitions = Effect.fn("DelegatedAgents.load")(function* (
  cwd: string,
  userDirectory: string,
) {
  const path = yield* Path.Path;
  const project = yield* readDirectoryDefinitions(path.join(cwd, AGENT_DEFINITIONS_DIRECTORY));
  const user = yield* readDirectoryDefinitions(userDirectory);
  const projectNames = new Set(project.map((definition) => definition.name));
  return [...project, ...user.filter((definition) => !projectNames.has(definition.name))].toSorted(
    (a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0),
  );
});

/** The delegation with the agent's instructions and defaults applied; what the caller passed wins. */
export function applyAgentDefinition(
  input: OrchestratorMcpDelegateTaskInput,
  agent: AgentDefinition,
): OrchestratorMcpDelegateTaskInput {
  return {
    ...input,
    task: `${agent.instructions}\n\n${input.task}`,
    ...(input.target === undefined && agent.target !== undefined ? { target: agent.target } : {}),
    ...(input.runtimeMode === undefined && agent.runtimeMode !== undefined
      ? { runtimeMode: agent.runtimeMode }
      : {}),
    ...(input.interactionMode === undefined && agent.interactionMode !== undefined
      ? { interactionMode: agent.interactionMode }
      : {}),
    ...(input.isolation === undefined && agent.isolation !== undefined
      ? { isolation: agent.isolation }
      : {}),
  };
}
