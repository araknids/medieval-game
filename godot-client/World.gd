extends Node3D
# ── Viewer de CENÁRIOS 3D ────────────────────────────────────────────────────────
# Monta um cenário (via Scenery.gd) e orbita a câmera pra você inspecionar.
# Rode World.tscn com F6. Plano: docs/PLANO_GODOT_3D.md (Fase 4 — cenários)

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const Scenery := preload("res://Scenery.gd")

## Cenário a montar. Por ora: "mining". Outros virão.
@export var scenario := "mining"
## Velocidade de órbita da câmera (graus/s). 0 = parada.
@export var orbit_speed := 12.0

var cam: Camera3D
var cam_angle := 0.0
var rng := RandomNumberGenerator.new()

func _ready() -> void:
	rng.seed = 20260611
	var sc := Scenery.new()
	sc.build(self, scenario, rng, 6.0)   # "mining" | "beach"
	_add_scale_char()
	cam = Camera3D.new()
	add_child(cam)
	_update_cam()

func _process(dt: float) -> void:
	cam_angle += orbit_speed * dt
	_update_cam()

func _update_cam() -> void:
	var a := deg_to_rad(cam_angle)
	var radius := 21.0
	if scenario == "beach": radius = 16.0
	elif scenario == "dungeon": radius = 15.0   # interior → câmera mais perto
	cam.position = Vector3(sin(a) * radius, 7.0, cos(a) * radius)
	cam.look_at(Vector3(0, 2.0, 0), Vector3.UP)

# personagem (idle) na beira da clareira, p/ referência de ESCALA
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
