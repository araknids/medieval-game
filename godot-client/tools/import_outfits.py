"""Importa peças de outfit do pack Source p/ o projeto Godot + gera os .gltf.import com retarget
Humanoid_map. Roda com python normal (sem Blender). [OUTFITS_CLASSE][OUTFITS_FEMALE]

Cobre:
  - peças FEMALE dos 4 temas (Knight/Noble/Ranger/Peasant) — o Male já foi importado antes;
  - texturas do tema INCL. as 3 variantes de cor (T_<Tema>_BaseColor / _2 / _3) p/ recolor por raridade;
  - skin Male E Female (T_Regular_<gênero>_*) — peças referenciam por nome.
Aditivo/idempotente: só copia o que encontra; re-rodar reproduz o mesmo resultado.
"""
import os, shutil, hashlib

ROOT = r"f:/Workspace/Jogo de browser mas mais grafico/assets externos/Modular Character Outfits - Fantasy[Source]"
PACK = os.path.join(ROOT, "Exports", "glTF (Godot-Unreal)")
PARTS = os.path.join(PACK, "Modular Parts")           # .gltf + .bin das peças
TEXROOT = os.path.join(ROOT, "Textures")              # Textures/<Tema>/ (inclui _2/_3) + Textures/Base/
DEST_ROOT = r"f:/Workspace/Jogo de browser mas mais grafico/godot-client/assets/outfits"

# tema -> peças FEMALE (basename sem extensão). Note: Female usa Feet/Legs SEM "_Armor" e "Pauldrons" plural.
PIECES = {
    "knight": ["Female_Knight_Body_Armor", "Female_Knight_Arms", "Female_Knight_Feet",
               "Female_Knight_Legs", "Female_Knight_Head_Armet", "Female_Knight_Acc_Pauldrons_Round"],
    "noble":  ["Female_Noble_Body", "Female_Noble_Arms", "Female_Noble_Feet",
               "Female_Noble_Legs", "Female_Noble_Head_Crown", "Female_Noble_Acc_Pauldron"],
    "ranger": ["Female_Ranger_Body", "Female_Ranger_Arms", "Female_Ranger_Feet",
               "Female_Ranger_Legs", "Female_Ranger_Head_Hood", "Female_Ranger_Acc_Pauldrons"],
    "peasant": ["Female_Peasant_Body", "Female_Peasant_Arms", "Female_Peasant_Feet", "Female_Peasant_Legs"],
}
# texturas do tema (da pasta Textures/<Tema>/): base + 2 variantes de cor + normal + ORM.
def theme_tex(theme):
    t = theme.capitalize()  # knight -> Knight
    return ["T_%s_BaseColor.png" % t, "T_%s_2_BaseColor.png" % t, "T_%s_3_BaseColor.png" % t,
            "T_%s_Normal.png" % t, "T_%s_ORM.png" % t]
# skin exposta (mãos/pescoço) — ambos os gêneros, de Textures/Base/
SKIN_TEX = ["T_Regular_Male_Dark_BaseColor.png", "T_Regular_Male_Normal.png", "T_Regular_Male_Roughness.png",
            "T_Regular_Female_Dark_BaseColor.png", "T_Regular_Female_Normal.png", "T_Regular_Female_Roughness.png"]

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
    chars = "0123456789abcdefghijklmnopqrstuvwxyz"
    n = int(h[:16], 16)
    s = ""
    for _ in range(13):
        s += chars[n % 36]; n //= 36
    return s


def fake_hash(name):
    return hashlib.md5(name.encode()).hexdigest()


def copy_tex(filename, dest):
    """Procura a textura em Textures/<Tema>/ ou Textures/Base/ e copia p/ dest. Retorna True se achou."""
    for sub in ("", "Base", "Knight", "Noble", "Ranger", "Peasant"):
        src = os.path.join(TEXROOT, sub, filename) if sub else os.path.join(TEXROOT, filename)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(dest, filename))
            return True
    return False


count = 0
for theme, pieces in PIECES.items():
    dest = os.path.join(DEST_ROOT, theme)
    os.makedirs(dest, exist_ok=True)
    for name in pieces:
        ok = False
        for ext in (".gltf", ".bin"):
            src = os.path.join(PARTS, name + ext)
            if os.path.exists(src):
                shutil.copy2(src, os.path.join(dest, name + ext))
                ok = ok or ext == ".gltf"
        if not ok:
            print("  MISSING PIECE", name); continue
        imp = IMPORT_TMPL.format(uid=uid_for(name), name=name, hash=fake_hash(name), theme=theme)
        with open(os.path.join(dest, name + ".gltf.import"), "w", encoding="utf-8", newline="\n") as f:
            f.write(imp)
        count += 1
        print("PIECE", theme, name)
    for tex in theme_tex(theme) + SKIN_TEX:
        if not copy_tex(tex, dest):
            print("  MISSING TEX", theme, tex)
print("DONE female pieces=", count)
