import bpy, sys, os, math, mathutils

# Renderiza SETS COMPLETOS (todas as peças vestidas no corpo) por tema/gênero/raridade p/ uma galeria
# de validação [SKIN_RARIDADE][OUTFITS_FEMALE]. Mostra a COR PURA das 3 bandas (emissão off — a do EEVEE
# não bate com a do Godot). Uso: blender --background --python render_set_gallery.py -- <godot-client> <out_dir>
argv = sys.argv[sys.argv.index("--") + 1:]
CLIENT = os.path.abspath(argv[0])
OUTF = os.path.join(CLIENT, "assets", "outfits")
BASE = os.path.join(CLIENT, "assets", "base")
out_dir = os.path.abspath(argv[1])
os.makedirs(out_dir, exist_ok=True)

VARIANT_FOR_RARITY = [0, 0, 1, 2, 2]   # rarity 1..5 → 0=cor1, 1=cor2, 2=cor3
BANDS = [1, 3, 5]                       # uma raridade representativa por banda (Comum, Raro, Lendário)

# peças por gênero/tema (espelha Outfits.gd SLOT_PIECE / SLOT_PIECE_FEMALE; peasant cai p/ ranger no elmo/ombreira)
SETS = {
    "Male": {
        "knight":  ["Male_Knight_Body_Armor", "Male_Knight_Arms", "Male_Knight_Feet_Armor", "Male_Knight_Legs_Armor", "Male_Knight_Head_Armet", "Male_Knight_Acc_Pauldron_Round"],
        "noble":   ["Male_Noble_Body", "Male_Noble_Arms", "Male_Noble_Feet", "Male_Noble_Legs", "Male_Noble_Head_Crown", "Male_Noble_Acc_Pauldron"],
        "ranger":  ["Male_Ranger_Body", "Male_Ranger_Arms", "Male_Ranger_Feet_Boots", "Male_Ranger_Legs", "Male_Ranger_Head_Hood", "Male_Ranger_Acc_Pauldron"],
        "peasant": ["Male_Peasant_Body", "Male_Peasant_Arms", "Male_Peasant_Feet", "Male_Peasant_Legs", "Male_Ranger_Head_Hood", "Male_Ranger_Acc_Pauldron"],
    },
    "Female": {
        "knight":  ["Female_Knight_Body_Armor", "Female_Knight_Arms", "Female_Knight_Feet", "Female_Knight_Legs", "Female_Knight_Head_Armet", "Female_Knight_Acc_Pauldrons_Round"],
        "noble":   ["Female_Noble_Body", "Female_Noble_Arms", "Female_Noble_Feet", "Female_Noble_Legs", "Female_Noble_Head_Crown", "Female_Noble_Acc_Pauldron"],
        "ranger":  ["Female_Ranger_Body", "Female_Ranger_Arms", "Female_Ranger_Feet", "Female_Ranger_Legs", "Female_Ranger_Head_Hood", "Female_Ranger_Acc_Pauldrons"],
        "peasant": ["Female_Peasant_Body", "Female_Peasant_Arms", "Female_Peasant_Feet", "Female_Peasant_Legs", "Female_Ranger_Head_Hood", "Female_Ranger_Acc_Pauldrons"],
    },
}


def theme_dir(base):
    for t in ("Knight", "Noble", "Ranger", "Peasant"):
        if t in base:
            return t.lower()
    return "peasant"


def variant_path(theme, rarity):
    idx = VARIANT_FOR_RARITY[rarity - 1]
    suffix = "" if idx == 0 else "_%d" % (idx + 1)
    return os.path.join(OUTF, theme, "T_%s%s_BaseColor.png" % (theme.capitalize(), suffix))


def bbox(objs):
    deps = bpy.context.evaluated_depsgraph_get()
    xs = []; ys = []; zs = []
    for o in objs:
        ev = o.evaluated_get(deps); me = ev.to_mesh()
        for v in me.vertices:
            w = ev.matrix_world @ v.co
            xs.append(w.x); ys.append(w.y); zs.append(w.z)
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


for gender, themes in SETS.items():
    for theme, pieces in themes.items():
        for rarity in BANDS:
            bpy.ops.wm.read_factory_settings(use_empty=True)
            scene = bpy.context.scene
            scene.render.engine = pick_engine()
            scene.render.film_transparent = True
            scene.render.resolution_x = 320
            scene.render.resolution_y = 560
            scene.render.image_settings.file_format = 'PNG'
            scene.render.image_settings.color_mode = 'RGBA'
            w = bpy.data.worlds.new("w"); w.use_nodes = False
            w.color = (0.36, 0.36, 0.40); scene.world = w

            # importa todas as peças do set + a cabeça-base (rosto)
            head = os.path.join(BASE, "Base_%s_Head.gltf" % gender)
            to_load = [os.path.join(OUTF, theme_dir(b), b + ".gltf") for b in pieces]
            if os.path.exists(head):
                to_load.append(head)
            for g in to_load:
                if os.path.exists(g):
                    try:
                        bpy.ops.import_scene.gltf(filepath=g)
                    except Exception as e:
                        print("IMPORT FAIL", g, e)
                else:
                    print("MISSING", g)
            scene = bpy.context.scene
            for arm in [o for o in scene.objects if o.type == 'ARMATURE']:
                arm.data.pose_position = 'REST'
            bpy.context.view_layer.update()

            # recolore o albedo das armaduras (T_<Tema>_BaseColor) → variante da raridade (busca por NOME)
            base_prefix = "T_%s_BaseColor" % theme.capitalize()
            vp = variant_path(theme, rarity)
            vimg = bpy.data.images.load(vp) if os.path.exists(vp) else None
            if vimg is not None:
                for mat in bpy.data.materials:
                    if not mat.use_nodes:
                        continue
                    for n in mat.node_tree.nodes:
                        if n.type == 'TEX_IMAGE' and n.image and n.image.name.startswith(base_prefix):
                            n.image = vimg

            meshes = [o for o in scene.objects if o.type == 'MESH']
            if not meshes:
                print("NO MESH", gender, theme, rarity); continue
            lo, hi = bbox(meshes); center = (lo + hi) / 2.0; dim = hi - lo
            cam_data = bpy.data.cameras.new("cam"); cam_data.type = 'ORTHO'
            cam_data.ortho_scale = max(dim.z, dim.x * 1.6, 0.1) * 1.12
            cam = bpy.data.objects.new("cam", cam_data); scene.collection.objects.link(cam)
            cam.location = (center.x, center.y - (max(dim.y, 1.0) + 10.0), center.z)
            cam.rotation_euler = (math.radians(90), 0, 0)
            scene.camera = cam
            for ang, en, nm in [((55, 0, 20), 4.0, "key"), ((65, 0, -130), 1.6, "fill"), ((-55, 0, 180), 2.2, "rim")]:
                l = bpy.data.lights.new(nm, type='SUN'); l.energy = en
                o = bpy.data.objects.new(nm, l); scene.collection.objects.link(o)
                o.rotation_euler = (math.radians(ang[0]), math.radians(ang[1]), math.radians(ang[2]))
            out = os.path.join(out_dir, "%s_%s__r%d.png" % (gender, theme, rarity))
            scene.render.filepath = out
            bpy.ops.render.render(write_still=True)
            print("SET", gender, theme, "r%d" % rarity, "->", os.path.exists(out))
print("DONE")
