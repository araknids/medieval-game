import bpy, sys, os, math, mathutils

# Showcase estilo "promo do asset": cada LOOK (combo de peças) renderizado nas 3 CORES (variações de
# textura do tema), corpo inteiro, fundo transparente → depois o PIL monta no tabuleiro. [OUTFITS_VARIANTES]
# Uso: blender --background --python render_showcase.py -- <godot-client> <out_dir>
argv = sys.argv[sys.argv.index("--") + 1:]
CLIENT = os.path.abspath(argv[0]); OUTF = os.path.join(CLIENT, "assets", "outfits")
BASE = os.path.join(CLIENT, "assets", "base"); out_dir = os.path.abspath(argv[1])
os.makedirs(out_dir, exist_ok=True)

K = "Knight"; N = "Noble"; R = "Ranger"; P = "Peasant"; W = "Wizard"
# (id, gênero, tema, [peças])
LOOKS = [
    ("M_knight_plate",  "Male", "knight", ["Male_Knight_Head_Armet", "Male_Knight_Body_Armor", "Male_Knight_Acc_Pauldron_Round", "Male_Knight_Arms", "Male_Knight_Legs_Armor", "Male_Knight_Feet_Armor"]),
    ("M_knight_horned", "Male", "knight", ["Male_Knight_Head_Horns", "Male_Knight_Body_Cloth", "Male_Knight_Acc_Pauldron_Spike", "Male_Knight_Arms", "Male_Knight_Legs_Armor", "Male_Knight_Feet_Armor"]),
    ("M_noble",         "Male", "noble",  ["Male_Noble_Head_Crown", "Male_Noble_Body", "Male_Noble_Acc_Pauldron", "Male_Noble_Arms", "Male_Noble_Legs", "Male_Noble_Feet"]),
    ("M_noble_lion",    "Male", "noble",  ["Male_Noble_Head_Crown", "Male_Noble_Body", "Male_Noble_Acc_Pauldron_Lion", "Male_Noble_Arms", "Male_Noble_Legs", "Male_Noble_Feet"]),
    ("M_ranger",        "Male", "ranger", ["Male_Ranger_Head_Hood", "Male_Ranger_Body", "Male_Ranger_Acc_Pauldron", "Male_Ranger_Arms", "Male_Ranger_Legs", "Male_Ranger_Feet_Boots"]),
    ("M_peasant",       "Male", "peasant", ["Male_Peasant_Body", "Male_Peasant_Arms", "Male_Peasant_Legs", "Male_Peasant_Feet"]),
    ("M_wizard",        "Male", "wizard", ["Male_Wizard_Body", "Male_Wizard_Arms", "Male_Wizard_Legs", "Male_Wizard_Feet"]),
    ("F_knight_plate",  "Female", "knight", ["Female_Knight_Head_Armet", "Female_Knight_Body_Armor", "Female_Knight_Acc_Pauldrons_Round", "Female_Knight_Arms", "Female_Knight_Legs", "Female_Knight_Feet"]),
    ("F_knight_horned", "Female", "knight", ["Female_Knight_Head_Horns", "Female_Knight_Body_Cloth", "Female_Knight_Acc_Pauldrons_Spike", "Female_Knight_Arms", "Female_Knight_Legs", "Female_Knight_Feet"]),
    ("F_noble",         "Female", "noble",  ["Female_Noble_Head_Crown", "Female_Noble_Body", "Female_Noble_Acc_Pauldron_Lion", "Female_Noble_Arms", "Female_Noble_Legs", "Female_Noble_Feet"]),
    ("F_ranger",        "Female", "ranger", ["Female_Ranger_Head_Hood", "Female_Ranger_Body", "Female_Ranger_Acc_Pauldrons", "Female_Ranger_Arms", "Female_Ranger_Legs", "Female_Ranger_Feet"]),
    ("F_wizard",        "Female", "wizard", ["Female_Wizard_Body", "Female_Wizard_Arms", "Female_Wizard_Legs", "Female_Wizard_Feet"]),
]
COLORS = [1, 2, 3]   # as 3 variações de textura por tema (1=base, 2="_2", 3="_3")


def theme_dir(base):
    for t in ("Knight", "Noble", "Ranger", "Peasant", "Wizard"):
        if t in base:
            return t.lower()
    return "peasant"


def variant_tex(theme, v):
    suffix = "" if v == 1 else "_%d" % v
    return os.path.join(OUTF, theme, "T_%s%s_BaseColor.png" % (theme.capitalize(), suffix))


def bbox(objs):
    deps = bpy.context.evaluated_depsgraph_get(); xs = []; ys = []; zs = []
    for o in objs:
        ev = o.evaluated_get(deps); me = ev.to_mesh()
        for v in me.vertices:
            wv = ev.matrix_world @ v.co; xs.append(wv.x); ys.append(wv.y); zs.append(wv.z)
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


for look_id, gender, theme, pieces in LOOKS:
    for cv in COLORS:
        bpy.ops.wm.read_factory_settings(use_empty=True)
        scene = bpy.context.scene
        scene.render.engine = pick_engine(); scene.render.film_transparent = True
        scene.render.resolution_x = 300; scene.render.resolution_y = 600
        scene.render.image_settings.file_format = 'PNG'; scene.render.image_settings.color_mode = 'RGBA'
        wd = bpy.data.worlds.new("w"); wd.use_nodes = False; wd.color = (0.4, 0.4, 0.43); scene.world = wd
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
        # recolore p/ a cor cv (busca o TEX_IMAGE da armadura pelo nome T_<Tema>_BaseColor)
        prefix = "T_%s_BaseColor" % theme.capitalize()
        vp = variant_tex(theme, cv)
        vimg = bpy.data.images.load(vp) if os.path.exists(vp) else None
        if vimg is not None:
            for mat in bpy.data.materials:
                if not mat.use_nodes:
                    continue
                for n in mat.node_tree.nodes:
                    if n.type == 'TEX_IMAGE' and n.image and n.image.name.startswith(prefix):
                        n.image = vimg
        meshes = [o for o in scene.objects if o.type == 'MESH']
        if not meshes:
            print("NO MESH", look_id); continue
        lo, hi = bbox(meshes); center = (lo + hi) / 2.0; dim = hi - lo
        cam_data = bpy.data.cameras.new("cam"); cam_data.type = 'ORTHO'
        cam_data.ortho_scale = max(dim.z, dim.x * 1.7, 0.1) * 1.10
        cam = bpy.data.objects.new("cam", cam_data); scene.collection.objects.link(cam)
        cam.location = (center.x, center.y - (max(dim.y, 1.0) + 10.0), center.z)
        cam.rotation_euler = (math.radians(90), 0, 0); scene.camera = cam
        for ang, en, nm in [((55, 0, 20), 4.0, "key"), ((65, 0, -130), 1.7, "fill"), ((-55, 0, 180), 2.2, "rim")]:
            l = bpy.data.lights.new(nm, type='SUN'); l.energy = en
            o = bpy.data.objects.new(nm, l); scene.collection.objects.link(o)
            o.rotation_euler = (math.radians(ang[0]), math.radians(ang[1]), math.radians(ang[2]))
        out = os.path.join(out_dir, "%s__c%d.png" % (look_id, cv)); scene.render.filepath = out
        bpy.ops.render.render(write_still=True)
        print("SHOW", look_id, "c%d" % cv, "->", os.path.exists(out))
print("DONE")
