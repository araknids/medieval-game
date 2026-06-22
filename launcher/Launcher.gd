extends Control
# ── Launcher / auto-updater do MedievalBattle [DISTRIB_UPDATE] ──────────────────────────
# Fluxo: lê o MANIFESTO (versão + URL do build) → compara com o que está instalado → se mudou,
# BAIXA o zip, EXTRAI e salva a versão → abre o JOGO. Offline mas com jogo instalado → abre o que tem.
# Distribuição FORA da Steam: o jogador baixa ESTE launcher (1 .exe) e ele mantém o jogo atualizado.
# (Na Steam, a própria Steam atualiza — o launcher é só pro canal direto/itch/teste.)
#
# Release: exporta o jogo (Embed PCK) → zipa → sobe o zip num GitHub Release → atualiza o manifest.json
# (version + url) e dá push. Ver launcher/README.md.

# ⚠️ EDITE: aponte pro manifesto que VOCÊ publica (ex.: raw do repo, ou um asset "latest" de Release).
const MANIFEST_URL := "https://raw.githubusercontent.com/araknids/medieval-game/main/launcher/manifest.json"
const PLATFORM_KEY := "windows"            # windows | linux | macos (este build é Windows)

const GAME_DIR := "user://game"            # onde o jogo fica instalado (gravável)
const VERSION_FILE := "user://installed_version.txt"
const ZIP_TMP := "user://download.zip"

var _status: Label
var _bar: ProgressBar
var _retry: Button
var _http: HTTPRequest
var _downloading := false
var _exe_rel := ""                         # caminho do exe DENTRO do GAME_DIR (vem do manifesto)

func _ready() -> void:
	_build_ui()
	_http = HTTPRequest.new()
	add_child(_http)
	_start()

func _build_ui() -> void:
	var bg := ColorRect.new()
	bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	bg.color = Color(0.08, 0.075, 0.09)
	add_child(bg)
	var v := VBoxContainer.new()
	v.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	v.alignment = BoxContainer.ALIGNMENT_CENTER
	v.add_theme_constant_override("separation", 14)
	v.offset_left = 40; v.offset_right = -40
	add_child(v)
	var title := Label.new()
	title.text = "MedievalBattle"
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	title.add_theme_font_size_override("font_size", 30)
	title.add_theme_color_override("font_color", Color(0.86, 0.72, 0.36))
	v.add_child(title)
	_status = Label.new()
	_status.text = "Iniciando…"
	_status.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_status.add_theme_color_override("font_color", Color(0.8, 0.8, 0.82))
	v.add_child(_status)
	_bar = ProgressBar.new()
	_bar.min_value = 0; _bar.max_value = 100; _bar.value = 0
	_bar.show_percentage = true
	_bar.custom_minimum_size = Vector2(0, 18)
	v.add_child(_bar)
	_retry = Button.new()
	_retry.text = "Tentar de novo"
	_retry.visible = false
	_retry.pressed.connect(_start)
	v.add_child(_retry)

func _set_status(t: String) -> void:
	if is_instance_valid(_status):
		_status.text = t

# ── Fluxo ───────────────────────────────────────────────────────────────────────────
func _start() -> void:
	_retry.visible = false
	_bar.value = 0
	_set_status("Verificando atualizações…")
	_fetch_manifest()

func _fetch_manifest() -> void:
	if _http.request(MANIFEST_URL) != OK:
		_offline_fallback("Não consegui contatar o servidor de atualização.")
		return
	var res = await _http.request_completed
	# res = [result, code, headers, body]
	if int(res[0]) != HTTPRequest.RESULT_SUCCESS or int(res[1]) != 200:
		_offline_fallback("Sem conexão com o servidor de atualização.")
		return
	var data = JSON.parse_string((res[3] as PackedByteArray).get_string_from_utf8())
	if not (data is Dictionary):
		_offline_fallback("Manifesto inválido.")
		return
	var latest := str(data.get("version", ""))
	var plat = data.get(PLATFORM_KEY, {})
	if not (plat is Dictionary) or str(plat.get("url", "")) == "":
		_offline_fallback("Manifesto sem build p/ esta plataforma.")
		return
	_exe_rel = str(plat.get("exe", "MedievalBattle.exe"))
	var installed := _installed_version()
	if latest != "" and latest != installed:
		_set_status("Baixando versão %s…" % latest)
		_download(str(plat["url"]), latest)
	else:
		_set_status("Tudo atualizado (v%s)." % installed)
		_launch_game()

func _download(url: String, version: String) -> void:
	DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path(GAME_DIR))
	_http.download_file = ProjectSettings.globalize_path(ZIP_TMP)
	if _http.request(url) != OK:
		_offline_fallback("Falha ao iniciar o download.")
		return
	_downloading = true
	var res = await _http.request_completed
	_downloading = false
	_http.download_file = ""
	if int(res[0]) != HTTPRequest.RESULT_SUCCESS or int(res[1]) != 200:
		_offline_fallback("Download falhou (code %s)." % str(res[1]))
		return
	_set_status("Instalando…")
	_bar.value = 100
	if not _extract(ZIP_TMP, GAME_DIR):
		_offline_fallback("Falha ao extrair o build.")
		return
	DirAccess.remove_absolute(ProjectSettings.globalize_path(ZIP_TMP))
	_write_version(version)
	_launch_game()

# Mostra o progresso do download enquanto baixa
func _process(_dt: float) -> void:
	if _downloading and is_instance_valid(_bar):
		var total := _http.get_body_size()
		var got := _http.get_downloaded_bytes()
		if total > 0:
			_bar.value = clampf(float(got) / float(total) * 100.0, 0.0, 100.0)

# ── ZIP → disco ───────────────────────────────────────────────────────────────────────
func _extract(zip_path: String, dest_dir: String) -> bool:
	var reader := ZIPReader.new()
	if reader.open(ProjectSettings.globalize_path(zip_path)) != OK:
		return false
	var base := ProjectSettings.globalize_path(dest_dir)
	for f in reader.get_files():
		if f.ends_with("/"):
			continue
		var out := base.path_join(f)
		DirAccess.make_dir_recursive_absolute(out.get_base_dir())
		var fa := FileAccess.open(out, FileAccess.WRITE)
		if fa == null:
			reader.close()
			return false
		fa.store_buffer(reader.read_file(f))
		fa.close()
	reader.close()
	return true

# ── Abrir o jogo ────────────────────────────────────────────────────────────────────
func _launch_game() -> void:
	var exe := ProjectSettings.globalize_path(GAME_DIR).path_join(_exe_rel if _exe_rel != "" else "MedievalBattle.exe")
	if not FileAccess.file_exists(exe):
		_offline_fallback("Jogo não encontrado — preciso baixar a primeira vez (com internet).")
		return
	_set_status("Abrindo o jogo…")
	OS.create_process(exe, [])   # abre o jogo num processo à parte
	get_tree().quit()             # e fecha o launcher

func _offline_fallback(msg: String) -> void:
	# Sem internet mas com jogo já instalado → abre o que tem; senão mostra erro + retry.
	if FileAccess.file_exists(_installed_exe_path()):
		_set_status("Offline — abrindo a versão instalada…")
		OS.create_process(_installed_exe_path(), [])
		get_tree().quit()
		return
	_set_status(msg)
	_retry.visible = true

# ── Estado local ──────────────────────────────────────────────────────────────────────
func _installed_version() -> String:
	if not FileAccess.file_exists(ProjectSettings.globalize_path(VERSION_FILE)):
		return ""
	var fa := FileAccess.open(VERSION_FILE, FileAccess.READ)
	return fa.get_as_text().strip_edges() if fa != null else ""

func _write_version(v: String) -> void:
	var fa := FileAccess.open(VERSION_FILE, FileAccess.WRITE)
	if fa != null:
		fa.store_string(v)
		fa.close()

func _installed_exe_path() -> String:
	var rel := _exe_rel if _exe_rel != "" else "MedievalBattle.exe"
	return ProjectSettings.globalize_path(GAME_DIR).path_join(rel)
