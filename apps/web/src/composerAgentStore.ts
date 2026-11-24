import { scopedThreadKey } from "@t3tools/client-runtime/environment";
import type { ScopedThreadRef } from "@t3tools/contracts";
import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";

import { resolveStorage } from "./lib/storage";

interface ComposerAgentStore {
  /** Per thread, the agent the user picked here; null is an explicit Default. */
  readonly agentByThread: Readonly<Record<string, string | null>>;
  readonly setAgent: (thread: ScopedThreadRef, agent: string | null) => void;
}

// A draft keeps its thread id when it is sent, so the pick carries over to the new thread.
const useComposerAgentStore = create<ComposerAgentStore>()(
  persist(
    (set) => ({
      agentByThread: {},
      setAgent: (thread, agent) =>
        set((state) => ({
          agentByThread: { ...state.agentByThread, [scopedThreadKey(thread)]: agent },
        })),
    }),
    {
      name: "t3code:composer-agents:v1",
      storage: createJSONStorage(() =>
        resolveStorage(typeof window !== "undefined" ? window.localStorage : undefined),
      ),
    },
  ),
);

/**
 * The agent the thread's next message goes to: this device's pick, else the agent of the
 * thread's latest message, so a delegated child or another device's pick shows as is.
 */
export function resolveComposerAgent(
  pick: string | null | undefined,
  threadAgent: string | null,
): string | null {
  return pick === undefined ? threadAgent : pick;
}

export function useComposerAgentPick(thread: ScopedThreadRef | null): string | null | undefined {
  return useComposerAgentStore((state) =>
    thread === null ? undefined : state.agentByThread[scopedThreadKey(thread)],
  );
}

export function readComposerAgentPick(thread: ScopedThreadRef): string | null | undefined {
  return useComposerAgentStore.getState().agentByThread[scopedThreadKey(thread)];
}

export function setComposerAgent(thread: ScopedThreadRef, agent: string | null): void {
  useComposerAgentStore.getState().setAgent(thread, agent);
}
