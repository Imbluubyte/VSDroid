# VSDroid roadmap

## Before the public release
- [ ] **Git/GitHub tab** in the sidebar (status, stage, commit, push/pull, clone; via Termux `git`; auto `safe.directory` fix)
- [ ] **Consistent APK signing** (a fixed release keystore stored as a GitHub secret, so builds can update over each other)
- [ ] **Custom themes** (built-in Light, Dracula, Catppuccin Mocha etc; user themes from JSON files; theme picker in Settings)
- [ ] **Custom templates** (user-defined project templates; ties in with the setup guide)
- [ ] **To-Do list dropdown** per project in the top bar (stored in the project, e.g. `.vsdroid/todo.json`)
- [ ] **Settings button in the top bar** (its own button, out of the File menu)
- [ ] **Esc closes the open menu/dialog**, with save prompts where needed
- [ ] **First-launch setup guide**: install Termux from F-Droid ("Already installed" button), enable Termux integration, install packages for chosen languages (with skip), choose a theme, then Git/GitHub setup
- [ ] **Browse device files to open a project** (folder picker instead of typing a path)
- [ ] **Thorough code scan for bugs** (review pass over every file, add tests for editing logic, crash log screen)
- [ ] **Choose a licence** – the owner wants it to be open source but not redistributed. Note: OSI "open source" licences always allow redistribution, so pick between:
  copyleft (GPLv3/AGPL: forks allowed only under the same licence with source), a source-available licence (e.g. PolyForm Noncommercial, BSL), or a permissive licence plus trademark rules on the VSDroid name/logo.
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
- Settings screen + `settings.json`, rebindable keyboard shortcuts, F1 shortcut list, F2 rename
- Backspace removes a whole indent level; auto-close pairs are deleted together
- Run support for Python, C#, Java, JavaScript, TypeScript, Kotlin, C/C++, Lua, shell
- Renamed from TabCode to VSDroid
