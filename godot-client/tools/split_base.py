# Corta a base Quaternius (Superhero_Male_FullBody) em peças NUAS por peso de osso:
# Head / Torso / Arms / Legs / Feet. Cada peça é exportada como .gltf mantendo a armature
# (skin por nome de osso → no Godot importa com o mesmo retarget Humanoid_map e gruda no
# GeneralSkeleton). Assim o paper-doll mostra a peça nua no slot vazio e a roupa no equipado,
# sem clipping (nunca os dois juntos). [GODOT_PAPERDOLL]
# Uso: blender --background --python split_base.py -- <src.gltf> <out_dir>
import bpy, sys, os, re
from collections import Counter

argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
src = argv[0]
out_dir = argv[1]
os.makedirs(out_dir, exist_ok=True)

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=src)

arm = next((o for o in bpy.data.objects if o.type == 'ARMATURE'), None)
meshes = [o for o in bpy.data.objects if o.type == 'MESH']
body = max(meshes, key=lambda o: len(o.data.vertices))
print('BODY:', body.name, len(body.data.vertices), 'verts; vgroups:', len(body.vertex_groups))


def part_of(bone):
    b = bone.lower()
    if re.search(r'head|neck', b): return 'Head'
    if re.search(r'upperarm|lowerarm|hand|index|middle|ring|pinky|thumb', b): return 'Arms'
    if re.search(r'thigh|calf', b): return 'Legs'
    if re.search(r'foot|ball', b): return 'Feet'
    if re.search(r'spine|clavicle|pelvis', b): return 'Torso'
    return 'Torso'


gname = {vg.index: vg.name for vg in body.vertex_groups}
me = body.data
PARTS = ['Head', 'Torso', 'Arms', 'Legs', 'Feet']
vpart = {}
for v in me.vertices:
    best_g, best_w = -1, -1.0
    for g in v.groups:
        if g.weight > best_w:
            best_w, best_g = g.weight, g.group
    vpart[v.index] = part_of(gname.get(best_g, 'Torso')) if best_g >= 0 else 'Torso'
print('distribuicao:', dict(Counter(vpart.values())))

# cria um vertex group por parte no corpo (método confiável p/ selecionar/separar)
pg = {p: body.vertex_groups.new(name='PART_' + p) for p in PARTS}
for vi, p in vpart.items():
    pg[p].add([vi], 1.0, 'REPLACE')

bpy.context.tool_settings.mesh_select_mode = (True, False, False)

for part in PARTS:
    bpy.ops.object.select_all(action='DESELECT')
    body.select_set(True)
    bpy.context.view_layer.objects.active = body
    bpy.ops.object.duplicate()
    dup = bpy.context.active_object
    dup.name = 'Base_' + part
    dup.vertex_groups.active_index = dup.vertex_groups.find('PART_' + part)
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.select_all(action='DESELECT')
    bpy.ops.object.vertex_group_select()      # seleciona os vértices DESTA parte
    bpy.ops.mesh.select_all(action='INVERT')  # inverte → seleciona o resto
    bpy.ops.mesh.delete(type='VERT')          # apaga o resto, mantém a parte
    bpy.ops.object.mode_set(mode='OBJECT')
    n = len(dup.data.vertices)
    if n == 0:
        print('  %s: vazio, pulando' % part)
        bpy.data.objects.remove(dup)
        continue
    # limpa os grupos PART_* do dup (não exportar lixo)
    for p in PARTS:
        g = dup.vertex_groups.get('PART_' + p)
        if g: dup.vertex_groups.remove(g)
    bpy.ops.object.select_all(action='DESELECT')
    dup.select_set(True)
    arm.select_set(True)
    bpy.context.view_layer.objects.active = arm
    out = os.path.join(out_dir, 'Base_Male_%s.gltf' % part)
    bpy.ops.export_scene.gltf(filepath=out, use_selection=True, export_format='GLTF_SEPARATE')
    print('  exportado %s: %d verts -> %s' % (part, n, out))
    bpy.data.objects.remove(dup)

print('DONE')
