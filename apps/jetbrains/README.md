# T3 Code for WebStorm

The plugin runs T3 Code in a tool window. It shares one background server with the desktop
app, so both show the same projects, threads, approvals, and checkpoints. It can connect or
start that server without opening the desktop window.

## Install and connect

1. In WebStorm 2026.3 or later, open **Settings → Plugins → gear → Install Plugin from Disk**.
   Download the plugin ZIP from the fork's matching GitHub Release and select it, then restart
   if prompted. For a local build, select `build/distributions/t3-code-jetbrains-0.4.0.zip`.
2. Open **View → Tool Windows → T3 Code** and click **Connect**. The plugin
   finds a T3 CLI with shared-server support, reuses the server for your T3 home or starts
   one in the background, and pairs the panel automatically.
3. If no runtime is found, open the toolbar's **…** menu and use **Server setup** to choose `t3.exe` (or `t3` on macOS/Linux).
   You can also select a Node/Electron executable and an absolute path to a built T3 CLI
   script such as `apps/server/dist/bin.mjs`. The installed desktop CLI's known Windows shim
   is supported and resolves to its bundled runtime without opening the desktop app.
4. Open or create a T3 thread whose workspace is the directory open in WebStorm. If the thread
   uses a separate Git worktree, open that worktree as your IDE project instead.

Use the desktop app from the same release, or a server and desktop app built from this
checkout. Older builds do not support the
`t3 shared` command or shared client authentication. Update both surfaces together.

## Shared background server

The default T3 home is `~/.t3`, the same as the desktop app. Existing desktop threads appear
automatically; messages and changes are live in both clients because they use the same
environment. Provider CLIs use your machine installation and their usual sign-in.

If no server is running, the plugin starts one on loopback on an available port. The desktop
app reuses it when opened. **Disconnect**, closing a project, quitting WebStorm, and quitting
the desktop app leave the background server running. It starts again when a client connects
after a reboot; this feature does not configure sign-in startup.

Enable automatic connection in **Server setup** to connect when the project's panel opens.
If you use a custom desktop `T3CODE_HOME`, select that same absolute directory as the plugin's
**Shared T3 home**. Windows and WSL homes are separate; a Windows plugin does not automatically
attach to the desktop's WSL backend. You can still connect to its URL manually.

Threads created by plugin 0.2.0 remain under `~/.t3-jetbrains/<project-id>/`; they are not
automatically merged. Select that old directory as the shared T3 home to continue using it,
and launch the desktop with the same `T3CODE_HOME` if both clients should see those threads.

To stop the background server explicitly, run `t3 shared --stop`. For a custom home, add
`--base-dir "C:\path\to\home"`. Stopping interrupts all clients and any running turns.
Stop it before upgrading its runtime. Changing desktop sharing settings also restarts it;
click **Connect** again in the IDE afterward.

Pairing credentials are hidden from **Server log** and are not stored in IDE settings.
The full server log lives at `<T3 home>/userdata/logs/shared-server.log`.

To connect to a server you run separately, choose **Connect to URL** from the toolbar's **…**
menu and paste its browser URL or a fresh pairing URL. Complete T3's normal pairing flow.

Select code and use **Send Selection to T3 Code** from the editor's context menu, **Tools**, or
**Find Action**. The panel's **Send selection** button does the same. The code, relative file path,
and line range are appended to the current draft; you review and send it yourself.
Unsaved text is included as an explicitly marked editor snapshot. Sharing never saves your files.

File links and files opened from T3's diff view navigate into the IDE when they belong to this
project and the connected local environment. Other environments keep their usual T3 behavior.

This first version supports local editor integration through `localhost`, `127.0.0.1`, or `::1`.
Remote T3 servers can be used in the panel, but do not receive IDE selections or open local files.
JetBrains Remote Development / Gateway integration is not implemented yet.

Only the server origin is persisted in your IDE workspace settings. Pairing tokens and URL
fragments are used for the current connection and are not saved there.

## Build

Use a Java 25 JDK and run these commands from `apps/jetbrains`:

```powershell
.\gradlew.bat test buildPlugin
```

The build downloads WebStorm 2026.3 by default. To use an existing installation:

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
.\gradlew.bat "-PplatformPath=$env:LOCALAPPDATA\Programs\WebStorm" test buildPlugin
```

The lifecycle tests use Node to run an isolated fixture server. `runIde` launches an isolated
IDE sandbox with the plugin installed. The plugin does not install or upgrade a runtime or
configure provider CLIs.

To build a matching server from this repository, run `vp run --filter t3 build` at the repository
root. In **Server setup**, choose your Node executable and the resulting absolute
`apps/server/dist/bin.mjs` path.
