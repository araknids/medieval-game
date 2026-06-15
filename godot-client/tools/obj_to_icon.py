import bpy, sys, os, math, mathutils

argv = sys.argv[sys.argv.index("--") + 1:]
src_dir = os.path.abspath(argv[0])
out_dir = os.path.abspath(argv[1])
os.makedirs(out_dir, exist_ok=True)

def bbox(objs):
    lo = mathutils.Vector((1e9, 1e9, 1e9))
    hi = mathutils.Vector((-1e9, -1e9, -1e9))
    for o in objs:
        for c in o.bound_box:
            w = o.matrix_world @ mathutils.Vector(c)
            lo = mathutils.Vector((min(lo.x, w.x), min(lo.y, w.y), min(lo.z, w.z)))
            hi = mathutils.Vector((max(hi.x, w.x), max(hi.y, w.y), max(hi.z, w.z)))
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

objs_list = sorted(f for f in os.listdir(src_dir) if f.lower().endswith('.obj'))
for f in objs_list:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.engine = pick_engine()
    scene.render.film_transparent = True
    scene.render.resolution_x = 128
    scene.render.resolution_y = 128
    scene.render.image_settings.file_format = 'PNG'
    scene.render.image_settings.color_mode = 'RGBA'
    # mundo cinza p/ luz ambiente (não aparece com film_transparent, só ilumina)
    world = bpy.data.worlds.new("w")
    world.use_nodes = False
    world.color = (0.35, 0.35, 0.38)
    scene.world = world
    try:
        bpy.ops.wm.obj_import(filepath=os.path.join(src_dir, f))
    except Exception as e:
        print("IMPORT FAIL", f, e); continue
    meshes = [o for o in scene.objects if o.type == 'MESH']
    if not meshes:
        print("NO MESH", f); continue
    lo, hi = bbox(meshes)
    center = (lo + hi) / 2.0
    dim = hi - lo
    cam_data = bpy.data.cameras.new("cam")
    cam_data.type = 'ORTHO'
    cam_data.ortho_scale = max(dim.x, dim.z, 0.1) * 1.15
    cam = bpy.data.objects.new("cam", cam_data)
    scene.collection.objects.link(cam)
    cam.location = (center.x, center.y - (dim.y + 10.0), center.z)
    cam.rotation_euler = (math.radians(90), 0, 0)   # olha +Y (vê o plano X-Z, arma na vertical)
    scene.camera = cam
    key = bpy.data.lights.new("key", type='SUN'); key.energy = 4.0
    ko = bpy.data.objects.new("key", key); scene.collection.objects.link(ko)
    ko.rotation_euler = (math.radians(55), 0, math.radians(35))
    fill = bpy.data.lights.new("fill", type='SUN'); fill.energy = 1.5
    fo = bpy.data.objects.new("fill", fill); scene.collection.objects.link(fo)
    fo.rotation_euler = (math.radians(70), 0, math.radians(-120))
    name = os.path.splitext(f)[0]
    scene.render.filepath = os.path.join(out_dir, name + ".png")
    bpy.ops.render.render(write_still=True)
    print("ICON", name, "->", os.path.exists(scene.render.filepath))
print("DONE engine=" + scene.render.engine)
