# VSDroid roadmap

## Before the public release
- [x] **Git/GitHub tab** in the sidebar (status, stage, commit, push/pull, clone; via Termux `git`; auto `safe.directory` fix) – done, see below
- [x] **Consistent APK signing** (a fixed release keystore stored as GitHub secrets, so builds can update over each other) – done, see below
- [x] **Custom themes**: built-in Light, user themes from JSON files, theme picker + create/import in Settings > Appearance – done. Not done yet (ideas): bundled Dracula / Catppuccin Mocha theme files
- [ ] **Custom templates** (user-defined project templates; ties in with the setup guide)
- [ ] **To-Do list dropdown** per project in the top bar (stored in the project, e.g. `.vsdroid/todo.json`)
- [x] **Settings button in the top bar** (its own button, out of the File menu)
- [x] **Esc closes the open menu/dialog**, with save prompts where needed
- [x] **First-launch setup guide**: Termux install and connection, language install, theme, Git/GitHub – done, see below. Not done yet (ideas): install progress for long downloads, detecting a Termux that needs `pkg upgrade`
- [x] **Browse device files to open a project** – done: File > Browse for Folder uses the system folder picker (Internal storage and SD cards; cloud locations are refused with a message)
- [ ] **Thorough code scan for bugs** (review pass over every file, add tests for editing logic, crash log screen)
- [x] **Choose a licence** – decided: GPLv3 (forks allowed only under the same licence, with source)
- [ ] **Add the GPLv3 `LICENSE` file** and a licence line in the README before the repo goes public
- [ ] Repo polish: README with screenshots, release workflow (tagged APKs on GitHub Releases), issue templates

## After the public release
- [ ] **Extension API** (very low priority). Will be similar to VSCode, this is a huge thing to implement so it'll take a while and we'll leave it until all core features are done

## Known bugs / to investigate
- [ ] The first `dotnet run` in a brand-new C# project is very slow or appears to hang (restore/first-run work; the terminal prints nothing meanwhile). Existing, already-built projects run fine.

## Done
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
