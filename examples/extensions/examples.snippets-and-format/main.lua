-- Completion: return the words to offer. Only words that start with ctx.prefix are shown.
vsd.completion.register({
  languages = { "python", "lua" },
  provide = function(ctx)
    local words = {
      python = { "ifmain", "__name__ == \"__main__\":", "defmain", "printf" },
      lua = { "localfn", "function()", "pcall(function()" },
    }
    return words[ctx.language] or {}
  end,
})

-- Formatter: receives the whole text, returns the new text (or nil to leave it alone).
vsd.formatters.register({
  languages = "*",
  format = function(ctx)
    local lines = {}
    for line in (ctx.text .. "\n"):gmatch("([^\n]*)\n") do
      lines[#lines + 1] = (line:gsub("%s+$", ""))
    end
    while #lines > 1 and lines[#lines] == "" do lines[#lines] = nil end
    return table.concat(lines, "\n") .. "\n"
  end,
})
