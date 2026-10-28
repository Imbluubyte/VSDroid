-- TODO Finder: a problem checker plus a side panel.

local function find_todos(text)
  local found = {}
  local line_no = 0
  for line in (text .. "\n"):gmatch("([^\n]*)\n") do
    line_no = line_no + 1
    -- look for TODO / FIXME / HACK
    for _, w in ipairs({ "TODO", "FIXME", "HACK" }) do
      local a, b = line:find(w, 1, true)
      if a then
        found[#found + 1] = { line = line_no, col = a, end_col = #line, word = w, text = line:sub(a) }
        break
      end
    end
  end
  return found
end

-- Problems: shown as underlines and in the Problems tab. Columns are 1-based byte positions.
vsd.diagnostics.register({
  languages = "*",
  check = function(ctx)
    local out = {}
    for _, t in ipairs(find_todos(ctx.text)) do
      out[#out + 1] = {
        line = t.line, col = t.col, end_col = t.end_col,
        severity = (t.word == "FIXME") and "error" or "warning",
        message = t.word .. ": " .. t.text:sub(#t.word + 1):gsub("^[:%s]+", ""),
        code = "todo",
      }
    end
    return out
  end,
})

-- Side panel: the same list for the open file. The panel is a plain table the IDE draws.
vsd.panels.register("todos", {
  title = "TODOs",
  icon = "check",
  render = function()
    local text = vsd.editor.text()
    if not text then return { type = "text", text = "Open a file first.", style = "dim" } end
    local items = {}
    for _, t in ipairs(find_todos(text)) do
      items[#items + 1] = { label = t.text, detail = "line " .. t.line, action = "goto", payload = tostring(t.line) }
    end
    if #items == 0 then return { type = "text", text = "Nothing to do here.", style = "dim" } end
    return {
      type = "column",
      children = {
        { type = "text", text = #items .. " in " .. vsd.editor.path():match("[^/]+$"), style = "title" },
        { type = "list", items = items },
      },
    }
  end,
  on_action = function(action, payload)
    if action == "goto" then vsd.editor.goto_line(tonumber(payload)) end
  end,
})

-- Keep the panel fresh when you switch files or save.
local function refresh() vsd.panels.refresh("todos") end
vsd.events.on("editor.activated", refresh)
vsd.events.on("file.saved", refresh)

vsd.commands.register("show", { title = "Show TODO list", run = function() vsd.panels.show("todos") end })
