extends SceneTree
# [CI_GODOT] "Compile check" do cliente: carrega TODO .gd do projeto pra pegar erro de PARSE/sintaxe.
# GDScript não tem compilador — um typo numa tela que ninguém abriu só crasha em runtime. Este script
# percorre res:// e dá load() em cada .gd; load() de um script com erro de parse imprime "SCRIPT ERROR"
# e volta null. Sai 1 se algum falhar, 0 se todos OK. Rodado no CI:
#   godot --headless --path godot-client --script res://ci_check.gd
# Não é referenciado pelo jogo (tool de CI). Desenho: docs/ + CLAUDE.md [CI_GODOT].

func _initialize() -> void:
	var failed: Array[String] = []
	var checked := 0
	var stack: Array[String] = ["res://"]
	while not stack.is_empty():
		var path: String = stack.pop_back()
		var dir := DirAccess.open(path)
		if dir == null:
			continue
		dir.list_dir_begin()
		var entry := dir.get_next()
		while entry != "":
			if not entry.begins_with("."):   # pula .godot / .git / ocultos
				var full := path.path_join(entry)
				if dir.current_is_dir():
					stack.push_back(full)
				elif entry.ends_with(".gd"):
					checked += 1
					var res := load(full)   # erro de parse → imprime SCRIPT ERROR e volta null
					if res == null:
						failed.append(full)
			entry = dir.get_next()
		dir.list_dir_end()
	print("──────────────────────────────────────────")
	print("ci_check: %d scripts verificados" % checked)
	if failed.is_empty():
		print("✓ todos os scripts carregaram sem erro de parse")
		quit(0)
	else:
		printerr("✗ %d script(s) com erro de parse:" % failed.size())
		for f in failed:
			printerr("   - " + f)
		quit(1)
