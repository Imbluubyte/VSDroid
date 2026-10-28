-- Hello World: the smallest extension. It needs no permissions.
-- Run it from the command palette (Ctrl+Shift+P): "Hello World: Say hello".

vsd.commands.register("hello", {
  title = "Say hello",
  shortcut = "Ctrl+Alt+H",
  run = function()
    local last = vsd.storage.get("name", "")
    vsd.prompt("What is your name?", { value = last, placeholder = "Name" }, function(name)
      if name == nil or name == "" then return end  -- cancelled
      vsd.storage.set("name", name)                 -- remembered between runs
      vsd.toast("Hello, " .. name .. "!")
    end)
  end,
})

vsd.log("Hello World loaded (VSDroid API " .. vsd.api_version .. ")")
