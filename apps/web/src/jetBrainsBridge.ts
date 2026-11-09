import type { EnvironmentId } from "@t3tools/contracts";
import { splitFilePathPosition, type FilePathPosition } from "@t3tools/shared/fileLinks";
import { workspaceRelativeFilePath } from "@t3tools/shared/path";

import { readPreparedConnection } from "./state/session";

export interface JetBrainsBridge {
  readonly protocolVersion: 1;
  readonly projectPath: string;
  readonly openFile: (position: FilePathPosition) => Promise<void>;
}

export interface JetBrainsClient {
  readonly protocolVersion: 1;
  readonly addSelection: (selection: unknown) => void;
}

export function readJetBrainsFileBridge(
  environmentId: EnvironmentId,
  targetPath: string,
): JetBrainsBridge | null {
  const bridge = typeof window === "undefined" ? undefined : window.t3JetBrains;
  if (bridge?.protocolVersion !== 1) return null;
  const connection = readPreparedConnection(environmentId);
  if (connection?.target._tag !== "PrimaryConnectionTarget") return null;
  const origin = new URL(connection.httpBaseUrl).origin;
  if (origin !== window.location.origin) return null;
  const relativePath = workspaceRelativeFilePath(
    splitFilePathPosition(targetPath).path,
    bridge.projectPath,
  );
  if (relativePath === null || relativePath === "." || relativePath.split(/[/\\]/).includes("..")) {
    return null;
  }
  return bridge;
}
