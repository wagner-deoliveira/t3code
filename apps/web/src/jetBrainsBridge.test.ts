import type { EnvironmentId } from "@t3tools/contracts";
import { expect, it, vi } from "vite-plus/test";

const connection = vi.hoisted(() => ({ read: vi.fn() }));
vi.mock("./state/session", () => ({ readPreparedConnection: connection.read }));

import { readJetBrainsFileBridge, type JetBrainsBridge } from "./jetBrainsBridge";

it("routes only this server's project files to the native bridge", () => {
  const bridge: JetBrainsBridge = {
    protocolVersion: 1,
    projectPath: "C:/work/project",
    openFile: async () => {},
  };
  vi.stubGlobal("window", {
    t3JetBrains: bridge,
    location: { origin: "http://localhost:6071" },
  });
  try {
    connection.read.mockReturnValue({
      httpBaseUrl: "http://localhost:6071",
      target: { _tag: "PrimaryConnectionTarget" },
    });
    const environmentId = "primary" as EnvironmentId;
    expect(readJetBrainsFileBridge(environmentId, "C:/work/project/src/example.ts:12:4")).toBe(
      bridge,
    );
    expect(readJetBrainsFileBridge(environmentId, "C:/work/project-other/example.ts")).toBeNull();
    expect(readJetBrainsFileBridge(environmentId, "C:/work/project/../example.ts")).toBeNull();
    connection.read.mockReturnValue({
      httpBaseUrl: "https://remote.example",
      target: { _tag: "PrimaryConnectionTarget" },
    });
    expect(readJetBrainsFileBridge(environmentId, "C:/work/project/src/example.ts")).toBeNull();
    connection.read.mockReturnValue({
      httpBaseUrl: "http://localhost:6071/relay/remote",
      target: { _tag: "RelayConnectionTarget" },
    });
    expect(readJetBrainsFileBridge(environmentId, "C:/work/project/src/example.ts")).toBeNull();
    connection.read.mockReturnValue(null);
    expect(readJetBrainsFileBridge(environmentId, "C:/work/project/src/example.ts")).toBeNull();
  } finally {
    vi.unstubAllGlobals();
  }
});
