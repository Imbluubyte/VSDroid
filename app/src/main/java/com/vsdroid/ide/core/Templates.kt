package com.vsdroid.ide.core

import java.io.File

class Template(
    val id: String,
    val name: String,
    val description: String,
    /** Offer to create a Python virtual environment after generating the project. */
    val wantsVenv: Boolean = false,
    val files: (projectName: String) -> Map<String, String>,
)

object Templates {
    fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').ifEmpty { "project" }

    private fun identifier(name: String): String {
        val s = name.replace(Regex("[^A-Za-z0-9_]"), "_")
        return if (s.isEmpty() || s[0].isDigit()) "_$s" else s
    }

    private const val CS_IGNORE = "bin/\nobj/\n.vs/\n*.user\n"

    val builtIn: List<Template> = listOf(
        Template("python", "Python", "main.py, requirements.txt, README, .gitignore", wantsVenv = true) { name ->
            mapOf(
                "main.py" to "def main():\n    print(\"Hello from $name!\")\n\n\nif __name__ == \"__main__\":\n    main()\n",
                "requirements.txt" to "# Add your dependencies here, one per line\n",
                "README.md" to "# $name\n\nA Python project.\n\n## Run\n\n```\npython main.py\n```\n",
                ".gitignore" to "__pycache__/\n*.pyc\n.venv/\nvenv/\n.env\n",
            )
        },
        Template("csharp-console", "C# Console App", ".csproj, Program.cs (.NET 8)") { name ->
            mapOf(
                "$name.csproj" to """<Project Sdk="Microsoft.NET.Sdk">

  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net8.0</TargetFramework>
    <ImplicitUsings>disable</ImplicitUsings>
    <UseAppHost>false</UseAppHost>
    <Nullable>enable</Nullable>
  </PropertyGroup>

</Project>
""",
                "Program.cs" to "using System;\n\nnamespace ${identifier(name)}\n{\n    class Program\n    {\n        static void Main(string[] args)\n        {\n            Console.WriteLine(\"Hello, World!\");\n        }\n    }\n}\n",
                ".gitignore" to CS_IGNORE,
                "README.md" to "# $name\n\nA C# console app.\n\n```\ndotnet run\n```\n",
            )
        },
        Template("csharp-lib", "C# Class Library", ".csproj, Class1.cs (.NET 8)") { name ->
            mapOf(
                "$name.csproj" to """<Project Sdk="Microsoft.NET.Sdk">

  <PropertyGroup>
    <TargetFramework>net8.0</TargetFramework>
    <ImplicitUsings>enable</ImplicitUsings>
    <UseAppHost>false</UseAppHost>
    <Nullable>enable</Nullable>
  </PropertyGroup>

</Project>
""",
                "Class1.cs" to "namespace ${identifier(name)};\n\npublic class Class1\n{\n}\n",
                ".gitignore" to CS_IGNORE,
            )
        },
        Template("csharp-web", "C# Minimal Web API", "ASP.NET Core minimal API (.NET 8)") { name ->
            mapOf(
                "$name.csproj" to """<Project Sdk="Microsoft.NET.Sdk.Web">

  <PropertyGroup>
    <TargetFramework>net8.0</TargetFramework>
    <ImplicitUsings>enable</ImplicitUsings>
    <UseAppHost>false</UseAppHost>
    <Nullable>enable</Nullable>
  </PropertyGroup>

</Project>
""",
                "Program.cs" to "var builder = WebApplication.CreateBuilder(args);\nvar app = builder.Build();\n\napp.MapGet(\"/\", () => \"Hello World!\");\n\napp.Run();\n",
                "appsettings.json" to "{\n  \"Logging\": {\n    \"LogLevel\": {\n      \"Default\": \"Information\"\n    }\n  },\n  \"AllowedHosts\": \"*\"\n}\n",
                ".gitignore" to CS_IGNORE,
            )
        },
        Template("web", "Web (HTML/CSS/JS)", "index.html, style.css, script.js") { name ->
            mapOf(
                "index.html" to "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"UTF-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n  <title>$name</title>\n  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n  <h1>$name</h1>\n  <script src=\"script.js\"></script>\n</body>\n</html>\n",
                "style.css" to "body {\n  font-family: system-ui, sans-serif;\n  margin: 2rem;\n}\n",
                "script.js" to "console.log(\"Hello from $name\");\n",
            )
        },
        Template("node", "Node.js", "package.json, index.js") { name ->
            mapOf(
                "package.json" to "{\n  \"name\": \"${name.lowercase()}\",\n  \"version\": \"1.0.0\",\n  \"main\": \"index.js\",\n  \"scripts\": {\n    \"start\": \"node index.js\"\n  }\n}\n",
                "index.js" to "console.log(\"Hello from $name\");\n",
                ".gitignore" to "node_modules/\n.env\n",
            )
        },
        Template("cpp", "C++ (CMake)", "CMakeLists.txt, main.cpp") { name ->
            mapOf(
                "CMakeLists.txt" to "cmake_minimum_required(VERSION 3.16)\nproject(${identifier(name)} LANGUAGES CXX)\n\nset(CMAKE_CXX_STANDARD 17)\nset(CMAKE_CXX_STANDARD_REQUIRED ON)\n\nadd_executable(${identifier(name)} main.cpp)\n",
                "main.cpp" to "#include <iostream>\n\nint main() {\n    std::cout << \"Hello, World!\" << std::endl;\n    return 0;\n}\n",
                ".gitignore" to "build/\n",
            )
        },
        Template("lua", "Lua", "main.lua, README") { name ->
            mapOf(
                "main.lua" to "local function main()\n    print(\"Hello from $name!\")\nend\n\nmain()\n",
                "README.md" to "# $name\n\nA Lua project.\n\n## Run\n\n```\nlua main.lua\n```\n",
            )
        },
        Template("empty", "Empty Folder", "Just a folder") { _ -> emptyMap() },
    )

    // ---- user templates (File > Save as Template) -----------------------------------------------

    /** Folder that holds saved templates; set by the app at start-up. Each template is a sub-folder. */
    var userDir: File? = null

    private const val META = "template.txt"
    private const val FILES = "files"
    private const val MAX_FILES = 500
    private const val MAX_FILE_BYTES = 1_000_000L
    private val SKIP_DIRS = setOf(".git", ".venv", "venv", "node_modules", "bin", "obj", "build", ".vs", "__pycache__", ".vsdroid", ".gradle")

    /** Built-in templates followed by the ones the user saved. */
    fun available(): List<Template> = builtIn + userTemplates()

    private fun userTemplates(): List<Template> {
        val dir = userDir ?: return emptyList()
        val folders = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: return emptyList()
        val out = ArrayList<Template>()
        for (folder in folders) {
            try {
                val lines = File(folder, META).takeIf { it.isFile }?.readLines() ?: continue
                val name = lines.getOrNull(0)?.trim().orEmpty().ifEmpty { folder.name }
                val desc = lines.getOrNull(1)?.trim().orEmpty()
                val filesDir = File(folder, FILES)
                out.add(Template("user:" + folder.name, name, desc.ifEmpty { "Saved template" }) { _ ->
                    val map = LinkedHashMap<String, String>()
                    filesDir.walkTopDown().filter { it.isFile }.forEach { f ->
                        map[f.relativeTo(filesDir).path] = f.readText()
                    }
                    map
                })
            } catch (e: Exception) {
                // A damaged template folder is skipped rather than breaking the New Project dialog.
            }
        }
        return out
    }

    /** True when a template with this name was already saved. */
    fun userTemplateExists(name: String): Boolean {
        val dir = userDir ?: return false
        return File(dir, sanitize(name)).exists()
    }

    /**
     * Saves the text files of [project] as a new template called [name]. Build folders, virtual environments,
     * .git and binary or very large files are left out. Returns the number of files saved.
     */
    fun saveFromProject(project: File, name: String, description: String): Int {
        val dir = userDir ?: error("Templates folder is not available")
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Give the template a name" }
        val target = File(dir, sanitize(cleanName))
        require(!target.exists()) { "A template named \"$cleanName\" already exists" }
        val filesDir = File(target, FILES)
        var count = 0
        try {
            check(filesDir.mkdirs()) { "Could not create ${filesDir.path}" }
            val queue = ArrayDeque<File>()
            queue.add(project)
            while (queue.isNotEmpty() && count < MAX_FILES) {
                val d = queue.removeFirst()
                for (f in d.listFiles().orEmpty().sortedBy { it.name }) {
                    if (f.isDirectory) {
                        if (f.name !in SKIP_DIRS) queue.add(f)
                    } else if (f.length() <= MAX_FILE_BYTES && isText(f) && count < MAX_FILES) {
                        val dest = File(filesDir, f.relativeTo(project).path)
                        dest.parentFile?.mkdirs()
                        f.copyTo(dest)
                        count++
                    }
                }
            }
            val desc = description.trim().replace(Regex("\\s+"), " ")
            File(target, META).writeText(cleanName.replace(Regex("\\s+"), " ") + "\n" + desc + "\n")
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        }
        return count
    }

    private fun isText(f: File): Boolean = try {
        f.inputStream().use { input ->
            val buf = ByteArray(8000)
            val n = input.read(buf)
            (0 until maxOf(n, 0)).none { buf[it].toInt() == 0 }
        }
    } catch (e: Exception) {
        false
    }

    /** Creates the project folder under [parent] and writes the template files. */
    fun create(parent: File, rawName: String, template: Template): File {
        val name = sanitize(rawName)
        val dir = File(parent, name)
        require(!dir.exists()) { "A folder named \"$name\" already exists" }
        check(dir.mkdirs()) { "Could not create ${dir.path}" }
        for ((path, content) in template.files(name)) {
            val f = File(dir, path)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        return dir
    }
}
