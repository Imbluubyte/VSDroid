# VSDroid

[![Join the Discord](https://img.shields.io/badge/Discord-Join%20the%20community-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/vpkU62pe4Q)

**Hey Guys**
Bluu here, just wanted to say thanks for checking out the project, just a disclaimer, this project was built by Claude, I take no credit for this project which is why you can redistribute it, fork it, and loads of other stuff that my tiny brain doesn't understand. I know a lot of people get angry at people who vibe-code, but it's pretty useful, especially if you're still new to programming and need a piece of Software that doesn't exist or you can't afford. In my case, my laptop broke, and so far I've had no luck with customer support to get it fixed, I needed somewhere to write my code so I could keep practicing, and that's why I asked Claude to make VSDroid.

I know I know, Acode exists, but even with extensions it lacks so many useful features and workflow improvements, VSDroid is built to work out of the box.

The rest of this README is writted by Claude, enjoy :]


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
