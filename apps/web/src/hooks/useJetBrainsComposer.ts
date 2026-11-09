import type { EnvironmentId } from "@t3tools/contracts";
import * as Schema from "effect/Schema";
import { useEffect, useEffectEvent } from "react";

import { type ComposerThreadTarget, useComposerDraftStore } from "../composerDraftStore";
import { readJetBrainsFileBridge, type JetBrainsClient } from "../jetBrainsBridge";
import { appendJetBrainsSelection, JetBrainsSelection } from "../jetBrainsContext";
import { dispatchSnapShotComposerFocus } from "../lib/desktopSnapShot";

const decodeJetBrainsSelection = Schema.decodeUnknownSync(JetBrainsSelection);

export function useJetBrainsComposer({
  enabled,
  environmentId,
  target,
  workspaceRoot,
}: {
  readonly enabled: boolean;
  readonly environmentId: EnvironmentId;
  readonly target: ComposerThreadTarget;
  readonly workspaceRoot: string | undefined;
}) {
  const addSelection = useEffectEvent((value: unknown) => {
    if (!enabled) throw new Error("This thread cannot receive code selections.");
    const selection = decodeJetBrainsSelection(value);
    if (readJetBrainsFileBridge(environmentId, selection.filePath) === null) {
      throw new Error("Select a thread on the connected local T3 server for this IDE project.");
    }
    const store = useComposerDraftStore.getState();
    const prompt = appendJetBrainsSelection(
      store.getComposerDraft(target)?.prompt ?? "",
      selection,
      workspaceRoot,
    );
    store.setPrompt(target, prompt);
    dispatchSnapShotComposerFocus();
  });

  // oxlint-disable-next-line t3code/no-unreasoned-state-sync -- connects the native IDE bridge to the currently mounted composer and releases it on navigation
  useEffect(() => {
    if (!enabled) return;
    const client: JetBrainsClient = {
      protocolVersion: 1,
      addSelection: (selection) => addSelection(selection),
    };
    const attach = () => {
      if (window.t3JetBrains?.protocolVersion === 1) window.t3JetBrainsClient = client;
    };
    attach();
    window.addEventListener("t3code:jetbrains-ready", attach);
    return () => {
      window.removeEventListener("t3code:jetbrains-ready", attach);
      if (window.t3JetBrainsClient === client) delete window.t3JetBrainsClient;
    };
  }, [enabled]);
}
