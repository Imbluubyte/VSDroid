# Known bugs (audit of 2026-10-26)

Every entry below was confirmed by reading the code and, for the problem checker, by compiling `core/Diag*.kt` + `Languages.kt` with kotlinc and running sample input. Nothing here has been fixed yet. Severity: **High** loses data or is common, **Medium** wrong behaviour in normal use, **Low** edge case.

## App / editor

### 1. Renaming a file or folder discards unsaved edits (Medium)
`IdeState.rename()` removes the tabs of the renamed path and then calls `openFile(target)` only if the target is a file. A dirty tab is not saved first, so its unsaved text is lost. Renaming a folder closes every open tab inside it and does not reopen them.
Repro: edit a file without saving, rename it in the explorer. The tab reopens with the old on-disk text.

### 2. Deleting a file with unsaved changes can bring it back (Medium)
`IdeState.delete()` calls `deleteRecursively()` and only then `closeTab(it)`. With auto-save on and a dirty tab, `closeTab` calls `save()`, which `writeText`s the file again. For a deleted folder the write fails and shows "Save failed".
Repro: auto-save on, edit a file, delete it in the explorer. The file reappears.

### 3. Terminal history: Down arrow jumps to the oldest command (Low)
`TerminalView.recall()` (ui/Panels.kt): with `historyIndex == -1` and `delta = 1`, the index becomes 0, so pressing Down with no history browsing fills in the oldest command. Pressing Up at index 0 gives -1 and clears the input instead of staying on the oldest entry.

### 4. Toggle comment also affects the line after a whole-line selection (Low)
`EditorOps.toggleComment` extends `end` to the end of the line containing the selection end. If the selection ends exactly at the start of the next line (select whole lines), that next line is toggled too.

### 5. Accepting a completion or Tab indent skips the edit pipeline (Low)
In `ui/Editor.kt`, `accept(word)` (and Tab indent/outdent) set `tab.value` directly instead of going through `onValueChange`, so problem remapping, `onEdited` and auto-save do not run for those edits. Squiggles stay at stale positions until the next analysis.

### 6. Closing a folder leaves search results and expanded state (Low)
`IdeState.closeFolder()` clears tabs, venvs and todos but not `searchHits`, `searchFiles` or `expanded`, which `openFolder()` does clear. Old results from the closed project stay in the Search view.

### 7. Terminal lines ending in a bare carriage return show as blank (Low)
`TerminalSession` strips with `ANSI.replace(...).substringAfterLast('\r')`. A line that ends in `\r` (CRLF output) becomes an empty string.

## Problem checker (core/Diag*.kt)

### 8. Kotlin imports after the first are not recognised (High for small projects)
`BraceCheck.atStatementStart()` accepts a `NEWLINE` token as "statement start", but `prevSig()` skips NEWLINE tokens, so that branch never fires. In languages whose imports end without `;` (Kotlin, optionally JS), only the first `import` in a file is recognised. Every following import line gets `Undefined variable 'androidx'` (and similar) and unused-import detection does not run for them. The project word index hides most of it in big projects, because the word shows up in other files. Seen when checking the repo's own files without the index (`Overlays.kt:3-31`, `MainActivity.kt`, and others).

### 9. JSON files with a byte-order mark report an error (Medium)
`JsonCheck.skip()` uses `isWhitespace()`, which is false for U+FEFF. Input `"﻿{\"a\":1}"` gives `1:1 json-unexpected Unexpected '?': expected a value`. Files saved by some Windows editors start with a BOM. The app does not strip it when opening files.

### 10. Lua long comments with `=` levels are mis-lexed (Medium)
`Languages` defines the Lua block comment as `--[[` … `]]` only. `--[==[ … ]==]` is treated as a line comment, so the following lines are lexed as code. Input `--[==[\nlong ' comment\n]==]\nprint(1)` gives `Unterminated string literal` and two `Unmatched ']'` errors.

### 11. JavaScript regex after `)` is lexed as division (Low)
`Lexer.regexAllowed()` returns false after `)`, `]` and `}`. In `if (x) /re/.test('a')`, `/re/` is read as division, so `re` is reported as `Undefined variable 're'`.

### 12. Lua: `break` inside a function inside a loop is not reported (Low)
`LuaCheck.flow()` has an empty `if` where function boundaries should reset the loop context (a "functions reset loop context" comment, no code). `for i=1,3 do local f=function() break end end` reports nothing, but it is an error in Lua.

### 13. JSON: numbers with leading zeros are accepted (Low)
`JsonCheck.number()` accepts `01`. Input `{"a":01}` gives no problem, but it is invalid JSON.

### 14. Lua: unused locals are missed when a field has the same name (Low)
`LuaCheck.undefinedAndUnused()` counts every identifier occurrence, including `.field` accesses. `local x = 1; t.x = 2` never reports `x` as unused.
