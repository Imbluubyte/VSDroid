# VSDroid roadmap

## Before the public release
- [x] **Git/GitHub tab** in the sidebar (status, stage, commit, push/pull, clone; via Termux `git`; auto `safe.directory` fix) – done, see below
- [x] **Consistent APK signing** (a fixed release keystore stored as GitHub secrets, so builds can update over each other) – done, see below
- [x] **Custom themes**: built-in Light, user themes from JSON files, theme picker + create/import in Settings > Appearance – done. Not done yet (ideas): bundled Dracula / Catppuccin Mocha theme files
- [ ] **Custom templates** (user-defined project templates; ties in with the setup guide)
- [x] **To-Do list dropdown** per project in the top bar (stored in the project, e.g. `.vsdroid/todo.json`)
- [x] **Settings button in the top bar** (its own button, out of the File menu)
- [x] **Esc closes the open menu/dialog**, with save prompts where needed
- [x] **First-launch setup guide**: Termux install and connection, language install, theme, Git/GitHub – done, see below. Not done yet (ideas): install progress for long downloads, detecting a Termux that needs `pkg upgrade`
- [x] **Browse device files to open a project** – done: File > Browse for Folder uses the system folder picker (Internal storage and SD cards; cloud locations are refused with a message)
- [ ] **Thorough code scan for bugs** (review pass over every file, add tests for editing logic, crash log screen)
- [x] **Choose a licence** – decided: GPLv3 (forks allowed only under the same licence, with source)
- [ ] **Add the GPLv3 `LICENSE` file** and a licence line in the README before the repo goes public
- [ ] Repo polish: README with screenshots, release workflow (tagged APKs on GitHub Releases), issue templates

- [x] **Debugging update**: problem underlines, Problems tab, quick fixes, terminal-error underlines, F11 full screen – done, see below. Not done yet (ideas): type checking, a real debugger (breakpoints, stepping), JSX/TSX, "fix all" for a whole file

## After the public release
- [ ] Extension ideas: an extension gallery/registry, more panel elements (tables, progress), editor decorations, language servers

## Known bugs / to investigate
- [ ] The first `dotnet run` in a brand-new C# project is very slow or appears to hang (restore/first-run work; the terminal prints nothing meanwhile). Existing, already-built projects run fine.

## Done
- **Extension API**: Lua extensions (LuaJ) with permissions, sandbox and time limits; commands, side panels, problem checks, completions, formatters, languages, themes, events, storage, files, terminal/process, HTTP. Docs in `docs/EXTENSIONS.md`, examples in `examples/extensions/`, tests in `tools/ext-tests/`
- Dark+ layout, explorer, tabs, syntax highlighting, word autocomplete, file and keyword search
- Termux or Android-shell terminal with interactive input (`input()`, `Console.ReadLine()`, ...)
- Python venvs and a package browser (shown only for projects containing .py files)
- Project templates (C# template uses `class Program` / `Main`)
- Settings window (General / Keybinds / Appearance tabs) + `settings.json`, rebindable keyboard shortcuts, F1 shortcut list, F2 rename
- Backspace removes a whole indent level; auto-close pairs are deleted together
- Run support for Python, C#, Java, JavaScript, TypeScript, Kotlin, C/C++, Lua, shell
- Renamed from TabCode to VSDroid
- Esc closes the topmost menu, dialog, command palette or settings screen (dialogs are now in-window overlays; the unsaved-changes prompt treats Esc as Cancel, so nothing is lost). Gear button for Settings in the top bar, and the General settings rows no longer carry hint text
- Fixed-key APK signing: CI signs with a keystore from GitHub secrets (setup in README, "Release signing"), falls back to the debug key without them, and checks the secret and password early with clear errors
- Git/GitHub Source Control tab (Ctrl+Shift+G): branch and ahead/behind, stage/unstage/discard, commit, push/pull/fetch, init, add remote, clone, GitHub sign-in with a personal access token, commit identity, automatic `safe.directory` fix. Runs `git` inside Termux. Not done yet (ideas): branch switching, diffs, merge-conflict helpers, SSH keys
- Visuals update: Settings is a floating window with a tab list and an X close icon; Appearance tab with Dark/Light themes, user JSON themes (new from template, import with validation); the Run button is an icon-only button shown only when the open file can be run; F1 shortcut list no longer has a footer note
- First-launch setup guide (opens on a fresh install; Help > Setup Guide reopens it): Termux install check with F-Droid / GitHub links and an "Already installed" button, a copy-and-paste Termux setup command with a connection test, one-tap language installs (Python, JS/TS, C/C++, Lua, C#, Java, Kotlin) with per-language results, theme picker, Git install, commit identity and GitHub sign-in. Existing installs with a settings.json do not see it automatically
- Browse for Folder (system folder picker, path conversion in `core/StoragePaths.kt`) and multiple terminals (tab strip, New/Close Terminal commands, Ctrl+Shift+`)
- Debugging update: `core/Diag*.kt` is a pure-Kotlin, token-based checker (conservative: when unsure it stays quiet). Every language gets the same categories: unterminated strings/comments and bracket errors, language-specific syntax errors (missing `;`, missing `:`, `=` in a condition, missing commas), undefined names with "did you mean" fixes, unreachable code, `break`/`continue` outside a loop and unused locals. Languages with Python-like imports (Python, JS/TS `import`/`require`, Java/Kotlin `import`, C# `using`, C/C++ `#include`, Lua `require`) also get unused-import and "only a few members used" detection. `ProblemsState` scans the project in the background; the editor draws squiggles and shows the message with a quick-fix button; terminal errors (`core/TermErrors.kt`) are underlined on the file and line the output names
- Fixed the crash on every launch after the first: `IdeState` reopened the last project from an early `init` block, before the to-do list (declared further down) existed. That restore now runs in the last `init` of the class, and a failure there only forgets the last project instead of crashing
