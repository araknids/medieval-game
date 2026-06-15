import bpy, sys, os

argv = sys.argv[sys.argv.index("--") + 1:]
src_dir, out_dir = argv[0], argv[1]
os.makedirs(out_dir, exist_ok=True)

objs = sorted(f for f in os.listdir(src_dir) if f.lower().endswith(".obj"))
ok = 0
for f in objs:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    path = os.path.join(src_dir, f)
    try:
        bpy.ops.wm.obj_import(filepath=path)
    except Exception as e:
        print("IMPORT FAIL", f, e)
        continue
    name = os.path.splitext(f)[0]
    out = os.path.join(out_dir, name + ".glb")
    bpy.ops.export_scene.gltf(filepath=out, export_format='GLB', use_selection=False)
    ok += 1
    print("GLB", name)
print("ALL DONE", ok, "/", len(objs))
