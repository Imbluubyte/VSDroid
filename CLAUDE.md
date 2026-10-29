# VSDroid – notes for Claude

VSDroid is a native Android, VS Code-style IDE for tablets (Kotlin + Jetpack Compose). Package `com.vsdroid.ide`.
The owner (GitHub: Imbluubyte) is not a Kotlin developer by trade and mostly supplies ideas and tests on a tablet;
Claude is the lead developer, so make decisions, explain briefly, and keep the app stable.

## Building and checking
- There is **no Android SDK in Claude's sandbox**, so code cannot be compiled there. GitHub Actions (`.github/workflows/build.yml`) is the real compile test:
  Gradle 8.9, JDK 17, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01, compileSdk 35, minSdk 30. Artifact: debug APK.
- Review every edit by reading it as a compiler would (imports, nullability, name clashes). Run a quick brace/paren balance check on changed files.
- When a build fails, read the log (`gh run view --log-failed` if available); otherwise the owner pastes it – only the `e: file:///...` lines matter.
- Signing: `app/build.gradle.kts` uses the keystore from the `VSDROID_KEYSTORE_*` env vars when set (CI decodes it from the `VSDROID_KEYSTORE_BASE64` secret), else the debug key. Never commit a keystore or passwords; never print secrets in workflow logs.
- Never leave the build broken on `main`. Work on a branch and let the owner merge, unless told otherwise.

## Layout (`app/src/main/java/com/vsdroid/ide/`)
- `MainActivity.kt` – permissions screen, root layout, hardware key dispatch (`dispatchKeyEvent` -> `Commands.handleKey`).
- `IdeState.kt` – all app state (tabs, project, search, python envs/packages, dialogs). `Dialog` sealed class lives here.
- `Commands.kt` – every action as a `Command(id, title, defaultShortcut)`; shortcuts are rebindable (state in `Command.shortcut`).
- `core/` – no UI: `Languages`, `Syntax`, `Completion`, `Search`, `Templates`, `EditorOps` (auto-indent, auto-close, backspace rules),
  `Settings` (settings.json), `Setup` (first-launch guide: Termux shell scripts, `SetupState`), `Themes` (theme keys, built-in Dark/Light, JSON parsing/validation, `ThemeManager` state), `TerminalSession`, `TermuxBridge`, `Venv`, `PackageIndex`,
  `Todo` (per-project to-do list, stored in `.vsdroid/todo.json`), `Diagnostics` + `DiagLexer`/`DiagPython`/`DiagBrace`/`DiagMisc`/`DiagData` (problem checker, pure Kotlin, tested with kotlinc on sample code; `ProblemsState.kt` runs it), `TermErrors` (terminal error to file:line), `Git` (pure Kotlin: shell-script builders + `git status` parser, no Android; tested against real git), `GitState` (Source Control state/actions, runs the scripts via `TermuxBridge`).
- `core/ext/` – the extension runtime, no Android (see Extensions below). `IdeExtensions.kt` (app package) connects it to the app.
- `core/Symbols*.kt` + `core/Intelli*.kt` – IntelliSense, pure Kotlin like the diagnostics (see IntelliSense below). `IntelliState.kt` (app package) is the glue: project `SymbolIndex`, `docFor(tab)`, go to definition, references, rename.
- `ui/` – `ProblemsPanel` (Problems tab), `Editor` (also draws the squiggles), `Sidebar` (explorer/search/env/package browser), `GitPanel` (Source Control tab), `Panels` (title bar, tabs, terminal), `Todo` (To-Do dropdown), `Overlays` (palette + dialogs), `Settings` (settings window with tabs), `Setup` (first-launch setup guide), `Theme` (`Vs` colour object, reads the active theme).

## Extensions
Lua (LuaJ `org.luaj:luaj-jse:3.0.1`) extensions, docs in `docs/EXTENSIONS.md`, examples in `examples/extensions/` (folder name = extension id).
- `core/ext/`: `MiniJson`, `Manifest` (permissions, `extension.json`, `Utf8Pos`), `LuaConv`, `Host` (`HostBridge` = everything an extension may do to the IDE), `ExtRuntime` (sandboxed Lua + the `vsd` API), `ExtensionManager` (lifecycle, approval/hash, events, providers, panels, runs everything on ONE background thread), `ExtInstaller` (.vsdext).
- `IdeExtensions.kt` implements `HostBridge`: hops to the main thread (never the other way round, so a stuck extension can't freeze the UI), owns the ask-dialog queue, cached `Command` objects for extension commands (`Commands.all = builtin + extension commands`), completion/diagnostic hooks. UI in `ui/Extensions.kt`.
- State: `VSDroid/extensions/`, `extension-data/`, `extensions.json` (approvals + SHA-256 of each folder). Master switch `extensionsEnabled` in settings.json.
- Test on a PC without Android: `tools/ext-tests/run.sh path/to/luaj-jse.jar` (kotlinc + java). Run it after ANY change in `core/ext/`; it also runs the example extensions.
- Rules: extension thread never calls into UI state directly; positions in the Lua API are 1-based UTF-8 byte offsets (`Utf8Pos`); keep the sandbox closed (new `vsd` functions must check `need(Permission...)`).

## Settings
`VSDroid/settings.json` in shared storage (`AppSettings`). Edited via the Settings screen (Ctrl+,) or by opening the file in VSDroid; saving it in the editor reloads it.
Shortcuts are stored as `commandId -> "Ctrl+Shift+P"`. New features that need options should add a field to `AppSettings` and a row to `ui/Settings.kt`.

## Setup guide
`ui/Setup.kt` (screens) + `core/Setup.kt` (`SetupScripts` = plain shell strings, `SetupState`). Opens when `settings.setupDone` is false; a settings.json from before the guide existed has no key and counts as done. Esc closes it for the session only; Finish or Skip sets `setupDone`. The Termux scripts are pure strings: test them with bash and fake `pkg`/`termux-setup-storage` commands (`SetupScripts.installScript` exits with `pkg install`'s code). To add a language, add a `SetupLanguage` (Termux package names, the command that proves it is installed).

## Terminal / Termux design
- `IdeState.terminals` holds the open `TerminalSession`s; `ide.terminal` is the shown one (so older code that says `ide.terminal.run(...)` still works). Venv and project-folder changes are applied to every session. `runCurrentFile` uses `terminalForRun()`, which opens a new terminal when the shown one is busy. Each Termux command gets its own `$HOME/.vsdroid/s/<time>-<counter>` folder, so concurrent sessions can't collide.
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

## IntelliSense
- `Symbols.scan(text, lang)` -> `Symbol`s (kind, container for members, `owner` for locals/params, body ranges). Scanners: `SymbolsPython` (indent frames), `SymbolsLua`, `SymbolsBrace` (+ `SymbolsBraceBase`) for Java/C#/Kotlin/JS/TS/C++. They reuse the diagnostics `Lexer`; when unsure they add nothing.
- `IntelliData` = built-in member tables per family (`py js cs java kt cpp lua`), `IntelliDoc` = types and members at an offset, `IntelliFeatures.kt` = completion / signature help / quick info / definitions, `IntelliRefs` = references and rename plans (compare owners by `start`, not identity: the index and the live doc scan separately).
- Feature parity across languages is deliberate; add a language by adding a scanner, a family in `IntelliData.family` and a table.
- Test without Android: compile `Languages.kt Diag*.kt Diagnostics.kt Symbols*.kt Completion.kt Intelli*.kt` with kotlinc plus a small main that runs samples (including the repo's own Kotlin files, which must not crash the scanner). Keep per-keystroke work cheap: `IntelliState.docFor` skips files over 120k characters.

## Problems / diagnostics
- Same categories for every language (see ROADMAP). Don't add advanced checks for one language only; import checks exist for languages with Python-like imports. Prefer missing a problem over a false alarm. Test changes by compiling `core/Diag*.kt` + `Languages.kt` with kotlinc and running sample files, including real project files (the repo's own Kotlin should give no errors).
- `knownGlobals` (settings.json) and the project word index (`ProjectIndex`) keep library and cross-file names from being reported.

## Gotchas we already hit (don't repeat)
- `IdeState` runs property initializers and `init` blocks in textual order. Anything that calls `openFolder()` (restoring the last project) must be in the LAST `init` of the class; calling it earlier crashed every launch after the first (to-do list not created yet). New state used by `openFolder` goes above that block.
- Value-based `BasicTextField` has no `softWrap`; don't pass it.
- A Kotlin property `fontSize` plus a function `setFontSize()` clash on the JVM; name functions differently (`changeFontSize`).
- Compose text layout lags one frame behind typing: check `layout.layoutInput.text.text == text` before `getCursorRect`/`getLineForOffset`.
- Key events in a separate window (`AlertDialog`, focusable `DropdownMenu`) do not reach `Activity.dispatchKeyEvent`. So dialogs are in-window overlays (`DialogFrame`/`VsDialog` in `ui/Overlays.kt`, like the palette and Settings screen), dropdown menus use `MenuPopup` (`focusable = false`) with their open state in `IdeState.openMenu`, and `Commands.dismissTopLayer()` handles Esc. Don't reintroduce `AlertDialog`; new menus should use `ide.openMenu` + `MenuPopup`.
- When splicing code with scripts, check you haven't duplicated declarations (a duplicated `showSide` once broke the build).
- Brace-balance checkers give false positives on char literals like `'{'`.

## Conventions
- Keep UI text free of device-specific wording (no tablet model names).
- All colours come from `Vs` in `ui/Theme.kt` (and `SyntaxColors`), which read the active theme (`ThemeManager.current`). Don't hard-code colours; add a `ThemeKey` (with dark and light defaults) in `core/Themes.kt` and a `Vs` getter instead. Anything that caches a colour in `remember` must key on `ThemeManager.current`. White text on the accent colour (buttons, badges) is the one allowed `Color.White`.
- Prefer small, self-contained files in `core/` that don't depend on Android where possible (easier to test).
- Update `README.md` and `ROADMAP.md` when behaviour or plans change.
- Commit messages: short imperative summary. Claude adds its Co-Authored-By trailer.

## Known issues
See `ROADMAP.md`.
