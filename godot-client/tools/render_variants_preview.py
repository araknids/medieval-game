import bpy, sys, os, math, mathutils

# Mostra que as VARIANTES de peça agora aparecem (drops diferentes = looks diferentes) [OUTFITS_VARIANTES].
# Renderiza alguns sets-exemplo do Knight/Noble com peças alternativas (elmo aberto vs chifre, peitoral
# de placa vs pano, ombreira redonda vs espinho vs cachecol; noble pauldron vs leão vs gorjal).
# Uso: blender --background --python render_variants_preview.py -- <godot-client> <out_dir>
argv = sys.argv[sys.argv.index("--") + 1:]
CLIENT = os.path.abspath(argv[0]); OUTF = os.path.join(CLIENT, "assets", "outfits")
BASE = os.path.join(CLIENT, "assets", "base"); out_dir = os.path.abspath(argv[1])
os.makedirs(out_dir, exist_ok=True)

COMBOS = [
    ("knight_A_armet", "Male", ["Male_Knight_Head_Armet", "Male_Knight_Body_Armor", "Male_Knight_Acc_Pauldron_Round", "Male_Knight_Arms", "Male_Knight_Legs_Armor", "Male_Knight_Feet_Armor"]),
    ("knight_B_horns", "Male", ["Male_Knight_Head_Horns", "Male_Knight_Body_Cloth", "Male_Knight_Acc_Pauldron_Spike", "Male_Knight_Arms", "Male_Knight_Legs_Armor", "Male_Knight_Feet_Armor"]),
    ("knight_C_scarf", "Male", ["Male_Knight_Head_Armet", "Male_Knight_Body_Armor", "Male_Knight_Acc_Scarf", "Male_Knight_Arms", "Male_Knight_Legs_Armor", "Male_Knight_Feet_Armor"]),
    ("noble_A_pauldron", "Male", ["Male_Noble_Head_Crown", "Male_Noble_Body", "Male_Noble_Acc_Pauldron", "Male_Noble_Arms", "Male_Noble_Legs", "Male_Noble_Feet"]),
    ("noble_B_lion", "Male", ["Male_Noble_Head_Crown", "Male_Noble_Body", "Male_Noble_Acc_Pauldron_Lion", "Male_Noble_Arms", "Male_Noble_Legs", "Male_Noble_Feet"]),
    ("noble_C_gorget", "Male", ["Male_Noble_Head_Crown", "Male_Noble_Body", "Male_Noble_Acc_Gorget", "Male_Noble_Arms", "Male_Noble_Legs", "Male_Noble_Feet"]),
]


def theme_dir(base):
    for t in ("Knight", "Noble", "Ranger", "Peasant", "Wizard"):
        if t in base:
            return t.lower()
    return "peasant"


def bbox(objs):
    deps = bpy.context.evaluated_depsgraph_get(); xs = []; ys = []; zs = []
    for o in objs:
        ev = o.evaluated_get(deps); me = ev.to_mesh()
        for v in me.vertices:
            w = ev.matrix_world @ v.co; xs.append(w.x); ys.append(w.y); zs.append(w.z)
        ev.to_mesh_clear()
    def pct(a, p):
        a = sorted(a); return a[max(0, min(len(a) - 1, int(p * (len(a) - 1))))]
    return (mathutils.Vector((pct(xs, .02), pct(ys, .02), pct(zs, .02))),
            mathutils.Vector((pct(xs, .98), pct(ys, .98), pct(zs, .98))))


def pick_engine():
    try:
        items = bpy.context.scene.render.bl_rna.properties['engine'].enum_items.keys()
    except Exception:
        items = []
    for e in ['BLENDER_EEVEE_NEXT', 'BLENDER_EEVEE', 'CYCLES']:
        if e in items:
            return e
    return 'CYCLES'


for label, gender, pieces in COMBOS:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.engine = pick_engine(); scene.render.film_transparent = True
    scene.render.resolution_x = 320; scene.render.resolution_y = 560
    scene.render.image_settings.file_format = 'PNG'; scene.render.image_settings.color_mode = 'RGBA'
    w = bpy.data.worlds.new("w"); w.use_nodes = False; w.color = (0.36, 0.36, 0.40); scene.world = w
    to_load = [os.path.join(OUTF, theme_dir(b), b + ".gltf") for b in pieces]
    head = os.path.join(BASE, "Base_%s_Head.gltf" % gender)
    if os.path.exists(head):
        to_load.append(head)
    for g in to_load:
        if os.path.exists(g):
            bpy.ops.import_scene.gltf(filepath=g)
        else:
            print("MISSING", g)
    scene = bpy.context.scene
    for arm in [o for o in scene.objects if o.type == 'ARMATURE']:
        arm.data.pose_position = 'REST'
    bpy.context.view_layer.update()
    meshes = [o for o in scene.objects if o.type == 'MESH']
    if not meshes:
        print("NO MESH", label); continue
    lo, hi = bbox(meshes); center = (lo + hi) / 2.0; dim = hi - lo
    cam_data = bpy.data.cameras.new("cam"); cam_data.type = 'ORTHO'
    cam_data.ortho_scale = max(dim.z, dim.x * 1.6, 0.1) * 1.12
    cam = bpy.data.objects.new("cam", cam_data); scene.collection.objects.link(cam)
    cam.location = (center.x, center.y - (max(dim.y, 1.0) + 10.0), center.z)
    cam.rotation_euler = (math.radians(90), 0, 0); scene.camera = cam
    for ang, en, nm in [((55, 0, 20), 4.0, "key"), ((65, 0, -130), 1.6, "fill"), ((-55, 0, 180), 2.2, "rim")]:
        l = bpy.data.lights.new(nm, type='SUN'); l.energy = en
        o = bpy.data.objects.new(nm, l); scene.collection.objects.link(o)
        o.rotation_euler = (math.radians(ang[0]), math.radians(ang[1]), math.radians(ang[2]))
    out = os.path.join(out_dir, label + ".png"); scene.render.filepath = out
    bpy.ops.render.render(write_still=True)
    print("VARIANT", label, "->", os.path.exists(out))
print("DONE")
