import bpy, sys, os, math, mathutils

# Renderiza cada peça .gltf (armadura skinned em bind pose) num ícone 2D frontal, enquadrado na peça.
# Uso: blender --background --python gltf_to_icon.py -- <outfits_root> <out_dir> [theme1 theme2 ...]
argv = sys.argv[sys.argv.index("--") + 1:]
root = os.path.abspath(argv[0])
out_dir = os.path.abspath(argv[1])
themes = argv[2:] if len(argv) > 2 else ["knight", "noble", "peasant", "ranger"]
os.makedirs(out_dir, exist_ok=True)

def bbox(objs):
    # bbox por PERCENTIL (2..98%) dos vértices avaliados — ignora outliers de rig que estouram o min/max.
    deps = bpy.context.evaluated_depsgraph_get()
    xs = []; ys = []; zs = []
    for o in objs:
        ev = o.evaluated_get(deps)
        me = ev.to_mesh()
        for v in me.vertices:
            w = ev.matrix_world @ v.co
            xs.append(w.x); ys.append(w.y); zs.append(w.z)
        ev.to_mesh_clear()
    def pct(a, p):
        a = sorted(a)
        i = max(0, min(len(a) - 1, int(p * (len(a) - 1))))
        return a[i]
    lo = mathutils.Vector((pct(xs, 0.08), pct(ys, 0.08), pct(zs, 0.08)))
    hi = mathutils.Vector((pct(xs, 0.92), pct(ys, 0.92), pct(zs, 0.92)))
    return lo, hi

def pick_engine():
    try:
        items = bpy.context.scene.render.bl_rna.properties['engine'].enum_items.keys()
    except Exception:
        items = []
    for e in ['BLENDER_EEVEE_NEXT', 'BLENDER_EEVEE', 'CYCLES']:
        if e in items:
            return e
    return 'CYCLES'

jobs = []
for theme in themes:
    d = os.path.join(root, theme)
    if not os.path.isdir(d):
        continue
    for f in sorted(os.listdir(d)):
        if f.lower().endswith('.gltf'):
            jobs.append((d, f))

for d, f in jobs:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.engine = pick_engine()
    scene.render.film_transparent = True
    scene.render.resolution_x = 128
    scene.render.resolution_y = 128
    scene.render.image_settings.file_format = 'PNG'
    scene.render.image_settings.color_mode = 'RGBA'
    world = bpy.data.worlds.new("w"); world.use_nodes = False
    world.color = (0.4, 0.4, 0.43); scene.world = world
    try:
        bpy.ops.import_scene.gltf(filepath=os.path.join(d, f))
    except Exception as e:
        print("IMPORT FAIL", f, e); continue
    scene = bpy.context.scene
    # esqueleto em REST = pose de bind → peça fica na posição visual correta (verts skinned)
    for arm in [o for o in scene.objects if o.type == 'ARMATURE']:
        arm.data.pose_position = 'REST'
    bpy.context.view_layer.update()
    meshes = [o for o in scene.objects if o.type == 'MESH']
    if not meshes:
        print("NO MESH", f); continue
    lo, hi = bbox(meshes)
    center = (lo + hi) / 2.0
    dim = hi - lo
    cam_data = bpy.data.cameras.new("cam"); cam_data.type = 'ORTHO'
    cam_data.ortho_scale = max(dim.x, dim.z, 0.1) * 1.18
    cam = bpy.data.objects.new("cam", cam_data); scene.collection.objects.link(cam)
    # vista frontal: câmera em -Y olhando +Y (front view do Blender)
    cam.location = (center.x, center.y - (max(dim.y, 1.0) + 10.0), center.z)
    cam.rotation_euler = (math.radians(90), 0, 0)
    scene.camera = cam
    key = bpy.data.lights.new("key", type='SUN'); key.energy = 4.0
    ko = bpy.data.objects.new("key", key); scene.collection.objects.link(ko)
    ko.rotation_euler = (math.radians(55), 0, math.radians(20))
    fill = bpy.data.lights.new("fill", type='SUN'); fill.energy = 1.6
    fo = bpy.data.objects.new("fill", fill); scene.collection.objects.link(fo)
    fo.rotation_euler = (math.radians(65), 0, math.radians(-130))
    rim = bpy.data.lights.new("rim", type='SUN'); rim.energy = 2.0
    ro = bpy.data.objects.new("rim", rim); scene.collection.objects.link(ro)
    ro.rotation_euler = (math.radians(-60), 0, math.radians(180))
    name = os.path.splitext(f)[0]
    scene.render.filepath = os.path.join(out_dir, name + ".png")
    bpy.ops.render.render(write_still=True)
    print("ICON", name, "dim=(%.2f,%.2f,%.2f)" % (dim.x, dim.y, dim.z), "scale=%.2f" % cam_data.ortho_scale, "->", os.path.exists(scene.render.filepath))
print("DONE engine=" + bpy.context.scene.render.engine)
