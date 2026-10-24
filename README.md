# VSDroid

A VS Code-style code editor built natively for Android tablets.
Kotlin + Jetpack Compose, no Electron, no WebView.

## Features

- VS Code Dark+ look and layout: title bar with menus, activity bar, side bar, tabs, breadcrumb, editor, terminal panel, status bar
- File explorer (create / rename / delete, long-press for the context menu)
- Syntax highlighting: Python, C#, Kotlin, Java, JavaScript/TypeScript, C/C++, Lua, Shell, JSON, YAML, TOML, CSS, HTML/XML, Markdown
- Word-level autocomplete: type `pri` in a Python file and get `print`, plus words already used in the file. Tab or tap accepts, Esc dismisses
- Search: keyword search across the project, and file-name search (plus Ctrl+P quick open)
- Terminal with two backends: Termux (full Linux tools) or the built-in Android shell
- Python virtual environments (create, activate, delete, pip install, requirements.txt)
- New project templates: Python, C# console / library / web API, Web, Node.js, C++ (CMake), empty
- Source Control tab (Ctrl+Shift+G): see changes, stage/unstage/discard, commit, push, pull, fetch, initialize a repository, add a remote, clone from GitHub, sign in with a personal access token. Uses `git` inside Termux
- Keyboard shortcuts (press F1 in the app for the full list)
- Auto-indent, auto-closing brackets and quotes, comment toggle, move/duplicate/delete line

Not included, by design: extensions, debugging.

## Building the APK (no PC needed)

1. Create a GitHub repository and upload this folder (keep the `.github` folder).
2. Open the repo's **Actions** tab, run **Build APK** (it also runs on every push).
3. When it finishes, download the **VSDroid-debug-apk** artifact, unzip it, and install `app-debug.apk` on the tablet.

Locally: `gradle assembleDebug` (JDK 17 and the Android SDK required).

## First run

1. Grant "All files access". Projects live in `Internal storage/VSDroidProjects`.
2. For the terminal and Python venvs, set up Termux (install it from F-Droid or GitHub, not the Play Store):
   ```
   echo "allow-external-apps=true" >> ~/.termux/termux.properties
   termux-setup-storage
   pkg install python git
   ```
   Close Termux completely, reopen it once, then in VSDroid tap "Grant Termux permission".
3. Without Termux the terminal falls back to the basic Android shell (ls, cat, etc., but no Python or dotnet).

## Git and GitHub

Open the Source Control tab (branch icon, or Ctrl+Shift+G). Git runs inside Termux, so run `pkg install git` there once.

- **Sign in:** in the GitHub section tap *Sign in to GitHub* and enter your username and a personal access token (github.com > Settings > Developer settings > Personal access tokens, with the `repo` permission). VSDroid checks the token with GitHub, then stores it in Termux's private home folder (`~/.git-credentials`), not in your projects or `settings.json`. *Sign out* removes it.
- **Commit identity:** set your name and email once (saved with `git config --global`).
- **Clone:** *Clone a repository* (also Git > Clone Repository) takes `https://github.com/user/repo` or just `user/repo` and clones into `VSDroidProjects`, then opens it.
- **New project to GitHub:** *Initialize repository*, create an empty repo on github.com, *Add remote*, commit, then *Push*.
- Shared storage belongs to a different user than Termux, so VSDroid automatically adds each project to git's `safe.directory` list (this avoids the "dubious ownership" error).
- Pull only fast-forwards. If your branch and the remote have diverged, VSDroid says so and you resolve it in the terminal. SSH keys, branch switching and diffs are not built in yet.

## Notes and limits

- The terminal runs one command at a time. Programs can read input while running (Python `input()`, C# `Console.ReadLine()`, Java `Scanner`, Node `readline`): the prompt appears in the terminal and you type your answer. C, C++ and Lua only show a prompt if they flush output (`fflush(stdout)` / `io.stdout:flush()`). Ctrl+D and full-screen programs like vim are not supported.
- Python virtual environments are stored in Termux's home folder, because shared storage cannot hold symlinks or executables.
- Running C# needs the .NET SDK installed in Termux (`pkg install dotnet-sdk-8.0`, availability depends on Termux's repos).
- Undo/redo works with a hardware keyboard (Ctrl+Z / Ctrl+Shift+Z); programmatic edits such as comment toggle are not part of the undo history.

## Building in AndroidIDE

Open the project folder in AndroidIDE and run `./gradlew assembleDebug` (or use its Build button). It needs
JDK 17 and the Android 35 platform installed through AndroidIDE's SDK manager. The first run downloads
Gradle 8.9, so keep Wi-Fi on. If the zip lost the executable bit, run `chmod +x gradlew` first.

## Settings

Open with Ctrl+, (or File > Open Settings). Settings live in `VSDroid/settings.json` in shared storage; you can edit them in the Settings screen or open the file in VSDroid and save it (changes apply immediately). Keyboard shortcuts can be re-recorded there, and the shortcuts list (F1) always shows the current ones. F2 renames the selected file.
