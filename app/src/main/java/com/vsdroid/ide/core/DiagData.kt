package com.vsdroid.ide.core

/* Word lists used by the problem checker. Kept apart so the checks themselves stay readable. */

private fun set(s: String): Set<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toHashSet()

internal object DiagData {
    /** Names that are always available in JavaScript / TypeScript (browser, Node, test runners). */
    val JS_GLOBALS: Set<String> = set(
        "undefined NaN Infinity globalThis window document console navigator location history screen localStorage " +
            "sessionStorage alert confirm prompt fetch setTimeout clearTimeout setInterval clearInterval setImmediate " +
            "clearImmediate requestAnimationFrame cancelAnimationFrame requestIdleCallback performance module exports " +
            "require process global __dirname __filename self top parent frames event name length status atob btoa " +
            "queueMicrotask structuredClone crypto indexedDB caches postMessage close open print stop focus blur scroll " +
            "scrollTo scrollBy eval parseInt parseFloat isNaN isFinite encodeURI encodeURIComponent decodeURI " +
            "decodeURIComponent escape unescape arguments describe it test expect beforeEach afterEach beforeAll afterAll " +
            "jest vi suite before after define chrome browser Deno Bun type of from as is infer satisfies asserts keyof " +
            "readonly declare namespace abstract override accessor unique out get set async await static module " +
            "string number boolean any void never unknown object symbol bigint undefined null true false " +
            "customElements matchMedia getComputedStyle innerWidth innerHeight devicePixelRatio onload onerror " +
            "addEventListener removeEventListener dispatchEvent reportError importScripts",
    )

    /** Standard names Kotlin code can use without importing them. */
    val KT_GLOBALS: Set<String> = set(
        "it field value this super null true false println print readLine readln listOf mutableListOf arrayListOf " +
            "emptyList setOf mutableSetOf hashSetOf emptySet mapOf mutableMapOf hashMapOf emptyMap linkedMapOf sortedMapOf " +
            "arrayOf intArrayOf longArrayOf doubleArrayOf floatArrayOf booleanArrayOf charArrayOf byteArrayOf shortArrayOf " +
            "emptyArray arrayOfNulls require requireNotNull check checkNotNull error assert TODO repeat run with apply " +
            "also let takeIf takeUnless lazy lazyOf to maxOf minOf buildString buildList buildMap buildSet sequence " +
            "generateSequence sequenceOf synchronized use measureTimeMillis measureNanoTime runCatching suspend " +
            "listOfNotNull mutableMapOf mapNotNull setOfNotNull arrayListOf lazy coroutineContext enumValues enumValueOf typeOf Pair Triple",
    )

    /** java.lang plus the basics, so a wildcard import is not blamed for them. */
    val JAVA_IMPLICIT: Set<String> = set(
        "Object String Integer Long Double Float Boolean Character Byte Short Number Math StrictMath System Thread " +
            "Runnable Exception RuntimeException Error Throwable IllegalArgumentException IllegalStateException " +
            "NullPointerException IndexOutOfBoundsException ArrayIndexOutOfBoundsException ClassCastException " +
            "ArithmeticException NumberFormatException UnsupportedOperationException InterruptedException " +
            "CloneNotSupportedException AssertionError StackOverflowError OutOfMemoryError Iterable Comparable " +
            "CharSequence StringBuilder StringBuffer Class ClassLoader Enum Record Void Override Deprecated " +
            "SuppressWarnings FunctionalInterface SafeVarargs Process ProcessBuilder Runtime Package Cloneable " +
            "AutoCloseable ThreadLocal InheritableThreadLocal SecurityException StackTraceElement ThreadGroup",
    )

    /** Kotlin's default imports (kotlin.*, kotlin.collections.*, kotlin.text.*, kotlin.io.*, java.lang.*). */
    val KT_IMPLICIT: Set<String> = set(
        "Any Unit Nothing Int Long Short Byte Float Double Boolean Char String Number Array IntArray LongArray " +
            "DoubleArray FloatArray BooleanArray CharArray ByteArray ShortArray List MutableList Map MutableMap Set " +
            "MutableSet Collection MutableCollection Iterable MutableIterable Iterator MutableIterator Sequence " +
            "Pair Triple Comparable CharSequence StringBuilder Throwable Exception Error RuntimeException " +
            "IllegalArgumentException IllegalStateException UnsupportedOperationException IndexOutOfBoundsException " +
            "NoSuchElementException NullPointerException ClassCastException ArithmeticException NumberFormatException " +
            "ConcurrentModificationException AssertionError Result Lazy Function Annotation Enum Regex Math System " +
            "Thread Runnable Object Class Deprecated JvmStatic JvmField JvmOverloads JvmName Volatile Synchronized " +
            "Transient Strictfp Suppress Throws Target Retention Repeatable MustBeDocumented ArrayList HashMap HashSet " +
            "LinkedHashMap LinkedHashSet Comparator AutoCloseable Closeable Appendable Readable",
    )

    /** For each C# namespace, names that need it. Namespaces not listed here are never reported. */
    val CS_NAMESPACES: Map<String, Set<String>> = mapOf(
        "System.Linq" to set(
            "Select Where OrderBy OrderByDescending ThenBy ThenByDescending ToList ToArray ToDictionary First FirstOrDefault " +
                "Last LastOrDefault Single SingleOrDefault Any All Sum Max Min Average Count LongCount GroupBy Distinct " +
                "DistinctBy Skip Take Concat Union Intersect Except Zip Aggregate SelectMany Cast OfType Contains Reverse " +
                "ElementAt ElementAtOrDefault Range Repeat Empty ToHashSet ToLookup Join GroupJoin TakeWhile SkipWhile " +
                "AsEnumerable AsQueryable Queryable Enumerable IQueryable IOrderedEnumerable IGrouping ILookup MaxBy MinBy " +
                "Chunk SequenceEqual DefaultIfEmpty Prepend Append TakeLast SkipLast Order OrderDescending " +
                "from select orderby group join let",
        ),
        "System.Collections.Generic" to set(
            "List Dictionary HashSet Queue Stack IEnumerable IList IDictionary ICollection KeyValuePair SortedDictionary " +
                "SortedList LinkedList LinkedListNode IReadOnlyList IReadOnlyCollection IReadOnlyDictionary ISet " +
                "Comparer EqualityComparer IComparer IEqualityComparer SortedSet PriorityQueue IAsyncEnumerable " +
                "IEnumerator CollectionExtensions KeyNotFoundException",
        ),
        "System.Text" to set("StringBuilder Encoding UTF8Encoding Rune NormalizationForm Decoder Encoder ASCIIEncoding UnicodeEncoding"),
        "System.IO" to set(
            "File Directory Path FileInfo DirectoryInfo Stream StreamReader StreamWriter MemoryStream FileStream TextReader " +
                "TextWriter StringReader StringWriter BinaryReader BinaryWriter FileMode FileAccess FileShare IOException " +
                "FileNotFoundException DirectoryNotFoundException SearchOption FileSystemWatcher BufferedStream FileAttributes",
        ),
        "System.Threading.Tasks" to set("Task Parallel TaskCompletionSource ValueTask TaskScheduler TaskStatus TaskFactory TaskCanceledException"),
        "System.Threading" to set(
            "Thread Mutex Monitor Interlocked SemaphoreSlim CancellationToken CancellationTokenSource Timer ThreadPool " +
                "Volatile ManualResetEvent AutoResetEvent Semaphore ReaderWriterLockSlim Barrier ThreadLocal Timeout " +
                "ManualResetEventSlim CountdownEvent ThreadStart ParameterizedThreadStart",
        ),
        "System.Net.Http" to set("HttpClient HttpResponseMessage HttpRequestMessage HttpContent StringContent HttpMethod HttpClientHandler FormUrlEncodedContent ByteArrayContent MultipartFormDataContent HttpRequestException"),
        "System.Diagnostics" to set("Debug Stopwatch Process Trace ProcessStartInfo Debugger Activity DebuggerDisplay DebuggerStepThrough Conditional"),
        "System.Text.RegularExpressions" to set("Regex Match MatchCollection Group RegexOptions MatchEvaluator Capture GroupCollection"),
        "System.Collections" to set("IEnumerable IEnumerator ArrayList Hashtable IList ICollection IDictionary Queue Stack BitArray SortedList IComparer DictionaryEntry"),
        "System.Text.Json" to set("JsonSerializer JsonDocument JsonElement JsonSerializerOptions Utf8JsonWriter Utf8JsonReader JsonException JsonProperty JsonValueKind"),
        "System.Globalization" to set("CultureInfo NumberStyles DateTimeStyles CompareOptions CultureTypes TextInfo CalendarWeekRule"),
        "System.Numerics" to set("BigInteger Complex Vector2 Vector3 Vector4 Matrix4x4 Quaternion Matrix3x2"),
        "System.Reflection" to set("Assembly MethodInfo PropertyInfo FieldInfo BindingFlags ConstructorInfo ParameterInfo MemberInfo"),
    )

    /** For each C/C++ header, names it provides. Headers not listed are never reported (and switch the undefined-name check off). */
    val HEADERS: Map<String, Set<String>> = buildMap {
        val stdio = set(
            "printf scanf fprintf sprintf snprintf fscanf sscanf fopen fclose fread fwrite fgets fputs puts putchar getchar " +
                "gets fgetc fputc getc putc fflush fseek ftell rewind remove rename perror stdin stdout stderr FILE EOF " +
                "setbuf setvbuf tmpfile vprintf vfprintf vsprintf vsnprintf feof ferror clearerr fgetpos fsetpos ungetc " +
                "BUFSIZ SEEK_SET SEEK_CUR SEEK_END fpos_t fileno popen pclose getline",
        )
        val stdlib = set(
            "malloc calloc realloc free exit abort atexit atoi atol atoll atof strtol strtoul strtod strtof strtoll " +
                "strtoull rand srand qsort bsearch abs labs llabs div ldiv system getenv setenv EXIT_SUCCESS EXIT_FAILURE " +
                "RAND_MAX div_t ldiv_t mblen wctomb mbstowcs aligned_alloc _Exit quick_exit",
        )
        val str = set(
            "strlen strcpy strncpy strcat strncat strcmp strncmp strchr strrchr strstr strtok memcpy memmove memset " +
                "memcmp memchr strdup strndup strerror strspn strcspn strpbrk strcoll strxfrm strcasecmp strncasecmp strnlen strlcpy",
        )
        val math = set(
            "sqrt pow sin cos tan asin acos atan atan2 sinh cosh tanh exp log log10 log2 ceil floor fabs fmod round " +
                "trunc cbrt hypot M_PI M_E M_PI_2 M_SQRT2 INFINITY NAN HUGE_VAL isnan isinf isfinite fmin fmax lround llround " +
                "copysign ldexp frexp modf expm1 log1p sqrtf powf sinf cosf tanf fabsf floorf ceilf roundf fmodf atan2f " +
                "lrint rint nearbyint remainder erf tgamma lgamma signbit fma",
        )
        val ctype = set("isalpha isdigit isalnum isspace isupper islower isprint ispunct isxdigit iscntrl isgraph toupper tolower isblank")
        val time = set("time clock difftime mktime strftime localtime gmtime asctime ctime time_t clock_t tm CLOCKS_PER_SEC timespec nanosleep gettimeofday clock_gettime timeval")
        val limits = set("INT_MAX INT_MIN UINT_MAX LONG_MAX LONG_MIN ULONG_MAX CHAR_BIT CHAR_MAX CHAR_MIN SHRT_MAX SHRT_MIN LLONG_MAX LLONG_MIN SCHAR_MAX SCHAR_MIN UCHAR_MAX USHRT_MAX ULLONG_MAX")
        val stddef = set("size_t ptrdiff_t NULL offsetof nullptr_t wchar_t max_align_t byte ssize_t")
        val errno = set("errno EINVAL ENOENT ERANGE EACCES ENOMEM EEXIST EINTR EAGAIN EPERM EIO EBADF EDOM")
        val signal = set("signal raise SIGINT SIGTERM SIGSEGV SIGKILL SIGALRM SIGABRT SIGFPE SIGILL SIGHUP SIGPIPE sig_atomic_t SIG_IGN SIG_DFL")
        val assertH = set("assert static_assert NDEBUG")
        val stdbool = set("bool true false __bool_true_false_are_defined")
        val stdint = set("intptr_t uintptr_t intmax_t uintmax_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t " +
            "INT8_MAX INT16_MAX INT32_MAX INT64_MAX UINT8_MAX UINT16_MAX UINT32_MAX UINT64_MAX INT8_MIN INT16_MIN INT32_MIN INT64_MIN SIZE_MAX")
        val unistd = set("read write close sleep usleep fork getpid getppid getcwd chdir pipe dup dup2 execvp execv execl access unlink isatty lseek rmdir getuid sysconf STDIN_FILENO STDOUT_FILENO STDERR_FILENO environ")
        put("stdio.h", stdio); put("cstdio", stdio)
        put("stdlib.h", stdlib); put("cstdlib", stdlib)
        put("string.h", str); put("cstring", str)
        put("math.h", math); put("cmath", math)
        put("ctype.h", ctype); put("cctype", ctype)
        put("time.h", time); put("ctime", time)
        put("limits.h", limits); put("climits", limits)
        put("stddef.h", stddef); put("cstddef", stddef)
        put("errno.h", errno); put("cerrno", errno)
        put("signal.h", signal); put("csignal", signal)
        put("assert.h", assertH); put("cassert", assertH)
        put("stdbool.h", stdbool)
        put("stdint.h", stdint); put("cstdint", stdint); put("inttypes.h", stdint)
        put("unistd.h", unistd)
        put("iostream", set("cout cin cerr clog endl flush ostream istream iostream ws boolalpha noboolalpha hex dec oct ios ios_base wcout wcin"))
        put("iomanip", set("setw setprecision setfill setbase left right fixed scientific showpoint showpos put_time get_time quoted"))
        put("string", set("string wstring to_string stoi stol stoll stoul stod stof getline string_view u16string u32string npos char_traits basic_string to_wstring"))
        put("vector", set("vector"))
        put("map", set("map multimap"))
        put("unordered_map", set("unordered_map unordered_multimap"))
        put("set", set("set multiset"))
        put("unordered_set", set("unordered_set unordered_multiset"))
        put("queue", set("queue priority_queue"))
        put("stack", set("stack"))
        put("deque", set("deque"))
        put("list", set("list"))
        put("array", set("array"))
        put("bitset", set("bitset"))
        put("algorithm", set("sort stable_sort find find_if find_if_not reverse min max minmax swap fill count count_if copy copy_if transform unique lower_bound upper_bound binary_search for_each next_permutation prev_permutation remove remove_if all_of any_of none_of min_element max_element iter_swap rotate shuffle merge partition equal mismatch adjacent_find search replace clamp generate nth_element partial_sort is_sorted set_union set_intersection set_difference reverse_copy fill_n copy_n"))
        put("numeric", set("accumulate iota gcd lcm partial_sum inner_product adjacent_difference reduce transform_reduce exclusive_scan inclusive_scan"))
        put("memory", set("unique_ptr shared_ptr make_unique make_shared weak_ptr allocator enable_shared_from_this addressof uninitialized_copy"))
        put("functional", set("function bind less greater plus minus multiplies divides negate hash ref cref reference_wrapper equal_to not_equal_to greater_equal less_equal logical_and logical_or logical_not mem_fn invoke"))
        put("utility", set("pair make_pair move forward swap exchange declval get as_const index_sequence integer_sequence make_index_sequence in_place"))
        put("tuple", set("tuple make_tuple tie get forward_as_tuple tuple_cat tuple_size apply ignore"))
        put("fstream", set("ifstream ofstream fstream filebuf wifstream wofstream"))
        put("sstream", set("stringstream ostringstream istringstream stringbuf"))
        put("thread", set("thread this_thread jthread"))
        put("mutex", set("mutex lock_guard unique_lock scoped_lock recursive_mutex timed_mutex once_flag call_once condition_variable"))
        put("chrono", set("chrono"))
        put("random", set("mt19937 mt19937_64 random_device uniform_int_distribution uniform_real_distribution default_random_engine normal_distribution bernoulli_distribution minstd_rand discrete_distribution"))
        put("optional", set("optional nullopt make_optional nullopt_t bad_optional_access"))
        put("variant", set("variant visit holds_alternative get_if monostate bad_variant_access"))
        put("stdexcept", set("runtime_error logic_error invalid_argument out_of_range domain_error length_error range_error overflow_error underflow_error"))
        put("exception", set("exception terminate current_exception rethrow_exception exception_ptr bad_exception"))
        put("iterator", set("back_inserter front_inserter inserter istream_iterator ostream_iterator iterator distance advance next prev begin end rbegin rend make_move_iterator"))
        put("cassert", assertH)
        put("atomic", set("atomic atomic_int atomic_bool atomic_flag memory_order atomic_thread_fence"))
        put("type_traits", set("is_same enable_if conditional remove_reference remove_const decay is_integral is_floating_point is_pointer integral_constant true_type false_type underlying_type is_base_of is_convertible"))
        put("cstdarg", set("va_list va_start va_end va_arg va_copy"))
        put("stdarg.h", set("va_list va_start va_end va_arg va_copy"))
        put("typeinfo", set("typeid type_info bad_cast"))
        put("new", set("bad_alloc nothrow nothrow_t new_handler set_new_handler"))
        put("cfloat", set("FLT_MAX FLT_MIN DBL_MAX DBL_MIN FLT_EPSILON DBL_EPSILON LDBL_MAX"))
        put("float.h", set("FLT_MAX FLT_MIN DBL_MAX DBL_MIN FLT_EPSILON DBL_EPSILON LDBL_MAX"))
    }

    /** Library names C and C++ code may use without any include of its own (compiler keywords and builtins). */
    val C_IMPLICIT: Set<String> = set(
        "main argc argv envp std size_t nullptr NULL __func__ __FILE__ __LINE__ __cplusplus __VA_ARGS__ __attribute__ " +
            "__declspec __asm__ asm defined this self sizeof offsetof wchar_t char16_t char32_t",
    )

    val NODE_BUILTINS: Set<String> = set("fs path os util crypto child_process http https url events stream zlib readline assert buffer net dns querystring timers")
}

/** Small helpers shared by the checks. */
internal object DiagUtil {
    fun suggest(name: String, candidates: Collection<String>): String? {
        if (name.length < 3) return null
        val limit = if (name.length <= 4) 1 else 2
        var best: String? = null
        var bestD = limit + 1
        var tie = false
        for (cand in candidates) {
            if (cand == name || cand.length < 2 || kotlin.math.abs(cand.length - name.length) > limit) continue
            if (cand.startsWith("__") && !name.startsWith("__")) continue
            val d = distance(name, cand, limit)
            if (d < bestD) {
                bestD = d
                best = cand
                tie = false
            } else if (d == bestD && cand != best) {
                tie = true
            }
        }
        return if (best != null && !tie && bestD <= limit) best else null
    }

    /** Optimal-string-alignment distance (a swap of two neighbours counts as one edit). */
    fun distance(a: String, b: String, max: Int): Int {
        val la = a.length
        val lb = b.length
        val d = Array(la + 1) { IntArray(lb + 1) }
        for (i in 0..la) d[i][0] = i
        for (j in 0..lb) d[0][j] = j
        for (i in 1..la) {
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
            }
        }
        return if (d[la][lb] > max) max + 1 else d[la][lb]
    }

    private val WORD = Regex("[A-Za-z_\$][A-Za-z0-9_\$]*")

    fun wordsIn(s: String): Sequence<String> = WORD.findAll(s).map { it.value }

    fun isAllCaps(s: String): Boolean = s.length >= 2 && s.all { it.isUpperCase() || it.isDigit() || it == '_' } && s.any { it.isUpperCase() }
}
