import bpy, sys, os, math, mathutils

# Renderiza UMA peça de armadura nas 5 RARIDADES (cor da variante + emissão) p/ validar o mapeamento
# raridade→cor [SKIN_RARIDADE]. Espelha Outfits.gd: VARIANT_FOR_RARITY + RARITY_TINT + RARITY_GLOW.
# Uso: blender --background --python render_rarity_preview.py -- <outfits_root> <out_dir> [piece1 piece2 ...]
#   pieces = caminhos relativos a <outfits_root> (ex.: knight/Male_Knight_Body_Armor.gltf). Default = knight body M+F.
argv = sys.argv[sys.argv.index("--") + 1:]
root = os.path.abspath(argv[0])
out_dir = os.path.abspath(argv[1])
pieces = argv[2:] if len(argv) > 2 else [
    "knight/Male_Knight_Body_Armor.gltf", "knight/Female_Knight_Body_Armor.gltf"]
os.makedirs(out_dir, exist_ok=True)

# === espelha Outfits.gd ===
VARIANT_FOR_RARITY = [0, 0, 1, 2, 2]                 # rarity 1..5 → variante de cor (0=base,1="_2",2="_3")
RARITY_TINT = [(0.82, 0.84, 0.88), (0.45, 0.85, 0.45), (0.35, 0.60, 1.0), (0.72, 0.40, 0.95), (1.0, 0.78, 0.28)]
RARITY_GLOW = [0.0, 0.05, 0.09, 0.14, 0.20]
RARITY_NAME = ["1-Comum", "2-Incomum", "3-Raro", "4-Epico", "5-Lendario"]
# A emissão do EEVEE não bate com o emission_energy do Godot e LAVA a textura clara → no preview
# mostramos a COR PURA (sinal confiável das 3 bandas). O brilho real se afina em engine. [SKIN_RARIDADE]
APPLY_EMISSION = False


def theme_of(piece_file):
    for t in ("Knight", "Noble", "Ranger", "Peasant"):
        if t in piece_file:
            return t
    return "Knight"


def variant_path(theme_dir, theme_cap, rarity):
    idx = VARIANT_FOR_RARITY[rarity - 1]
    suffix = "" if idx == 0 else "_%d" % (idx + 1)
    return os.path.join(theme_dir, "T_%s%s_BaseColor.png" % (theme_cap, suffix))


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
        a = sorted(a); i = max(0, min(len(a) - 1, int(p * (len(a) - 1)))); return a[i]
    return (mathutils.Vector((pct(xs, .05), pct(ys, .05), pct(zs, .05))),
            mathutils.Vector((pct(xs, .95), pct(ys, .95), pct(zs, .95))))


def pick_engine():
    try:
        items = bpy.context.scene.render.bl_rna.properties['engine'].enum_items.keys()
    except Exception:
        items = []
    for e in ['BLENDER_EEVEE_NEXT', 'BLENDER_EEVEE', 'CYCLES']:
        if e in items:
            return e
    return 'CYCLES'


for piece in pieces:
    gltf = os.path.join(root, piece)
    if not os.path.exists(gltf):
        print("MISSING", piece); continue
    theme_dir = os.path.dirname(gltf)
    theme_cap = theme_of(os.path.basename(piece))
    base_img_prefix = "T_%s_BaseColor" % theme_cap
    name = os.path.splitext(os.path.basename(piece))[0]
    for rarity in range(1, 6):
        bpy.ops.wm.read_factory_settings(use_empty=True)
        scene = bpy.context.scene
        scene.render.engine = pick_engine()
        scene.render.film_transparent = True
        scene.render.resolution_x = 320
        scene.render.resolution_y = 420
        scene.render.image_settings.file_format = 'PNG'
        scene.render.image_settings.color_mode = 'RGBA'
        world = bpy.data.worlds.new("w"); world.use_nodes = False
        world.color = (0.38, 0.38, 0.42); scene.world = world
        try:
            bpy.ops.import_scene.gltf(filepath=gltf)
        except Exception as e:
            print("IMPORT FAIL", piece, e); continue
        scene = bpy.context.scene
        for arm in [o for o in scene.objects if o.type == 'ARMATURE']:
            arm.data.pose_position = 'REST'
        bpy.context.view_layer.update()

        # carrega a textura da variante de cor e injeta no material da armadura (+ emissão de raridade)
        vp = variant_path(theme_dir, theme_cap, rarity)
        vimg = bpy.data.images.load(vp) if os.path.exists(vp) else None
        tint = RARITY_TINT[rarity - 1]; glow = RARITY_GLOW[rarity - 1]
        for mat in bpy.data.materials:
            if not mat.use_nodes:
                continue
            nt = mat.node_tree
            # o importador glTF liga a BaseColor via um nó MIX → busco o TEX_IMAGE pelo NOME da imagem
            # (não pela ligação direta). Só recolore o material da armadura (T_<Tema>_BaseColor).
            recolored = False
            for n in nt.nodes:
                if n.type == 'TEX_IMAGE' and n.image and n.image.name.startswith(base_img_prefix):
                    if vimg is not None:
                        n.image = vimg
                    recolored = True
            if not recolored:
                continue   # pele/outro material
            bsdf = next((n for n in nt.nodes if n.type == 'BSDF_PRINCIPLED'), None)
            if bsdf and APPLY_EMISSION:
                if 'Emission Color' in bsdf.inputs:
                    bsdf.inputs['Emission Color'].default_value = (tint[0], tint[1], tint[2], 1.0)
                if 'Emission Strength' in bsdf.inputs:
                    bsdf.inputs['Emission Strength'].default_value = glow

        meshes = [o for o in scene.objects if o.type == 'MESH']
        if not meshes:
            print("NO MESH", piece); continue
        lo, hi = bbox(meshes); center = (lo + hi) / 2.0; dim = hi - lo
        cam_data = bpy.data.cameras.new("cam"); cam_data.type = 'ORTHO'
        cam_data.ortho_scale = max(dim.x, dim.z, 0.1) * 1.25
        cam = bpy.data.objects.new("cam", cam_data); scene.collection.objects.link(cam)
        cam.location = (center.x, center.y - (max(dim.y, 1.0) + 10.0), center.z)
        cam.rotation_euler = (math.radians(90), 0, 0)
        scene.camera = cam
        for ang, en, name_l in [((55, 0, 20), 4.0, "key"), ((65, 0, -130), 1.6, "fill"), ((-60, 0, 180), 2.0, "rim")]:
            l = bpy.data.lights.new(name_l, type='SUN'); l.energy = en
            o = bpy.data.objects.new(name_l, l); scene.collection.objects.link(o)
            o.rotation_euler = (math.radians(ang[0]), math.radians(ang[1]), math.radians(ang[2]))
        out = os.path.join(out_dir, "%s__r%d.png" % (name, rarity))
        scene.render.filepath = out
        bpy.ops.render.render(write_still=True)
        print("RENDER", name, RARITY_NAME[rarity - 1], "->", os.path.exists(out))
print("DONE")
