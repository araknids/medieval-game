extends Node3D
# ── Navegador de CENÁRIOS 3D ──────────────────────────────────────────────────────
# Monta um cenário (via Scenery.gd) e orbita a câmera. Use ← / → (ou ↑ / ↓) pra TROCAR
# de mapa AO VIVO — sem mexer no Inspector nem re-rodar. O nome do mapa aparece na tela.
# Rode World.tscn com F6. Plano: docs/PLANO_GODOT_3D.md (Fase 4 — cenários)

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const Scenery := preload("res://Scenery.gd")

# TODOS os cenários, na ordem do ciclo (← anterior / → próximo).
const SCENARIOS := ["mining", "garimpa", "beach", "dungeon", "arena", "city", "castle"]

## Cenário INICIAL (o ciclo começa nele). Troque ao vivo com as setas.
@export var scenario := "mining"
## Velocidade de órbita da câmera (graus/s). 0 = parada.
@export var orbit_speed := 12.0
## Pós-processo grimdark (vinheta + grade + bloom/SSAO). Desligue p/ comparar A/B. [GODOT_GRIMDARK]
@export var grimdark := true

var cam: Camera3D
var cam_angle := 0.0
var rng := RandomNumberGenerator.new()
var idx := 0
var scenery_root: Node3D   # container do cenário atual (free + rebuild a cada troca)
var label: Label

func _ready() -> void:
	idx = maxi(0, SCENARIOS.find(scenario))
	scenario = SCENARIOS[idx]
	_build_scenery()
	_add_scale_char()
	cam = Camera3D.new()
	add_child(cam)
	_update_cam()
	_build_hud()

func _process(dt: float) -> void:
	cam_angle += orbit_speed * dt
	_update_cam()

# ← / ↑ = anterior; → / ↓ = próximo. (echo ignorado → segurar a tecla não dispara em rajada.)
func _input(event: InputEvent) -> void:
	if event.is_action_pressed("ui_right") or event.is_action_pressed("ui_down"):
		_switch(1)
	elif event.is_action_pressed("ui_left") or event.is_action_pressed("ui_up"):
		_switch(-1)

func _switch(step: int) -> void:
	idx = (idx + step + SCENARIOS.size()) % SCENARIOS.size()
	scenario = SCENARIOS[idx]
	_build_scenery()
	_update_cam()
	_update_hud()

# (re)monta o cenário atual num container próprio — free do anterior garante troca limpa
# (mata o WorldEnvironment/luzes/partículas/overlay antigos antes de criar os novos).
func _build_scenery() -> void:
	if scenery_root and is_instance_valid(scenery_root):
		scenery_root.free()
	scenery_root = Node3D.new()
	add_child(scenery_root)
	rng.seed = 20260611   # determinístico → cada mapa fica igual a cada visita
	var sc := Scenery.new()
	sc.build(scenery_root, scenario, rng, 6.0, grimdark)

func _update_cam() -> void:
	var a := deg_to_rad(cam_angle)
	var radius := 21.0
	var height := 7.0
	var look_y := 2.0
	if scenario == "beach": radius = 16.0
	elif scenario == "dungeon": radius = 15.0   # interior → câmera mais perto
	elif scenario == "arena": radius = 18.0; height = 15.0; look_y = 0.5   # alto → olha pra dentro do pit
	elif scenario == "city": radius = 20.0; height = 13.0; look_y = 1.0    # alto → praça + telhados
	elif scenario == "castle": radius = 26.0; height = 18.0; look_y = 1.0  # alto → pátio + torres
	cam.position = Vector3(sin(a) * radius, height, cos(a) * radius)
	cam.look_at(Vector3(0, look_y, 0), Vector3.UP)

# HUD: nome do mapa + dica das setas (CanvasLayer ACIMA do overlay grimdark, layer 0).
func _build_hud() -> void:
	var layer := CanvasLayer.new()
	layer.layer = 10
	add_child(layer)
	label = Label.new()
	label.position = Vector2(20, 16)
	label.add_theme_font_size_override("font_size", 30)
	label.add_theme_color_override("font_color", Color(1, 1, 1))
	label.add_theme_color_override("font_outline_color", Color(0, 0, 0))
	label.add_theme_constant_override("outline_size", 6)
	layer.add_child(label)
	_update_hud()

func _update_hud() -> void:
	if label:
		label.text = "%s   (%d/%d)   ← →" % [scenario.to_upper(), idx + 1, SCENARIOS.size()]

# personagem (idle) na beira da clareira, p/ referência de ESCALA (persiste entre os mapas)
func _add_scale_char() -> void:
	var ch := CHAR.instantiate()
	add_child(ch)
	ch.position = Vector3(-4.5, 0, 2.4)
	ch.rotation_degrees = Vector3(0, 60, 0)
	var ap: AnimationPlayer = ch.find_child("AnimationPlayer", true, false)
	if ap:
		var il := ap.get_animation("UAL1_Standard/Sword_Idle")
		if il: il.loop_mode = Animation.LOOP_LINEAR
		ap.play("UAL1_Standard/Sword_Idle")
