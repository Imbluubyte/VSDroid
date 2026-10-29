package com.vsdroid.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.IdeState
import com.vsdroid.ide.core.Diagnostic
import com.vsdroid.ide.core.Severity
import java.io.File

private sealed class Row2 {
    class Header(val path: String, val errors: Int, val warnings: Int) : Row2()
    class Item(val path: String, val d: Diagnostic) : Row2()
}

private enum class Filter { ALL, ERRORS, WARNINGS }

/** The Problems tab: every problem of the project, grouped by file, with the reason and a quick fix when there is one. */
@Composable
fun ProblemsPanel(ide: IdeState) {
    val p = ide.problems
    var filter by remember { mutableStateOf(Filter.ALL) }
    val root = ide.root
    Column(Modifier.fillMaxSize()) {
        SideHeader("Problems") {
            if (p.terminal.isNotEmpty()) SmallIconButton(Icons.Filled.Close, "Clear terminal errors", { p.clearTerminal() })
            SmallIconButton(Icons.Filled.Refresh, "Check the project again", { p.settingsChanged() })
        }
        if (!ide.settings.diagnostics) {
            Text(
                "Problem checking is turned off. Turn it on in Settings > General > Problems.",
                color = Vs.textDim, fontSize = 13.sp, modifier = Modifier.padding(14.dp),
            )
            return@Column
        }
        if (root == null) {
            Text("Open a project to see its problems.", color = Vs.textDim, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            return@Column
        }
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("All", filter == Filter.ALL) { filter = Filter.ALL }
            Chip("Errors ${p.errorCount}", filter == Filter.ERRORS) { filter = Filter.ERRORS }
            Chip("Warnings ${p.warningCount}", filter == Filter.WARNINGS) { filter = Filter.WARNINGS }
        }

        val rows = ArrayList<Row2>()
        for (path in p.filesWithProblems()) {
            val list = p.forFile(path)
                .filter {
                    when (filter) {
                        Filter.ALL -> true
                        Filter.ERRORS -> it.severity == Severity.ERROR
                        Filter.WARNINGS -> it.severity == Severity.WARNING
                    }
                }
                .sortedWith(compareBy({ it.line }, { it.column }))
            if (list.isEmpty()) continue
            rows.add(Row2.Header(path, list.count { it.severity == Severity.ERROR }, list.count { it.severity == Severity.WARNING }))
            list.forEach { rows.add(Row2.Item(path, it)) }
        }

        if (rows.isEmpty()) {
            Text(
                when {
                    p.scanning -> "Checking the project..."
                    filter == Filter.ALL -> "No problems found in the project."
                    else -> "Nothing to show with this filter."
                },
                color = Vs.textDim, fontSize = 13.sp, modifier = Modifier.padding(14.dp),
            )
            return@Column
        }
        if (p.scanning) Text("Checking the project...", color = Vs.textDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(rows) { row ->
                when (row) {
                    is Row2.Header -> {
                        val file = File(row.path)
                        Row(
                            Modifier.fillMaxWidth().background(Vs.hover).clickable { ide.openFile(file) }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                file.relativeToOrSelf(root).path, color = Vs.textBright, fontSize = 12.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (row.errors > 0) Text("${row.errors}", color = Vs.error, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                            if (row.warnings > 0) Text("${row.warnings}", color = Vs.warning, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    is Row2.Item -> {
                        val d = row.d
                        Column(
                            Modifier.fillMaxWidth().clickable { ide.openFile(File(row.path), d.line) }
                                .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    Modifier.padding(top = 5.dp, end = 8.dp).size(8.dp)
                                        .background(if (d.severity == Severity.ERROR) Vs.error else Vs.warning, CircleShape),
                                )
                                Text(d.message, color = Vs.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text("${d.line}:${d.column}", color = Vs.textDim, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
                            }
                            val fix = d.fix
                            val canDismiss = d.severity == Severity.WARNING
                            if (fix != null || canDismiss) {
                                Spacer(Modifier.size(4.dp))
                                Row(Modifier.padding(start = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (fix != null) {
                                        Text(
                                            fix.title, color = Color.White, fontSize = 12.sp,
                                            modifier = Modifier
                                                .background(Vs.accent, RoundedCornerShape(10.dp))
                                                .clickable { p.applyFix(row.path, d) }
                                                .padding(horizontal = 10.dp, vertical = 4.dp),
                                        )
                                    }
                                    if (canDismiss) {
                                        Text(
                                            "Dismiss", color = Vs.text, fontSize = 12.sp,
                                            modifier = Modifier
                                                .background(Vs.inputBg, RoundedCornerShape(10.dp))
                                                .clickable { p.dismiss(row.path, d) }
                                                .padding(horizontal = 10.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Vs.accent else Vs.inputBg, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = if (selected) Color.White else Vs.text, fontSize = 12.sp)
    }
}
