# Writing VSDroid extensions

Extensions are small programs written in **Lua 5.2** that add features to VSDroid: commands, side panels, problem checks,
completions, formatters, languages, themes and more. They run in a sandbox, only after the user has approved what they
may do, and they cannot freeze the app.

- [Quick start](#quick-start)
- [What an extension is](#what-an-extension-is)
- [Permissions and safety](#permissions-and-safety)
- [API reference](#api-reference)
- [Panels](#panels)
- [Positions and text](#positions-and-text)
- [Limits](#limits)
- [Packaging and installing](#packaging-and-installing)
- [Examples](#examples)

## Quick start

1. Create a folder called after your extension's id inside `VSDroid/extensions/` (shared storage, next to `settings.json`):
   `VSDroid/extensions/yourname.hello/`
2. Add `extension.json`:

   ```json
   {
     "id": "yourname.hello",
     "name": "Hello",
     "version": "1.0.0",
     "description": "Says hello.",
     "author": "You",
     "apiVersion": 1,
     "permissions": []
   }
   ```
3. Add `main.lua`:

   ```lua
   vsd.commands.register("hello", {
     title = "Say hello",
     run = function() vsd.toast("Hello from my extension!") end,
   })
   ```
4. Open the **Extensions** tab (Ctrl+Shift+X), tap **Review & turn on** and allow it. Then run *Hello: Say hello* from the
   command palette (Ctrl+Shift+P).

While developing, switch on **Reload when its files change** on the extension's card: after you edit and save, the
extension restarts by itself. (Normally an extension whose files changed must be approved again.)
A runtime error never crashes the app; it shows on the card and in its **Log**.

## What an extension is

A folder (or a `.vsdext` file, which is that folder zipped) containing:

| File | Purpose |
| --- | --- |
| `extension.json` | Required. Describes the extension. |
| `main.lua` | The program (a different file can be named with `"main"`). |
| other `.lua` files | Load them with `require("name")` (or `require("sub.name")`). Only files in your own folder can be required. |
| theme `.json` files | Listed in `"themes"`. |

`extension.json` fields:

| Field | Required | Notes |
| --- | --- | --- |
| `id` | yes | 2-64 characters: lower-case letters, digits, `.`, `-`, `_`. **The folder must have exactly this name.** Use `yourname.thing`. |
| `name`, `version` | yes | Shown in the Extensions tab. |
| `apiVersion` | yes | Currently `1`. An extension that needs a newer API is refused with a clear message. |
| `description`, `author`, `homepage` | no | |
| `main` | no | Default `main.lua`. |
| `permissions` | no | List of permission ids, see below. |
| `themes` | no | Up to 20 theme files, same format as user themes (see the README). They appear in Settings > Appearance. |

Lua code runs once when the extension starts. Everything useful happens through the `vsd` table. Callbacks you register run
later, one at a time, on a background thread.

## Permissions and safety

Before anything runs, the user sees the list of permissions and has to agree. Sensitive ones are highlighted.

| Id | Allows | Sensitive |
| --- | --- | --- |
| `editor.read` | Read the open file's text, cursor and language. Also used by completion and problem providers' text. | no |
| `editor.write` | Insert, replace and format text in the open file. | no |
| `files.read` | Read and list files inside the open project; open them in the editor. | no |
| `files.write` | Create, change and delete files inside the open project. | **yes** |
| `terminal` | Run commands (in the terminal, or in the background through Termux or the Android shell). | **yes** |
| `network` | HTTP requests. | **yes** |

No permission is needed for: commands, panels, dialogs, timers, events, storage, `json`, the extension's own data folder
(`@data/...`) and reading its own folder (`@ext/...`).

Safety measures built in:

- **Sandbox.** There is no `io`, `os.execute`, `debug`, `dofile`, `loadfile`, `package` or `luajava`. `os` only has `time`, `date`
  and `clock`. `load` only reads text, never compiled code.
- **Project fences.** File access is limited to the open project (and your own data folder). `..` tricks, symlinks that
  point outside, and `.git` folders are refused.
- **Time limits.** Commands may run 10 s, event handlers 5 s, providers (completion, problems, formatters) 3 s. An
  extension that exceeds its limit is interrupted and the extension keeps working. Endless loops, endless recursion and
  out-of-memory are caught.
- **Never blocks the app.** Extensions run on their own thread. Anything slow (HTTP, processes) is asynchronous with a callback.
- **Tamper check.** The extension's files are fingerprinted when approved. If any file changes, or a new permission is
  added, it has to be approved again (unless developer reload is on, which still never grants new permissions).
- **Master switch.** Settings > Extensions turns all of them off.

## API reference

Everything lives in the global `vsd` table. Functions that can fail while doing I/O return `nil, "message"` instead of
raising an error. Using something you have no permission for raises a Lua error that names the permission.

### Basics

| Function | Description |
| --- | --- |
| `vsd.api_version` | The API version of this VSDroid (a number). |
| `vsd.extension` | `{ id, name, version, dir, data_dir }` |
| `vsd.log(...)`, `vsd.warn(...)`, `vsd.error(...)` | Write to the extension's log (Extensions tab). `print` does the same. |
| `vsd.toast(text)` | Short message at the bottom of the screen. |
| `vsd.prompt(title, [options], callback)` | Asks for text. `options` = `{ placeholder = "", value = "" }`. `callback(text)` gets `nil` if cancelled. |
| `vsd.confirm(title, message, callback)` | `callback(true/false)`. |
| `vsd.pick(title, items, callback)` | Pick from a list of strings. `callback(index, label)` (1-based), both `nil` if cancelled. |
| `vsd.timeout(ms, fn)` / `vsd.interval(ms, fn)` | Run later / repeatedly (minimum 100 ms). Return an id. |
| `vsd.cancel(id)` | Stop a timer. |

### Commands

```lua
vsd.commands.register("my-command", {
  title = "Do the thing",      -- shown as "<Extension name>: Do the thing"
  shortcut = "Ctrl+Alt+T",     -- optional; the user can rebind it, and conflicts disable it
  run = function() ... end,
})
vsd.commands.execute("my-command")      -- run one of your own commands
vsd.commands.builtin()                  -- list of { id =, title = } for the IDE's own commands
```

### Events

```lua
local id = vsd.events.on("file.saved", function(path) ... end)
vsd.events.off(id)
```

| Event | Argument |
| --- | --- |
| `app.ready` | none; fired once at start |
| `project.opened`, `project.closed` | project folder path |
| `file.opened`, `file.saved`, `file.closed` | file path |
| `editor.activated` | file path of the tab that was shown |
| `editor.changed` | file path; delayed until typing pauses (about 0.5 s) |
| `terminal.finished` | exit code |
| `unload` | none; the extension is being stopped (keep it quick) |

If a project was already open when VSDroid started, `app.ready` is your cue; ask `vsd.project.root()`.

### Editor (`editor.read` / `editor.write`)

| Function | Description |
| --- | --- |
| `vsd.editor.path()`, `.language()` | Path and language id (`python`, `lua`, ...) of the shown file, or `nil`. |
| `vsd.editor.text()` | The full text. |
| `vsd.editor.line_count()`, `.line_text(n)` | |
| `vsd.editor.cursor()` | Cursor position (byte index, see [Positions](#positions-and-text)). |
| `vsd.editor.line()` | Cursor line (1-based). |
| `vsd.editor.selection()` | `first, last` of the selection (empty selection: `last == first - 1`). |
| `vsd.editor.selected_text()` | |
| `vsd.editor.replace(first, last, text)` | Replace bytes `first..last` (like `string.sub`). `replace(i, i - 1, s)` inserts before `i`. Returns `true`, or `false` if the file changed under you. *(write)* |
| `vsd.editor.insert(text)` | Replace the selection / insert at the cursor. *(write)* |
| `vsd.editor.set_text(text)` | Replace everything. *(write)* |
| `vsd.editor.goto_line(n)` | Move the cursor. *(read)* |
| `vsd.editor.open(path, [line])` | Open a project file. *(files.read)* |

### Project and files

| Function | Description |
| --- | --- |
| `vsd.project.root()`, `.name()` | Folder path / name of the open project (`nil` if none). |
| `vsd.project.files({ ext = "py", dir = "src", max = 500 })` | Relative paths of project files. *(files.read)* |
| `vsd.fs.read(path)` | Text of a file, or `nil, err`. |
| `vsd.fs.write(path, text)` | `true` or `nil, err`. Creates folders as needed. *(files.write)* |
| `vsd.fs.exists(path)`, `.is_dir(path)`, `.list(path)` | `list` gives names, folders end with `/`. |
| `vsd.fs.mkdir(path)`, `.remove(path)` | *(files.write)* |

Paths are relative to the project, except two special prefixes that need no permission: `@data/...` is your private,
writable folder; `@ext/...` is your own extension folder (read only).

### Storage and JSON

```lua
vsd.storage.set("count", 3)        -- numbers, text, booleans, tables of those; nil deletes (1 MB per extension)
vsd.storage.get("count", 0)        -- second argument is the default
vsd.storage.keys()
vsd.json.encode(table, [pretty])   -- text
vsd.json.decode(text)              -- value, or nil + error message
```

### Terminal, processes, network

```lua
vsd.terminal.run("python main.py", [cwd])          -- shows it in the terminal (terminal)
vsd.process.exec("git status -s", { cwd = ".", timeout = 30000 }, function(r)
  -- r = { code =, stdout =, stderr =, error = (only if it could not start / timed out) }
end)                                                -- runs in the background through Termux (terminal)
vsd.http.get(url, function(r) ... end)              -- r = { status =, body =, headers =, truncated =, error = }
vsd.http.post(url, body, function(r) ... end)
vsd.http.request({ url = ..., method = "PUT", headers = { ... }, body = "...", timeout = 15000 }, function(r) ... end)
```

At most 4 commands run at once. Output is capped at 1 MB. Callbacks arrive later, never inside the call.

### Providers

All provider functions receive one table `ctx` and are time limited (3 s). `languages` is a language id, a list of ids, or
omitted/`"*"` for all.

**Problems** (underlines in the editor and entries in the Problems tab; runs after the file changes and typing pauses):

```lua
vsd.diagnostics.register({
  languages = { "python" },
  check = function(ctx)    -- ctx.path, ctx.name, ctx.language, ctx.text
    return {
      { line = 3, col = 5, end_col = 9, severity = "warning" --[[ or "error" ]], message = "Something is off", code = "my-rule" },
    }                      -- line is 1-based; col/end_col are 1-based byte columns, end_col optional
  end,
})
```

**Completion** (words offered while typing, after at least 2 characters; the IDE keeps those that start with the typed prefix):

```lua
vsd.completion.register({
  languages = { "lua" },
  provide = function(ctx)  -- ctx.path, language, text, prefix, cursor, line, col, line_text
    return { "word1", { text = "word2" } }
  end,
})
```

**Formatter** (*Edit: Format Document*, Shift+Alt+F; needs `editor.write`). Return the new full text, or `nil` to do nothing.
The first formatter for the file's language that changes it wins.

```lua
vsd.formatters.register({ languages = "*", format = function(ctx) return ctx.text end })
```

### Languages

```lua
vsd.languages.register({
  id = "myLang", name = "My Language",
  extensions = { "ml" }, filenames = { "Mlfile" },
  keywords = { "if", "then" }, types = { "int" }, builtins = { "print" },
  line_comment = "#", block_comment = { "/*", "*/" },
  run = "mylang {file}",      -- optional "Run" command; only honoured with the terminal permission
})
```

The language id is lower-cased. A language you register wins over a built-in language for the same file extension.

## Panels

A panel is a page in the side bar with its own icon. You describe it with plain Lua tables, and the IDE draws it.

```lua
vsd.panels.register("main", {
  title = "My panel",
  icon = "check",            -- list, check, code, search, bug, folder, settings, terminal, star, bookmark, build, doc
  render = function()        -- return the tree; called when shown and after refresh/actions
    return { type = "column", children = {
      { type = "text", text = "Hello", style = "title" },
      { type = "button", label = "Add", action = "add" },
    } }
  end,
  on_action = function(action, payload) ... end,   -- a button, list row, checkbox or input was used
})
vsd.panels.refresh("main")   -- draw again (e.g. after data changed)
vsd.panels.show("main")      -- bring the panel to the front
```

Element types:

| `type` | Fields |
| --- | --- |
| `column`, `row` | `children = { ... }` (or put the children directly in the table) |
| `text` | `text`, `style` = `title`, `bold`, `dim`, `mono`, `accent`, `error`, `warning`, `success` |
| `button` | `label`, `action`, `payload` |
| `input` | `id`, `value`, `placeholder`, `action`: when the user presses Done, `on_action(action, typedText)` |
| `checkbox` | `label`, `checked`, `action`: `on_action(action, "true"/"false")` |
| `list` | `items = { "text", { label =, detail =, action =, payload = } }`; tapping a row with an `action` calls `on_action(action, payload)` |
| `divider`, `spacer` | |

A bare string in place of an element is a `text`. Limits: 2000 elements, 14 levels deep. A mistake in the tree shows an
error message inside the panel (and in the log) instead of crashing.

## Positions and text

Text is exchanged as **UTF-8**, and positions in the editor API, in `ctx.cursor`, and in problem columns are **1-based byte
positions**, exactly what Lua's `string.sub`, `string.find` and `#` give. So this just works, even with accents or emoji:

```lua
local text = vsd.editor.text()
local s, e = text:find("TODO", 1, true)
if s then vsd.editor.replace(s, e, "DONE") end
```

A change is applied only if the open file still has the text you read; otherwise the call returns `false` and you can try again.

## Limits

| What | Limit |
| --- | --- |
| Package (.vsdext) | 8 MB, 400 files, 24 MB unpacked |
| One file read/written through `vsd.fs` | 5 MB |
| Text passed to the editor | 2 MB |
| Storage per extension | 1 MB |
| Timers | 20 per extension, 100 ms minimum for `interval` |
| `string.rep` result | 8 MB |
| Output of a process | 1 MB |
| Problems per check | 200 |

## Packaging and installing

- **Folder:** copy the folder into `VSDroid/extensions/`, then **Reload** (the circular arrow in the Extensions tab).
- **Package:** zip the contents of your folder (with `extension.json` at the top, or inside one single folder) and rename
  it to `yourname.thing.vsdext`. In the Extensions tab choose **+** and pick the file. Installing a newer version replaces the old
  one and asks for approval again. Packages with unsafe paths (`../`) are refused and nothing is written.

## Examples

See [`examples/extensions/`](../examples/extensions):

- `examples.hello-world`: the smallest extension (prompt, storage, toast).
- `examples.todo-finder`: problem checker plus a side panel that jumps to the line.
- `examples.snippets-and-format`: completion provider and a formatter.
- `examples.theme-pack`: Catppuccin Mocha and Dracula themes.

To try one, copy its folder into `VSDroid/extensions/`.

## For VSDroid developers

The runtime is plain Kotlin in `app/src/main/java/com/vsdroid/ide/core/ext/` (no Android classes), using
[LuaJ](https://github.com/luaj/luaj). `IdeExtensions.kt` connects it to the app. It can be tested on a PC without the Android SDK:

```
tools/ext-tests/run.sh path/to/luaj-jse.jar
```

(needs `kotlinc` and `java`; the script compiles the runtime and the tests together and runs every check, including the examples).
