import com.vsdroid.ide.core.*
import java.io.File

fun main(args: Array<String>) {
    val lang = Languages.forFile("x.sn")
    var bad = 0
    for (root in args) File(root).walkTopDown().filter { it.extension == "sn" }.forEach { f ->
        val text = f.readText()
        val ds = Diagnostics.analyze(text, lang)
        val isErr = f.path.contains("/errors/")
        if (!isErr && ds.isNotEmpty()) { bad++; println("FALSE POSITIVE ${f.path}"); ds.take(5).forEach { println("   ${it.line}: ${it.message}") } }
        if (isErr) println("${if (ds.isEmpty()) "MISSED " else "caught "} ${f.name}: ${ds.firstOrNull()?.message ?: ""}")
        Symbols.scan(text, lang)
    }
    println("false positives: $bad")
}
