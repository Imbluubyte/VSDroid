# VSDroid

**A real code editor for your tablet.**

You've got a big, beautiful screen, a keyboard that clips on, and a pile of ideas. What you haven't had is somewhere to write code that doesn't feel like a compromise. VSDroid is built natively for Android (Kotlin and Jetpack Compose, no Electron, no WebView), so it opens instantly and it feels at home on a touch screen.

## It feels like the editor you already know

The layout is the one you've spent years in: activity bar, side bar, tabs, a command palette, and a terminal panel under your code. Dark by default, light if you prefer, and every colour is themeable with a few lines of JSON. Shortcuts work with a hardware keyboard, and you can rebind every one of them.

## It understands your code

Type `.` and VSDroid knows what you're talking about, whether it's a string, a class from another file or something from the standard library. Parameter hints follow you through a call. Press F12 and you're at the definition, even across files. Rename a function and every use of it changes with it, without touching the strings and comments that happen to share the name.

That works the same way in **Python, JavaScript, TypeScript, Java, C#, Kotlin, C++ and Lua**, not only in one favourite language.

## It tells you what's wrong before you run it

Syntax errors, undefined names and unused imports get underlined as you type, with a one-tap fix when there is one. A Problems tab collects everything in the project, and when your program crashes in the terminal, the file and line it blames light up in the editor.

## It runs your code

A real terminal is built in. With Termux behind it you get the full Linux toolbox: Python with virtual environments and pip, .NET, Node, Java, C++, Lua. Programs can ask for input and you answer right in the terminal. Open as many terminals as you like and run things side by side.

## Your projects, your Git

Clone from GitHub, stage, commit, push and pull from the Source Control tab. Start a project from a template, or turn your own project into a template for next time.

## Make it yours

Extensions are small Lua programs that add commands, panels, checks, formatters, languages and themes. Each one lists exactly what it wants to do and only runs once you approve it, inside a sandbox, so a misbehaving extension can't freeze your editor. Writing one is a weekend afternoon: see [docs/EXTENSIONS.md](docs/EXTENSIONS.md) and the [examples](examples/extensions).

## Little things that add up

A first-launch guide that sets up your tools, a to-do list for every project, full-screen mode, auto-indent, auto-closing brackets and quotes, and a quick-open palette (Ctrl+P) that gets you to any file in a few keystrokes.

---

VSDroid is open source under the [GNU GPLv3](LICENSE). Ideas, bug reports and pull requests are welcome.
