package com.vsdroid.ide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PackageInfo(val name: String, val summary: String, val version: String? = null)

/**
 * Package search for the Python Environments panel.
 *
 * PyPI has no public search API, so there are two sources:
 *  1. a built-in index of popular packages (works offline, searched as you type), and
 *  2. an exact-name lookup against pypi.org, so any package can still be found by its name.
 */
object PackageIndex {
    private val VALID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun isValidName(name: String) = name.length in 1..100 && VALID.matches(name)

    /** PEP 503 normalisation, used to compare names. */
    fun norm(name: String) = name.trim().lowercase().replace(Regex("[-_.]+"), "-")

    private fun p(name: String, summary: String) = PackageInfo(name, summary)

    val popular: List<PackageInfo> = listOf(
        // Data and science
        p("numpy", "Arrays and numerical computing"),
        p("pandas", "Data analysis and tables"),
        p("scipy", "Scientific computing algorithms"),
        p("matplotlib", "Plotting and charts"),
        p("seaborn", "Statistical data visualisation"),
        p("plotly", "Interactive graphs"),
        p("scikit-learn", "Machine learning tools"),
        p("statsmodels", "Statistical models and tests"),
        p("sympy", "Symbolic mathematics"),
        p("networkx", "Graphs and networks"),
        p("pillow", "Image processing (PIL)"),
        p("opencv-python", "Computer vision (OpenCV)"),
        p("scikit-image", "Image processing algorithms"),
        p("h5py", "HDF5 file access"),
        p("openpyxl", "Read and write Excel .xlsx files"),
        p("xlrd", "Read old Excel files"),
        p("xlsxwriter", "Create Excel files"),
        p("polars", "Fast DataFrame library"),
        p("pyarrow", "Apache Arrow data format"),
        p("bokeh", "Interactive web plots"),
        p("altair", "Declarative statistical charts"),
        p("tabulate", "Pretty-print tables"),
        p("tqdm", "Progress bars"),
        p("rich", "Rich text and formatting in the terminal"),
        // Web and networking
        p("requests", "HTTP requests made simple"),
        p("httpx", "Modern HTTP client"),
        p("aiohttp", "Async HTTP client and server"),
        p("urllib3", "HTTP client library"),
        p("beautifulsoup4", "Parse HTML and XML"),
        p("lxml", "Fast XML and HTML processing"),
        p("scrapy", "Web crawling framework"),
        p("selenium", "Browser automation"),
        p("playwright", "Browser automation by Microsoft"),
        p("flask", "Lightweight web framework"),
        p("django", "Full-featured web framework"),
        p("fastapi", "Modern async API framework"),
        p("uvicorn", "ASGI web server"),
        p("gunicorn", "WSGI web server"),
        p("starlette", "ASGI toolkit"),
        p("jinja2", "Templating engine"),
        p("websockets", "WebSocket client and server"),
        p("websocket-client", "WebSocket client"),
        p("paramiko", "SSH connections"),
        p("flask-cors", "CORS support for Flask"),
        p("flask-sqlalchemy", "SQLAlchemy for Flask"),
        p("python-socketio", "Socket.IO server and client"),
        p("pyjwt", "JSON Web Tokens"),
        p("cryptography", "Encryption and cryptographic recipes"),
        p("pycryptodome", "Cryptographic library"),
        p("bcrypt", "Password hashing"),
        p("certifi", "Root certificates for TLS"),
        // AI and ML
        p("torch", "PyTorch deep learning"),
        p("tensorflow", "TensorFlow deep learning"),
        p("keras", "High-level neural network API"),
        p("transformers", "Hugging Face models"),
        p("openai", "OpenAI API client"),
        p("anthropic", "Anthropic API client"),
        p("langchain", "LLM application framework"),
        p("tiktoken", "Fast tokeniser for LLMs"),
        p("nltk", "Natural language toolkit"),
        p("spacy", "Natural language processing"),
        p("gensim", "Topic modelling"),
        p("xgboost", "Gradient boosting"),
        p("lightgbm", "Gradient boosting by Microsoft"),
        p("joblib", "Lightweight pipelining and caching"),
        // Databases
        p("sqlalchemy", "SQL toolkit and ORM"),
        p("psycopg2-binary", "PostgreSQL driver"),
        p("pymysql", "MySQL driver"),
        p("mysql-connector-python", "MySQL official driver"),
        p("pymongo", "MongoDB driver"),
        p("redis", "Redis client"),
        p("peewee", "Small ORM"),
        p("alembic", "Database migrations for SQLAlchemy"),
        p("sqlmodel", "SQL databases with Python types"),
        // Dev tools and testing
        p("pytest", "Testing framework"),
        p("pytest-cov", "Coverage plugin for pytest"),
        p("pytest-asyncio", "Async tests for pytest"),
        p("coverage", "Code coverage measurement"),
        p("tox", "Test automation across environments"),
        p("black", "Code formatter"),
        p("flake8", "Style and error checker"),
        p("pylint", "Static code analysis"),
        p("mypy", "Static type checker"),
        p("ruff", "Very fast linter and formatter"),
        p("isort", "Sort imports"),
        p("autopep8", "Auto-format to PEP 8"),
        p("pre-commit", "Git hook manager"),
        p("ipython", "Enhanced interactive shell"),
        p("jupyter", "Notebooks"),
        p("jupyterlab", "Notebook interface"),
        p("notebook", "Classic Jupyter Notebook"),
        p("pip-tools", "Pin dependencies"),
        p("poetry", "Dependency management and packaging"),
        p("virtualenv", "Create virtual environments"),
        p("pipenv", "Dependency and environment manager"),
        p("setuptools", "Build and package projects"),
        p("wheel", "Wheel packaging format"),
        p("twine", "Upload packages to PyPI"),
        p("build", "Python package builder"),
        p("pyinstaller", "Bundle programs into executables"),
        p("cython", "Compile Python to C"),
        p("numba", "JIT compile numeric code"),
        p("hypothesis", "Property-based testing"),
        p("faker", "Generate fake data"),
        p("mock", "Mocking library"),
        p("responses", "Mock the requests library"),
        p("freezegun", "Mock the current time"),
        p("debugpy", "Debug adapter for Python"),
        p("python-lsp-server", "Language server for editors"),
        // CLI and utilities
        p("click", "Command line interfaces"),
        p("typer", "CLI apps from type hints"),
        p("colorama", "Coloured terminal text"),
        p("termcolor", "ANSI colour formatting"),
        p("prompt-toolkit", "Interactive command lines"),
        p("pyfiglet", "ASCII art text"),
        p("python-dotenv", "Load settings from .env files"),
        p("pyyaml", "YAML parser"),
        p("toml", "TOML parser"),
        p("tomli", "Read TOML files"),
        p("ujson", "Fast JSON"),
        p("orjson", "Very fast JSON"),
        p("pydantic", "Data validation with type hints"),
        p("attrs", "Classes without boilerplate"),
        p("marshmallow", "Object serialisation"),
        p("python-dateutil", "Date utilities"),
        p("pytz", "Time zones"),
        p("arrow", "Friendlier dates and times"),
        p("pendulum", "Easier datetime handling"),
        p("schedule", "Run jobs on a schedule"),
        p("apscheduler", "Advanced job scheduler"),
        p("celery", "Distributed task queue"),
        p("psutil", "System and process information"),
        p("watchdog", "Watch file system events"),
        p("send2trash", "Move files to the trash"),
        p("pathlib2", "Object-oriented file paths"),
        p("regex", "Extended regular expressions"),
        p("python-slugify", "Make URL slugs"),
        p("emoji", "Emoji support"),
        p("humanize", "Human-friendly numbers and dates"),
        p("loguru", "Simple logging"),
        p("structlog", "Structured logging"),
        p("tenacity", "Retry logic"),
        p("cachetools", "Caching helpers"),
        p("more-itertools", "Extra iteration tools"),
        p("toolz", "Functional utilities"),
        p("six", "Python 2 and 3 compatibility"),
        p("typing-extensions", "Backports of typing features"),
        p("packaging", "Version and requirement parsing"),
        p("platformdirs", "Find standard app folders"),
        p("filelock", "Cross-platform file locking"),
        p("docopt", "Create CLIs from docstrings"),
        p("fire", "Turn any object into a CLI"),
        p("pexpect", "Control interactive programs"),
        p("sh", "Call shell commands like functions"),
        p("pyperclip", "Clipboard access"),
        p("qrcode", "Generate QR codes"),
        p("python-magic", "Detect file types"),
        p("chardet", "Detect text encodings"),
        p("unidecode", "ASCII transliteration"),
        p("markdown", "Markdown to HTML"),
        p("mistune", "Fast Markdown parser"),
        p("pygments", "Syntax highlighting"),
        p("reportlab", "Create PDF files"),
        p("pypdf", "Read and edit PDFs"),
        p("pypdf2", "Read and edit PDFs (older name)"),
        p("python-docx", "Read and write Word files"),
        p("python-pptx", "Create PowerPoint files"),
        p("fpdf2", "Simple PDF generation"),
        p("pyzipper", "Encrypted zip files"),
        // Games, GUI and media
        p("pygame", "Game development"),
        p("pyglet", "Windowing and multimedia"),
        p("arcade", "Easy 2D games"),
        p("kivy", "Touch-friendly GUI framework"),
        p("pyqt5", "Qt5 GUI bindings"),
        p("pyside6", "Qt6 GUI bindings"),
        p("customtkinter", "Modern-looking tkinter"),
        p("pysimplegui", "Simple GUIs"),
        p("textual", "Terminal user interfaces"),
        p("streamlit", "Data apps in minutes"),
        p("gradio", "Machine learning web demos"),
        p("dash", "Analytical web apps"),
        p("nicegui", "Web-based UI framework"),
        p("pyautogui", "Control mouse and keyboard"),
        p("pynput", "Monitor and control input"),
        p("moviepy", "Video editing"),
        p("pydub", "Audio manipulation"),
        p("imageio", "Read and write images and video"),
        p("pytesseract", "OCR with Tesseract"),
        p("gtts", "Google text-to-speech"),
        p("pyttsx3", "Offline text-to-speech"),
        p("speechrecognition", "Speech to text"),
        p("yt-dlp", "Download online videos"),
        p("discord.py", "Discord bots"),
        p("python-telegram-bot", "Telegram bots"),
        p("tweepy", "Twitter API"),
        p("praw", "Reddit API"),
        p("pyserial", "Serial port access"),
        p("gpiozero", "Raspberry Pi GPIO"),
        p("bleak", "Bluetooth Low Energy"),
        p("zeroconf", "mDNS service discovery"),
        // Async
        p("trio", "Friendly async concurrency"),
        p("anyio", "Async compatibility layer"),
        p("gevent", "Coroutine-based networking"),
    ).distinctBy { norm(it.name) }

    private val sortedLookup = popular.associateBy { norm(it.name) }

    /** Searches the built-in index. Empty query returns the first few entries. */
    fun search(query: String, limit: Int = 30): List<PackageInfo> {
        val q = norm(query)
        if (q.isEmpty()) return popular.take(20)
        val scored = ArrayList<Pair<Int, PackageInfo>>()
        for (info in popular) {
            val n = norm(info.name)
            val score = when {
                n == q -> 0
                n.startsWith(q) -> 1
                n.contains(q) -> 2
                info.summary.lowercase().contains(query.trim().lowercase()) -> 3
                else -> continue
            }
            scored.add(score to info)
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second.name.length }, { it.second.name }))
            .take(limit)
            .map { it.second }
    }

    fun inIndex(name: String): Boolean = norm(name) in sortedLookup

    /** Exact-name lookup on pypi.org. Returns null when the package doesn't exist or there's no network. */
    suspend fun lookup(raw: String): PackageInfo? = withContext(Dispatchers.IO) {
        val name = raw.trim()
        if (!isValidName(name)) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://pypi.org/pypi/$name/json").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val info = JSONObject(body).getJSONObject("info")
            PackageInfo(
                name = info.optString("name", name),
                summary = info.optString("summary", ""),
                version = info.optString("version", "").ifEmpty { null },
            )
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
