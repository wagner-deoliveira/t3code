import { createMemoryHistory } from "@tanstack/react-router";
import { scopeProjectRef } from "@t3tools/client-runtime/environment";
import { ThreadId } from "@t3tools/contracts";
import { DEFAULT_CLIENT_SETTINGS } from "@t3tools/contracts/settings";
import type * as Context from "effect/Context";
import { expect, it, vi } from "vite-plus/test";
import { page } from "vite-plus/test/browser";

import { AppRoot } from "~/AppRoot";
// The route loads ChatView lazily, and its first transform (React Compiler over ~4k lines)
// takes 6-11s; importing it here keeps that out of the test's timeout.
import "~/components/ChatView";
import { DraftId, useComposerDraftStore } from "~/composerDraftStore";
import { __setClientSettingsForTests } from "~/hooks/useSettings";
import { getRouter } from "~/router";
import {
  ENVIRONMENT_ID,
  PROJECT_ID,
  dispatchedCommands,
  installFakeEnvironment,
  type FakeDriver,
} from "../../test/fakeEnvironment";
import { act, render } from "../../test/render";

// The socket is the only fake layer; see test/appScenarios.renders.tsx.
vi.mock("../../../../packages/client-runtime/src/connection/driver.ts", async (importOriginal) => {
  const [actual, { fakeDriver }, Layer] = await Promise.all([
    importOriginal<{ readonly ConnectionDriver: Context.Key<unknown, FakeDriver> }>(),
    import("../../test/fakeEnvironment"),
    import("effect/Layer"),
  ]);
  return { ...actual, layer: Layer.succeed(actual.ConnectionDriver, fakeDriver) };
});

vi.mock(import("~/rpc/requestLatencyState"), async (original) => ({
  ...(await original()),
  trackRpcRequestSent: () => {},
}));

it("keeps a new draft local while accepting an IDE selection", async () => {
  using _environment = installFakeEnvironment([]);
  using fetchSpy = vi.spyOn(globalThis, "fetch");
  __setClientSettingsForTests({
    ...DEFAULT_CLIENT_SETTINGS,
    onboardingCompletedAt: "2026-01-01T00:00:00.000Z",
  });
  const draftId = DraftId.make("draft-27443");
  const threadId = ThreadId.make("thread-27443");
  useComposerDraftStore
    .getState()
    .setProjectDraftThreadId(scopeProjectRef(ENVIRONMENT_ID, PROJECT_ID), draftId, { threadId });

  const router = getRouter(createMemoryHistory({ initialEntries: [`/draft/${draftId}`] }));
  await router.load();
  await using _app = await render(<AppRoot router={router} />, {
    // AppRoot brings its own router.
    router: false,
  });
  await expect.element(page.getByTestId("composer-editor")).toBeVisible();
  // The shell snapshot has arrived once the sidebar lists the project, so the connection is up.
  await expect.element(page.getByText("render", { exact: true }).first()).toBeVisible();

  const threadRequests = fetchSpy.mock.calls
    .map(([input]) => String(input instanceof Request ? input.url : input))
    .filter((url) => url.includes(`/api/orchestration/threads/${threadId}/`));
  // A 404 here marks the thread deleted, and the view never loads it once the send creates it.
  expect(threadRequests).toEqual([]);

  const previousBridge = window.t3JetBrains;
  using _bridge = {
    [Symbol.dispose]: () => {
      if (previousBridge === undefined) delete window.t3JetBrains;
      else window.t3JetBrains = previousBridge;
    },
  };
  window.t3JetBrains = {
    protocolVersion: 1,
    projectPath: "/workspace/render",
    openFile: async () => {},
  };
  window.dispatchEvent(new Event("t3code:jetbrains-ready"));
  const client = window.t3JetBrainsClient;
  if (!client) throw new Error("The mounted composer did not attach the IDE bridge.");
  await act(() => {
    useComposerDraftStore.getState().setPrompt(draftId, "Explain this code");
    client.addSelection({
      projectPath: "/workspace/render",
      filePath: "/workspace/render/src/example.ts",
      text: "const selected = 1;",
      startLine: 3,
      endLine: 3,
      unsaved: true,
    });
  });
  await expect.element(page.getByTestId("composer-editor")).toMatchTextContent("Explain this code");
  await expect
    .element(page.getByTestId("composer-editor"))
    .toMatchTextContent('Unsaved editor snapshot: "src/example.ts", lines 3–3');
  await expect
    .element(page.getByTestId("composer-editor"))
    .toMatchTextContent("const selected = 1;");
  expect(dispatchedCommands()).toEqual([]);
  await _app.unmount();
  expect(window.t3JetBrainsClient).toBeUndefined();
});
