package com.vsdroid.ide.core

/*
 * Built-in knowledge for IntelliSense: the global functions, common types and standard modules of each language.
 * Plain text tables ("name(signature) -> returnType | description"), parsed once on first use.
 * A line "@Type" starts the members of Type; "@globals" holds the names available everywhere.
 */

class BuiltinEntry(val name: String, val sig: String, val returns: String?, val doc: String, val kind: SymKind) {
    val params: List<String>
        get() {
            val open = sig.indexOf('(')
            val close = sig.lastIndexOf(')')
            if (open < 0 || close <= open) return emptyList()
            return splitParams(sig.substring(open + 1, close))
        }

    private fun splitParams(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in s) {
            when (c) {
                '(', '[', '{', '<' -> depth++
                ')', ']', '}', '>' -> depth--
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }
}

object IntelliData {
    /** "py", "js", "cs", "java", "kt", "cpp" or "lua"; null for languages without tables. */
    fun family(lang: Language): String? = when (lang.id) {
        "python" -> "py"
        "javascript", "typescript" -> "js"
        "csharp" -> "cs"
        "java" -> "java"
        "kotlin" -> "kt"
        "cpp" -> "cpp"
        "lua" -> "lua"
        else -> null
    }

    private val parsed = HashMap<String, Map<String, List<BuiltinEntry>>>()

    @Synchronized
    private fun table(fam: String): Map<String, List<BuiltinEntry>> = parsed.getOrPut(fam) { parse(RAW[fam] ?: "") }

    private fun parse(raw: String): Map<String, List<BuiltinEntry>> {
        val out = LinkedHashMap<String, MutableList<BuiltinEntry>>()
        var cur: MutableList<BuiltinEntry>? = null
        for (line0 in raw.lines()) {
            val line = line0.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("@")) {
                cur = out.getOrPut(line.substring(1).trim()) { ArrayList() }
                continue
            }
            val c = cur ?: continue
            val bar = line.indexOf('|')
            val doc = if (bar >= 0) line.substring(bar + 1).trim() else ""
            var head = if (bar >= 0) line.substring(0, bar).trim() else line
            var ret: String? = null
            val arrow = head.indexOf("->")
            if (arrow >= 0) {
                ret = head.substring(arrow + 2).trim()
                head = head.substring(0, arrow).trim()
            }
            val paren = head.indexOf('(')
            val name = (if (paren >= 0) head.substring(0, paren) else head).trim()
            val kind = when {
                paren >= 0 -> SymKind.METHOD
                name.isNotEmpty() && name[0].isUpperCase() && name.all { it.isUpperCase() || it == '_' || it.isDigit() } -> SymKind.CONSTANT
                else -> SymKind.FIELD
            }
            c.add(BuiltinEntry(name, head, ret, doc, kind))
        }
        return out
    }

    /** Global functions and names of the language. */
    fun globals(fam: String): List<BuiltinEntry> = table(fam)["globals"] ?: emptyList()

    /** Members of a type or module such as "str", "math" or "os.path", after resolving aliases. */
    fun members(fam: String, type: String): List<BuiltinEntry>? {
        val t = table(fam)
        return t[type] ?: ALIASES[fam]?.get(type)?.let { t[it] }
    }

    fun hasType(fam: String, type: String) = members(fam, type) != null

    fun member(fam: String, type: String, name: String): BuiltinEntry? = members(fam, type)?.firstOrNull { it.name == name }

    fun global(fam: String, name: String): BuiltinEntry? = globals(fam).firstOrNull { it.name == name }

    /** All known type/module names (for completion after `import`). */
    fun typeNames(fam: String): List<String> = table(fam).keys.filter { it != "globals" }

    /** The type of a literal: kind is "string", "list", "dict", "int", "float", "bool". */
    fun literalType(fam: String, kind: String): String? = LITERALS[fam]?.get(kind)

    private val LITERALS = mapOf(
        "py" to mapOf("string" to "str", "list" to "list", "dict" to "dict", "int" to "int", "float" to "float", "bool" to "bool"),
        "js" to mapOf("string" to "String", "list" to "Array", "dict" to "Object", "int" to "Number", "float" to "Number", "bool" to "Boolean"),
        "cs" to mapOf("string" to "string", "int" to "int", "float" to "double", "bool" to "bool"),
        "java" to mapOf("string" to "String", "int" to "int", "float" to "double", "bool" to "boolean"),
        "kt" to mapOf("string" to "String", "int" to "Int", "float" to "Double", "bool" to "Boolean"),
        "cpp" to mapOf("string" to "string", "int" to "int", "float" to "double", "bool" to "bool"),
        "lua" to mapOf("string" to "string"),
    )

    private val ALIASES = mapOf(
        "py" to mapOf("List" to "list", "Dict" to "dict", "Set" to "set", "Tuple" to "tuple", "TextIOWrapper" to "file", "TextIO" to "file", "string" to "str", "String" to "str", "Integer" to "int"),
        "js" to mapOf("string" to "String", "number" to "Number", "boolean" to "Boolean", "object" to "Object"),
        "cs" to mapOf("String" to "string", "Int32" to "int", "IList" to "List", "IEnumerable" to "List", "ICollection" to "List", "IDictionary" to "Dictionary"),
        "java" to mapOf("ArrayList" to "List", "LinkedList" to "List", "Collection" to "List", "HashMap" to "Map", "TreeMap" to "Map", "LinkedHashMap" to "Map", "HashSet" to "Set", "Integer" to "Integer", "StringBuilder" to "StringBuilder"),
        "kt" to mapOf("MutableList" to "List", "ArrayList" to "List", "MutableMap" to "Map", "HashMap" to "Map", "MutableSet" to "Set", "CharSequence" to "String", "Collection" to "List", "Iterable" to "List", "Sequence" to "List"),
        "cpp" to mapOf("wstring" to "string", "std::string" to "string", "std::vector" to "vector", "std::map" to "map"),
    )

    private val RAW: Map<String, String> = mapOf("py" to PY, "js" to JS, "cs" to CS, "java" to JAVA, "kt" to KT, "cpp" to CPP, "lua" to LUA)
}

private const val PY = """
@globals
print(*values, sep=' ', end='\n', file=None, flush=False) -> None | Print values to the screen.
input(prompt='') -> str | Read a line from the keyboard.
len(obj) -> int | Number of items in a container.
range(start, stop, step=1) -> list | A sequence of integers.
open(file, mode='r', encoding=None) -> file | Open a file.
int(x=0, base=10) -> int | Convert to an integer.
float(x=0.0) -> float | Convert to a floating point number.
str(object='') -> str | Convert to a string.
bool(x=False) -> bool | Convert to True or False.
list(iterable=()) -> list | Make a list.
dict(**kwargs) -> dict | Make a dictionary.
set(iterable=()) -> set | Make a set.
tuple(iterable=()) -> tuple | Make a tuple.
abs(x) -> int | Absolute value.
round(number, ndigits=None) -> int | Round a number.
sum(iterable, start=0) -> int | Add up all items.
min(iterable, *, key=None) -> int | Smallest item.
max(iterable, *, key=None) -> int | Largest item.
sorted(iterable, *, key=None, reverse=False) -> list | A new sorted list.
reversed(seq) -> list | Iterate backwards.
enumerate(iterable, start=0) -> list | Pairs of (index, item).
zip(*iterables) -> list | Combine iterables item by item.
map(function, iterable) -> list | Apply a function to every item.
filter(function, iterable) -> list | Keep items where function is true.
any(iterable) -> bool | True if any item is true.
all(iterable) -> bool | True if all items are true.
isinstance(obj, classinfo) -> bool | Is obj an instance of a class?
type(object) -> type | The type of an object.
chr(i) -> str | Character for a code point.
ord(c) -> int | Code point of a character.
repr(object) -> str | Developer-friendly text of an object.
format(value, format_spec='') -> str | Format a value.
getattr(object, name, default=None) | Get an attribute by name.
setattr(object, name, value) -> None | Set an attribute by name.
hasattr(object, name) -> bool | Does the object have this attribute?
divmod(a, b) -> tuple | Quotient and remainder.
pow(base, exp, mod=None) -> int | Raise to a power.
hex(x) -> str | Hexadecimal text of an integer.
bin(x) -> str | Binary text of an integer.
@str
upper() -> str | Upper-case copy.
lower() -> str | Lower-case copy.
strip(chars=None) -> str | Remove whitespace (or chars) from both ends.
lstrip(chars=None) -> str | Remove from the left end.
rstrip(chars=None) -> str | Remove from the right end.
split(sep=None, maxsplit=-1) -> list | Split into a list of strings.
join(iterable) -> str | Join strings with this one in between.
replace(old, new, count=-1) -> str | Replace text.
find(sub, start=0, end=None) -> int | Index of sub, or -1.
index(sub, start=0, end=None) -> int | Index of sub, error if missing.
count(sub) -> int | Count occurrences.
startswith(prefix) -> bool | Does it start with prefix?
endswith(suffix) -> bool | Does it end with suffix?
format(*args, **kwargs) -> str | Fill in {} placeholders.
isdigit() -> bool | Only digits?
isalpha() -> bool | Only letters?
isalnum() -> bool | Only letters and digits?
isspace() -> bool | Only whitespace?
title() -> str | Title Case copy.
capitalize() -> str | First letter upper-case.
splitlines(keepends=False) -> list | Split at line breaks.
zfill(width) -> str | Pad with zeros on the left.
center(width, fillchar=' ') -> str | Centre in a field.
encode(encoding='utf-8') -> bytes | Convert to bytes.
@list
append(x) -> None | Add an item at the end.
extend(iterable) -> None | Add all items of iterable.
insert(i, x) -> None | Insert at index i.
remove(x) -> None | Remove the first x.
pop(i=-1) | Remove and return item i.
index(x) -> int | Index of the first x.
count(x) -> int | Count occurrences.
sort(*, key=None, reverse=False) -> None | Sort in place.
reverse() -> None | Reverse in place.
copy() -> list | Shallow copy.
clear() -> None | Remove all items.
@dict
keys() -> list | The keys.
values() -> list | The values.
items() -> list | (key, value) pairs.
get(key, default=None) | Value for key, or default.
pop(key, default=None) | Remove key and return its value.
update(other) -> None | Merge another dict.
setdefault(key, default=None) | Get key, adding it when missing.
copy() -> dict | Shallow copy.
clear() -> None | Remove all items.
@set
add(x) -> None | Add an item.
remove(x) -> None | Remove an item (error if missing).
discard(x) -> None | Remove an item if present.
union(*others) -> set | Items in any set.
intersection(*others) -> set | Items in all sets.
difference(*others) -> set | Items only in this set.
pop() | Remove and return an item.
clear() -> None | Remove all items.
@tuple
count(x) -> int | Count occurrences.
index(x) -> int | Index of the first x.
@int
bit_length() -> int | Bits needed to represent it.
to_bytes(length, byteorder) -> bytes | Convert to bytes.
@float
is_integer() -> bool | True when it has no fraction.
@file
read(size=-1) -> str | Read the whole file (or size characters).
readline() -> str | Read one line.
readlines() -> list | Read all lines.
write(s) -> int | Write text.
close() -> None | Close the file.
@math
pi | The constant pi.
e | Euler's number.
sqrt(x) -> float | Square root.
sin(x) -> float | Sine.
cos(x) -> float | Cosine.
tan(x) -> float | Tangent.
floor(x) -> int | Round down.
ceil(x) -> int | Round up.
pow(x, y) -> float | x to the power y.
log(x, base=e) -> float | Logarithm.
exp(x) -> float | e to the power x.
fabs(x) -> float | Absolute value.
gcd(a, b) -> int | Greatest common divisor.
factorial(n) -> int | n factorial.
radians(x) -> float | Degrees to radians.
degrees(x) -> float | Radians to degrees.
hypot(*coords) -> float | Euclidean distance.
@random
random() -> float | Random float in [0, 1).
randint(a, b) -> int | Random integer from a to b, inclusive.
choice(seq) | Random item of a sequence.
shuffle(x) -> None | Shuffle a list in place.
uniform(a, b) -> float | Random float between a and b.
sample(population, k) -> list | k unique random items.
seed(a=None) -> None | Start the generator from a seed.
randrange(start, stop=None, step=1) -> int | Random item of range().
@os
getcwd() -> str | Current directory.
listdir(path='.') -> list | Names in a directory.
mkdir(path) -> None | Make a directory.
makedirs(name, exist_ok=False) -> None | Make directories recursively.
remove(path) -> None | Delete a file.
rename(src, dst) -> None | Rename a file.
system(command) -> int | Run a shell command.
getenv(key, default=None) -> str | Read an environment variable.
walk(top) -> list | Walk a directory tree.
path | The os.path module.
environ | Environment variables.
sep | Path separator.
@os.path
join(*paths) -> str | Join path parts.
exists(path) -> bool | Does the path exist?
isfile(path) -> bool | Is it a file?
isdir(path) -> bool | Is it a directory?
basename(path) -> str | Last part of a path.
dirname(path) -> str | Folder part of a path.
splitext(path) -> tuple | Split off the extension.
abspath(path) -> str | Absolute path.
getsize(path) -> int | Size in bytes.
@sys
argv | Command line arguments.
exit(code=0) -> None | Stop the program.
path | Module search path.
platform | Operating system name.
version | Python version text.
stdin | Standard input.
stdout | Standard output.
stderr | Standard error.
@json
dumps(obj, indent=None) -> str | Convert to JSON text.
loads(s) | Parse JSON text.
dump(obj, fp, indent=None) -> None | Write JSON to a file.
load(fp) | Read JSON from a file.
@time
time() -> float | Seconds since 1970.
sleep(seconds) -> None | Pause the program.
perf_counter() -> float | High resolution timer.
strftime(format, t=None) -> str | Format a time.
@re
match(pattern, string) | Match at the start of a string.
search(pattern, string) | Find the first match.
findall(pattern, string) -> list | All matches.
sub(pattern, repl, string) -> str | Replace matches.
split(pattern, string) -> list | Split at matches.
compile(pattern) | Compile a pattern.
"""

private const val JS = """
@globals
console | The console object.
Math | Math functions.
JSON | JSON parsing and writing.
Object | Object helpers.
Array | Array helpers.
Promise | Promises.
parseInt(string, radix) -> Number | Parse an integer.
parseFloat(string) -> Number | Parse a floating point number.
isNaN(value) -> Boolean | Is it NaN?
setTimeout(callback, ms) -> Number | Run later.
setInterval(callback, ms) -> Number | Run repeatedly.
clearTimeout(id) -> undefined | Cancel a timeout.
clearInterval(id) -> undefined | Cancel an interval.
fetch(url, options) -> Promise | HTTP request.
require(module) | Load a CommonJS module.
String(value) -> String | Convert to a string.
Number(value) -> Number | Convert to a number.
Boolean(value) -> Boolean | Convert to a boolean.
@console
log(...data) -> undefined | Print to the console.
error(...data) -> undefined | Print an error.
warn(...data) -> undefined | Print a warning.
info(...data) -> undefined | Print information.
table(data) -> undefined | Print a table.
time(label) -> undefined | Start a timer.
timeEnd(label) -> undefined | Stop a timer and print it.
@Math
PI | The constant pi.
E | Euler's number.
abs(x) -> Number | Absolute value.
floor(x) -> Number | Round down.
ceil(x) -> Number | Round up.
round(x) -> Number | Round to nearest.
max(...values) -> Number | Largest value.
min(...values) -> Number | Smallest value.
pow(x, y) -> Number | x to the power y.
sqrt(x) -> Number | Square root.
random() -> Number | Random number in [0, 1).
sin(x) -> Number | Sine.
cos(x) -> Number | Cosine.
trunc(x) -> Number | Remove the fraction.
@JSON
stringify(value, replacer, space) -> String | Convert to JSON text.
parse(text) | Parse JSON text.
@Object
keys(obj) -> Array | Own property names.
values(obj) -> Array | Own property values.
entries(obj) -> Array | [key, value] pairs.
assign(target, ...sources) | Copy properties.
freeze(obj) | Make an object read-only.
fromEntries(entries) -> Object | Build an object from pairs.
@Promise
all(promises) -> Promise | Wait for all.
race(promises) -> Promise | First to finish.
resolve(value) -> Promise | A resolved promise.
reject(reason) -> Promise | A rejected promise.
then(onFulfilled, onRejected) -> Promise | Run when resolved.
catch(onRejected) -> Promise | Run when rejected.
finally(onFinally) -> Promise | Run when settled.
@String
length | Number of characters.
toUpperCase() -> String | Upper-case copy.
toLowerCase() -> String | Lower-case copy.
trim() -> String | Remove whitespace from both ends.
split(separator, limit) -> Array | Split into an array.
slice(start, end) -> String | Part of the string.
substring(start, end) -> String | Part of the string.
indexOf(search, from) -> Number | Index of search, or -1.
includes(search) -> Boolean | Does it contain search?
startsWith(search) -> Boolean | Does it start with search?
endsWith(search) -> Boolean | Does it end with search?
replace(pattern, replacement) -> String | Replace the first match.
replaceAll(pattern, replacement) -> String | Replace all matches.
charAt(index) -> String | Character at index.
padStart(length, pad) -> String | Pad on the left.
padEnd(length, pad) -> String | Pad on the right.
repeat(count) -> String | Repeat the text.
@Number
toFixed(digits) -> String | Format with fixed decimals.
toString(radix) -> String | Convert to text.
@Array
length | Number of items.
push(...items) -> Number | Add items at the end.
pop() | Remove and return the last item.
shift() | Remove and return the first item.
unshift(...items) -> Number | Add items at the start.
map(callback) -> Array | Transform every item.
filter(callback) -> Array | Keep items where callback is true.
reduce(callback, initial) | Fold into one value.
forEach(callback) -> undefined | Run for every item.
find(callback) | First item where callback is true.
findIndex(callback) -> Number | Index of the first match.
indexOf(item) -> Number | Index of item, or -1.
includes(item) -> Boolean | Does it contain item?
join(separator) -> String | Join into text.
slice(start, end) -> Array | Part of the array.
splice(start, deleteCount, ...items) -> Array | Remove or insert items.
sort(compare) -> Array | Sort in place.
reverse() -> Array | Reverse in place.
concat(...arrays) -> Array | Join arrays.
some(callback) -> Boolean | Is any item a match?
every(callback) -> Boolean | Are all items a match?
flat(depth) -> Array | Flatten nested arrays.
isArray(value) -> Boolean | Is it an array?
@Map
get(key) | Value for key.
set(key, value) -> Map | Store a value.
has(key) -> Boolean | Is key present?
delete(key) -> Boolean | Remove key.
size | Number of entries.
"""

private const val CS = """
@globals
Console | Console input and output.
Math | Math functions.
Convert | Type conversion.
String | String helpers.
Environment | The environment.
@Console
WriteLine(value) -> void | Print a line.
Write(value) -> void | Print without a line break.
ReadLine() -> string | Read a line.
ReadKey() | Read a key press.
Clear() -> void | Clear the console.
@Math
PI | The constant pi.
Abs(value) -> double | Absolute value.
Max(a, b) -> double | Larger value.
Min(a, b) -> double | Smaller value.
Pow(x, y) -> double | x to the power y.
Sqrt(d) -> double | Square root.
Floor(d) -> double | Round down.
Ceiling(d) -> double | Round up.
Round(d) -> double | Round to nearest.
@Convert
ToInt32(value) -> int | Convert to an integer.
ToDouble(value) -> double | Convert to a double.
ToString(value) -> string | Convert to text.
@string
Length | Number of characters.
ToUpper() -> string | Upper-case copy.
ToLower() -> string | Lower-case copy.
Trim() -> string | Remove whitespace from both ends.
Split(separator) -> string[] | Split into parts.
Substring(startIndex, length) -> string | Part of the string.
Contains(value) -> bool | Does it contain value?
StartsWith(value) -> bool | Does it start with value?
EndsWith(value) -> bool | Does it end with value?
Replace(oldValue, newValue) -> string | Replace text.
IndexOf(value) -> int | Index of value, or -1.
Join(separator, values) -> string | Join strings.
IsNullOrEmpty(value) -> bool | Is it null or empty?
@List
Count | Number of items.
Add(item) -> void | Add an item.
Remove(item) -> bool | Remove an item.
Contains(item) -> bool | Does it contain item?
Clear() -> void | Remove all items.
Sort() -> void | Sort in place.
Reverse() -> void | Reverse in place.
IndexOf(item) -> int | Index of item, or -1.
Where(predicate) -> List | Keep matching items.
Select(selector) -> List | Transform items.
OrderBy(keySelector) -> List | Sort by key.
ToList() -> List | Copy into a list.
ToArray() -> array | Copy into an array.
FirstOrDefault() | First item or default.
Any() -> bool | Is there any item?
Sum() -> int | Add up all items.
Max() | Largest item.
Min() | Smallest item.
@Dictionary
Count | Number of entries.
Add(key, value) -> void | Add an entry.
Remove(key) -> bool | Remove an entry.
ContainsKey(key) -> bool | Is key present?
TryGetValue(key, out value) -> bool | Get a value if present.
Keys | The keys.
Values | The values.
"""

private const val JAVA = """
@globals
System | System access.
Math | Math functions.
String | String helpers.
Integer | Integer helpers.
Arrays | Array helpers.
@System
out | Standard output stream.
in | Standard input stream.
currentTimeMillis() -> long | Milliseconds since 1970.
exit(status) -> void | Stop the program.
@System.out
println(x) -> void | Print a line.
print(x) -> void | Print without a line break.
printf(format, args) -> void | Print formatted text.
@Math
PI | The constant pi.
abs(a) -> int | Absolute value.
max(a, b) -> int | Larger value.
min(a, b) -> int | Smaller value.
pow(a, b) -> double | a to the power b.
sqrt(a) -> double | Square root.
floor(a) -> double | Round down.
ceil(a) -> double | Round up.
round(a) -> long | Round to nearest.
random() -> double | Random number in [0, 1).
@Integer
parseInt(s) -> int | Parse an integer.
valueOf(s) -> Integer | Convert to an Integer.
toString(i) -> String | Convert to text.
MAX_VALUE | Largest int.
MIN_VALUE | Smallest int.
@Arrays
toString(a) -> String | Text of an array.
sort(a) -> void | Sort an array.
asList(a) -> List | View an array as a list.
fill(a, val) -> void | Fill an array.
@String
length() -> int | Number of characters.
charAt(index) -> char | Character at index.
substring(begin, end) -> String | Part of the string.
indexOf(str) -> int | Index of str, or -1.
equals(other) -> boolean | Same text?
equalsIgnoreCase(other) -> boolean | Same text ignoring case?
toUpperCase() -> String | Upper-case copy.
toLowerCase() -> String | Lower-case copy.
trim() -> String | Remove whitespace from both ends.
split(regex) -> String[] | Split into parts.
contains(s) -> boolean | Does it contain s?
startsWith(prefix) -> boolean | Does it start with prefix?
endsWith(suffix) -> boolean | Does it end with suffix?
replace(target, replacement) -> String | Replace text.
isEmpty() -> boolean | Is it empty?
format(format, args) -> String | Formatted text.
join(delimiter, elements) -> String | Join strings.
@List
size() -> int | Number of items.
get(index) | Item at index.
add(item) -> boolean | Add an item.
remove(index) | Remove an item.
contains(item) -> boolean | Does it contain item?
isEmpty() -> boolean | Is it empty?
clear() -> void | Remove all items.
indexOf(item) -> int | Index of item, or -1.
set(index, item) | Replace an item.
sort(comparator) -> void | Sort the list.
stream() | A stream of the items.
@Map
size() -> int | Number of entries.
get(key) | Value for key.
put(key, value) | Store a value.
containsKey(key) -> boolean | Is key present?
remove(key) | Remove an entry.
keySet() -> Set | The keys.
values() -> List | The values.
entrySet() -> Set | The entries.
getOrDefault(key, defaultValue) | Value for key, or a default.
@Set
size() -> int | Number of items.
add(item) -> boolean | Add an item.
contains(item) -> boolean | Does it contain item?
remove(item) -> boolean | Remove an item.
@StringBuilder
append(x) -> StringBuilder | Add text.
toString() -> String | The text built so far.
length() -> int | Number of characters.
reverse() -> StringBuilder | Reverse the text.
insert(offset, x) -> StringBuilder | Insert text.
"""

private const val KT = """
@globals
println(message) -> Unit | Print a line.
print(message) -> Unit | Print without a line break.
readLine() -> String | Read a line.
listOf(vararg elements) -> List | An immutable list.
mutableListOf(vararg elements) -> List | A mutable list.
mapOf(vararg pairs) -> Map | An immutable map.
mutableMapOf(vararg pairs) -> Map | A mutable map.
setOf(vararg elements) -> Set | An immutable set.
arrayOf(vararg elements) -> Array | An array.
require(value, lazyMessage) -> Unit | Throw if false.
check(value) -> Unit | Throw IllegalStateException if false.
error(message) -> Nothing | Throw an IllegalStateException.
repeat(times, action) -> Unit | Run an action several times.
@String
length | Number of characters.
uppercase() -> String | Upper-case copy.
lowercase() -> String | Lower-case copy.
trim() -> String | Remove whitespace from both ends.
split(vararg delimiters) -> List | Split into parts.
substring(startIndex, endIndex) -> String | Part of the string.
contains(other) -> Boolean | Does it contain other?
startsWith(prefix) -> Boolean | Does it start with prefix?
endsWith(suffix) -> Boolean | Does it end with suffix?
replace(oldValue, newValue) -> String | Replace text.
toInt() -> Int | Parse as an integer.
toIntOrNull() -> Int | Parse as an integer or null.
toDouble() -> Double | Parse as a double.
isEmpty() -> Boolean | Is it empty?
isBlank() -> Boolean | Only whitespace?
padStart(length, padChar) -> String | Pad on the left.
reversed() -> String | Reversed copy.
@List
size | Number of items.
get(index) | Item at index.
add(element) -> Boolean | Add an item (mutable lists).
remove(element) -> Boolean | Remove an item (mutable lists).
contains(element) -> Boolean | Does it contain element?
isEmpty() -> Boolean | Is it empty?
first() | First item.
last() | Last item.
map(transform) -> List | Transform every item.
filter(predicate) -> List | Keep matching items.
forEach(action) -> Unit | Run for every item.
sorted() -> List | Sorted copy.
sortedBy(selector) -> List | Sorted by key.
joinToString(separator) -> String | Join into text.
sum() -> Int | Add up all items.
any(predicate) -> Boolean | Is any item a match?
all(predicate) -> Boolean | Are all items a match?
indexOf(element) -> Int | Index of element, or -1.
take(n) -> List | First n items.
@Map
size | Number of entries.
get(key) | Value for key.
put(key, value) | Store a value.
containsKey(key) -> Boolean | Is key present?
keys | The keys.
values | The values.
getOrDefault(key, defaultValue) | Value for key, or a default.
forEach(action) -> Unit | Run for every entry.
@Int
toString() -> String | Convert to text.
toDouble() -> Double | Convert to a double.
coerceIn(min, max) -> Int | Limit to a range.
"""

private const val CPP = """
@globals
printf(format, ...) -> int | Print formatted text.
scanf(format, ...) -> int | Read formatted input.
puts(s) -> int | Print a line.
malloc(size) | Allocate memory.
free(ptr) -> void | Release memory.
strlen(s) -> size_t | Length of a C string.
strcpy(dest, src) | Copy a C string.
strcmp(a, b) -> int | Compare C strings.
memcpy(dest, src, n) | Copy memory.
sqrt(x) -> double | Square root.
pow(x, y) -> double | x to the power y.
abs(x) -> int | Absolute value.
rand() -> int | Random number.
exit(status) -> void | Stop the program.
std | The standard library namespace.
@std
cout | Standard output stream.
cin | Standard input stream.
cerr | Standard error stream.
endl | End of line.
string | Text type.
vector | Dynamic array.
map | Sorted key/value map.
unordered_map | Hash map.
set | Sorted set.
sort(first, last) -> void | Sort a range.
min(a, b) | Smaller value.
max(a, b) | Larger value.
swap(a, b) -> void | Swap two values.
to_string(value) -> string | Convert to text.
make_unique(args) | Create a unique_ptr.
make_shared(args) | Create a shared_ptr.
getline(stream, str) | Read a line.
@string
length() -> size_t | Number of characters.
size() -> size_t | Number of characters.
empty() -> bool | Is it empty?
substr(pos, len) -> string | Part of the string.
find(str) -> size_t | Index of str, or npos.
append(str) -> string | Add text.
push_back(c) -> void | Add a character.
c_str() | The C string.
clear() -> void | Remove all characters.
erase(pos, len) -> string | Remove characters.
replace(pos, len, str) -> string | Replace characters.
@vector
size() -> size_t | Number of items.
empty() -> bool | Is it empty?
push_back(value) -> void | Add an item at the end.
pop_back() -> void | Remove the last item.
back() | The last item.
front() | The first item.
at(pos) | Item at pos, checked.
clear() -> void | Remove all items.
begin() | Iterator to the start.
end() | Iterator to the end.
insert(pos, value) | Insert an item.
erase(pos) | Remove an item.
resize(n) -> void | Change the size.
@map
size() -> size_t | Number of entries.
empty() -> bool | Is it empty?
find(key) | Find an entry.
count(key) -> size_t | 1 if the key is present.
insert(value) | Insert an entry.
erase(key) -> size_t | Remove an entry.
at(key) | Value for key, checked.
clear() -> void | Remove all entries.
"""

private const val LUA = """
@globals
print(...) -> nil | Print values.
type(v) -> string | The type name of a value.
tostring(v) -> string | Convert to a string.
tonumber(v, base) -> number | Convert to a number.
pairs(t) | Iterate over all keys of a table.
ipairs(t) | Iterate over the array part of a table.
require(modname) | Load a module.
setmetatable(table, metatable) -> table | Set a metatable.
getmetatable(object) | Get a metatable.
pcall(f, ...) -> boolean | Call in protected mode.
error(message, level) -> nil | Raise an error.
assert(v, message) | Raise an error if v is false.
select(n, ...) | Select from the arguments.
unpack(list) | Unpack a table into values.
string | String library.
table | Table library.
math | Math library.
os | Operating system library.
io | Input and output library.
@string
format(formatstring, ...) -> string | Formatted text.
sub(s, i, j) -> string | Part of a string.
len(s) -> number | Length of a string.
upper(s) -> string | Upper-case copy.
lower(s) -> string | Lower-case copy.
find(s, pattern, init, plain) -> number | Find a pattern.
match(s, pattern, init) -> string | Match a pattern.
gmatch(s, pattern) | Iterate over matches.
gsub(s, pattern, repl, n) -> string | Replace matches.
rep(s, n, sep) -> string | Repeat a string.
reverse(s) -> string | Reverse a string.
byte(s, i, j) -> number | Character codes.
char(...) -> string | String from character codes.
@table
insert(list, pos, value) -> nil | Insert a value.
remove(list, pos) | Remove a value.
concat(list, sep, i, j) -> string | Join values into a string.
sort(list, comp) -> nil | Sort in place.
unpack(list, i, j) | Unpack a table into values.
@math
pi | The constant pi.
huge | Infinity.
floor(x) -> number | Round down.
ceil(x) -> number | Round up.
abs(x) -> number | Absolute value.
max(x, ...) -> number | Largest value.
min(x, ...) -> number | Smallest value.
sqrt(x) -> number | Square root.
random(m, n) -> number | Random number.
randomseed(x) -> nil | Seed the generator.
sin(x) -> number | Sine.
cos(x) -> number | Cosine.
@os
time(table) -> number | Current time.
clock() -> number | CPU time used.
date(format, time) -> string | Formatted date.
getenv(varname) -> string | Read an environment variable.
exit(code) -> nil | Stop the program.
remove(filename) | Delete a file.
@io
write(...) -> file | Write to standard output.
read(format) | Read from standard input.
open(filename, mode) -> file | Open a file.
lines(filename) | Iterate over lines of a file.
@file
read(format) | Read from the file.
write(...) -> file | Write to the file.
lines() | Iterate over lines.
close() -> boolean | Close the file.
"""
