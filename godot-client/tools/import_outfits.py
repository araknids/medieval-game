"""Copia peças Male dos temas Knight/Noble/Peasant do pack Source + texturas e gera os .gltf.import
com retarget Humanoid_map (igual à Ranger). Roda com python normal (sem Blender). [OUTFITS_CLASSE]"""
import os, shutil, hashlib

PACK = r"f:/Workspace/Jogo de browser mas mais grafico/assets externos/Modular Character Outfits - Fantasy[Source]/Exports/glTF (Godot-Unreal)"
PARTS = os.path.join(PACK, "Modular Parts")
OUTF = os.path.join(PACK, "Outfits")  # texturas moram aqui
DEST_ROOT = r"f:/Workspace/Jogo de browser mas mais grafico/godot-client/assets/outfits"

# tema -> peças (basename sem extensão) a copiar
PIECES = {
    "knight": ["Male_Knight_Body_Armor", "Male_Knight_Arms", "Male_Knight_Feet_Armor",
               "Male_Knight_Legs_Armor", "Male_Knight_Head_Armet", "Male_Knight_Acc_Pauldron_Round"],
    "noble":  ["Male_Noble_Body", "Male_Noble_Arms", "Male_Noble_Feet",
               "Male_Noble_Legs", "Male_Noble_Head_Crown", "Male_Noble_Acc_Pauldron"],
    "peasant": ["Male_Peasant_Body", "Male_Peasant_Arms", "Male_Peasant_Feet", "Male_Peasant_Legs"],
}
# texturas por tema (copiadas pra pasta do tema; gltf referencia por nome simples)
TEX = {
    "knight": ["T_Knight_BaseColor.png", "T_Knight_Normal.png", "T_Knight_ORM.png"],
    "noble":  ["T_Noble_BaseColor.png", "T_Noble_Normal.png", "T_Noble_ORM.png"],
    "peasant": ["T_Peasant_BaseColor.png", "T_Peasant_Normal.png", "T_Peasant_ORM.png"],
}
BASE_TEX = ["T_Regular_Male_Dark_BaseColor.png", "T_Regular_Male_Normal.png", "T_Regular_Male_Roughness.png"]

IMPORT_TMPL = '''[remap]

importer="scene"
importer_version=1
type="PackedScene"
uid="uid://{uid}"
path="res://.godot/imported/{name}.gltf-{hash}.scn"

[deps]

source_file="res://assets/outfits/{theme}/{name}.gltf"
dest_files=["res://.godot/imported/{name}.gltf-{hash}.scn"]

[params]

nodes/root_type=""
nodes/root_name=""
nodes/apply_root_scale=true
nodes/root_scale=1.0
nodes/import_as_skeleton_bones=false
nodes/use_name_suffixes=true
nodes/use_node_type_suffixes=true
meshes/ensure_tangents=true
meshes/generate_lods=true
meshes/create_shadow_meshes=true
meshes/light_baking=1
meshes/lightmap_texel_size=0.2
meshes/force_disable_compression=false
skins/use_named_skins=true
animation/import=true
animation/fps=30
animation/trimming=false
animation/remove_immutable_tracks=true
animation/import_rest_as_RESET=false
import_script/path=""
materials/extract=0
_subresources={{
"nodes": {{
"PATH:Armature/Skeleton3D": {{
"retarget/bone_map": Resource("uid://dlsjipayxuj0q", "res://addons/quaternius_ik_rigged/Humanoid_map.tres")
}}
}}
}}
gltf/naming_version=2
gltf/embedded_image_handling=1
'''

def uid_for(name):
    h = hashlib.md5(("outfit:" + name).encode()).hexdigest()
    # uid base ~13 chars alfanum minúsculos (formato aceito pelo Godot; se recusar, regenera mantendo o retarget)
    chars = "0123456789abcdefghijklmnopqrstuvwxyz"
    n = int(h[:16], 16)
    s = ""
    for _ in range(13):
        s += chars[n % 36]; n //= 36
    return s

def fake_hash(name):
    return hashlib.md5(name.encode()).hexdigest()

count = 0
for theme, pieces in PIECES.items():
    dest = os.path.join(DEST_ROOT, theme)
    os.makedirs(dest, exist_ok=True)
    for name in pieces:
        for ext in (".gltf", ".bin"):
            src = os.path.join(PARTS, name + ext)
            if os.path.exists(src):
                shutil.copy2(src, os.path.join(dest, name + ext))
        imp = IMPORT_TMPL.format(uid=uid_for(name), name=name, hash=fake_hash(name), theme=theme)
        with open(os.path.join(dest, name + ".gltf.import"), "w", encoding="utf-8", newline="\n") as f:
            f.write(imp)
        count += 1
        print("PIECE", theme, name)
    for tex in TEX[theme] + BASE_TEX:
        src = os.path.join(OUTF, tex)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(dest, tex))
        else:
            print("  MISSING TEX", tex)
print("DONE pieces=", count)
