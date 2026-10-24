# VSDroid – notes for Claude

VSDroid is a native Android, VS Code-style IDE for tablets (Kotlin + Jetpack Compose). Package `com.vsdroid.ide`.
The owner (GitHub: Imbluubyte) is not a Kotlin developer by trade and mostly supplies ideas and tests on a tablet;
Claude is the lead developer, so make decisions, explain briefly, and keep the app stable.

## Building and checking
- There is **no Android SDK in Claude's sandbox**, so code cannot be compiled there. GitHub Actions (`.github/workflows/build.yml`) is the real compile test:
  Gradle 8.9, JDK 17, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01, compileSdk 35, minSdk 30. Artifact: debug APK.
- Review every edit by reading it as a compiler would (imports, nullability, name clashes). Run a quick brace/paren balance check on changed files.
- When a build fails, read the log (`gh run view --log-failed` if available); otherwise the owner pastes it – only the `e: file:///...` lines matter.
- Never leave the build broken on `main`. Work on a branch and let the owner merge, unless told otherwise.

## Layout (`app/src/main/java/com/vsdroid/ide/`)
- `MainActivity.kt` – permissions screen, root layout, hardware key dispatch (`dispatchKeyEvent` -> `Commands.handleKey`).
- `IdeState.kt` – all app state (tabs, project, search, python envs/packages, dialogs). `Dialog` sealed class lives here.
- `Commands.kt` – every action as a `Command(id, title, defaultShortcut)`; shortcuts are rebindable (state in `Command.shortcut`).
- `core/` – no UI: `Languages`, `Syntax`, `Completion`, `Search`, `Templates`, `EditorOps` (auto-indent, auto-close, backspace rules),
  `Settings` (settings.json), `TerminalSession`, `TermuxBridge`, `Venv`, `PackageIndex`,
  `Git` (pure Kotlin: shell-script builders + `git status` parser, no Android; tested against real git), `GitState` (Source Control state/actions, runs the scripts via `TermuxBridge`).
- `ui/` – `Editor`, `Sidebar` (explorer/search/env/package browser), `GitPanel` (Source Control tab), `Panels` (title bar, tabs, terminal, status bar), `Overlays` (palette + dialogs), `Settings` (settings screen), `Theme` (`Vs` colour object).

## Settings
`VSDroid/settings.json` in shared storage (`AppSettings`). Edited via the Settings screen (Ctrl+,) or by opening the file in VSDroid; saving it in the editor reloads it.
Shortcuts are stored as `commandId -> "Ctrl+Shift+P"`. New features that need options should add a field to `AppSettings` and a row to `ui/Settings.kt`.

## Terminal / Termux design
- Backends: Termux (preferred) via the `RUN_COMMAND` intent, or a plain Android shell fallback.
- Termux cannot give a live TTY, so a command is started detached: stdin from a FIFO, stdout/stderr to a log file in `$HOME/.vsdroid/s/<id>/`, exit code + final cwd written to files. VSDroid polls the log and writes typed lines to the FIFO. Text without a trailing newline is shown as the input prompt (`partial`).
- Shared storage (`/storage/emulated/0`) is mounted **noexec** and has no symlinks. Anything executable (venvs, compiled binaries, .NET apphost, cargo target) must live under Termux `$HOME`. The terminal script sets `UseAppHost=false`, `UseSharedCompilation=false`, `TMPDIR`, `CARGO_TARGET_DIR` for this reason.
- Projects live in `/storage/emulated/0/VSDroidProjects` (needs MANAGE_EXTERNAL_STORAGE). Venvs live in Termux home.
- Known limits: no Ctrl+D/EOF, no full-screen programs (vim), C/C++/Lua need explicit flush to show prompts.

## Git design
- All git runs in Termux through `GitState.exec` (one command at a time, guarded by a Mutex). Every script starts with `Git.enter`: `cd` to the project, add the repo's top folder to `safe.directory`, set `GIT_TERMINAL_PROMPT=0` so git never waits for a password.
- The project folder may be a sub-folder of the repo; scripts `cd` to the repo top, and `GitChange.path` is relative to that top (`GitState.top`).
- GitHub login = a personal access token stored by git's `store` helper in Termux `~/.git-credentials` (never in settings.json). Only the exit code/`@@VSD_CODE` marker comes back; the token is not echoed or logged.
- `Git.kt` can be compiled and exercised without Android (kotlinc + a script that runs the generated scripts against a throw-away repo with a temp `HOME`).

## Gotchas we already hit (don't repeat)
- Value-based `BasicTextField` has no `softWrap`; don't pass it.
- A Kotlin property `fontSize` plus a function `setFontSize()` clash on the JVM; name functions differently (`changeFontSize`).
- Compose text layout lags one frame behind typing: check `layout.layoutInput.text.text == text` before `getCursorRect`/`getLineForOffset`.
- Key events in an `AlertDialog` do not reach `Activity.dispatchKeyEvent`; screens that must record keys are overlays inside the main window (see Settings screen).
- When splicing code with scripts, check you haven't duplicated declarations (a duplicated `showSide` once broke the build).
- Brace-balance checkers give false positives on char literals like `'{'`.

## Conventions
- Keep UI text free of device-specific wording (no tablet model names).
- Dark+ style colours come from `Vs` in `ui/Theme.kt`; don't hard-code colours elsewhere (themes are planned).
- Prefer small, self-contained files in `core/` that don't depend on Android where possible (easier to test).
- Update `README.md` and `ROADMAP.md` when behaviour or plans change.
- Commit messages: short imperative summary. Claude adds its Co-Authored-By trailer.

## Known issues
See `ROADMAP.md`.
