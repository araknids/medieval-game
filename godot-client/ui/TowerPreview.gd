extends TextureRect
class_name TowerPreview
# ── Preview ANIMADO (pixel-art) de um inimigo da Torre ─────────────────────────────
# Cicla os quadros de res://assets/ui/tower/<key>/f0.png, f1.png, … num loop suave.
# Fallback em cascata: vários quadros → estático 1-quadro (res://assets/ui/tower/<key>.png)
# → nada (a fábrica devolve null e quem chama esconde o preview). A arte é gerada no
# PixelLab (v3, vista lateral) e baixada por andar/zona; mapa key↔andar fica no Tower.gd.
# Padrão de carga espelha o Icons.gd (ResourceLoader.exists + load). [TORRE_PREVIEW]

const DIR := "res://assets/ui/tower/"

var _frames: Array = []          # Array[Texture2D] — quadros do idle
var _i := 0
var _t := 0.0
var fps := 6.0                   # breathing-idle do PixelLab (4 quadros) — 6 fps respira sem tremer

func _process(delta: float) -> void:
	if _frames.size() <= 1:
		set_process(false)        # estático: não precisa de tick
		return
	_t += delta
	var step := 1.0 / fps
	while _t >= step:
		_t -= step
		_i = (_i + 1) % _frames.size()
		texture = _frames[_i]

# Carrega os quadros do inimigo `key`. Retorna quantos achou (0 = sem arte).
func load_key(key: String) -> int:
	_frames.clear()
	_i = 0; _t = 0.0
	var folder := DIR + key + "/"
	var n := 0
	while true:
		var p := folder + "f%d.png" % n
		if not ResourceLoader.exists(p):
			break
		_frames.append(load(p))
		n += 1
	if _frames.is_empty():
		var single := DIR + key + ".png"
		if ResourceLoader.exists(single):
			_frames.append(load(single))
	if not _frames.is_empty():
		texture = _frames[0]
	return _frames.size()

# Fábrica: TowerPreview pronto (px quadrado, nearest-neighbor p/ pixel-art crocante).
# Devolve null se não houver arte p/ `key` (quem chama omite o preview, sem buraco no layout).
static func make(key: String, px := 132) -> TowerPreview:
	var tp := TowerPreview.new()
	tp.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	tp.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	tp.custom_minimum_size = Vector2(px, px)
	tp.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
	tp.mouse_filter = Control.MOUSE_FILTER_IGNORE
	if tp.load_key(key) == 0:
		tp.free()
		return null
	return tp
