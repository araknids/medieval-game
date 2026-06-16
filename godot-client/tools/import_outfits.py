"""Importa TODAS as peças do pack Quaternius "Modular Character Outfits – Fantasy" p/ o projeto Godot
+ gera os .gltf.import com retarget Humanoid_map. Roda com python normal (sem Blender). [OUTFITS_FEMALE]

Cobre os 5 temas (Knight/Noble/Ranger/Peasant/Wizard), Male E Female, TODAS as peças (inclui as
variantes: Horns/Spike/Scarf/Body_Cloth/Gorget/Lion). Texturas: as 3 variações de cor por tema
(T_<Tema>_BaseColor/_2/_3) + Normal + ORM, e a skin exposta dos 2 gêneros (T_Regular_<gênero>_*).
Aditivo/idempotente: varre a pasta de origem e copia tudo que encontra.
"""
import os, re, shutil, hashlib

ROOT = r"f:/Workspace/Jogo de browser mas mais grafico/assets externos/Modular Character Outfits - Fantasy[Source]"
PACK = os.path.join(ROOT, "Exports", "glTF (Godot-Unreal)")
PARTS = os.path.join(PACK, "Modular Parts")           # .gltf + .bin de TODAS as peças
TEXROOT = os.path.join(ROOT, "Textures")              # Textures/<Tema>/ (inclui _2/_3) + Textures/Base/
DEST_ROOT = r"f:/Workspace/Jogo de browser mas mais grafico/godot-client/assets/outfits"

THEMES = ["knight", "noble", "ranger", "peasant", "wizard"]
PIECE_RE = re.compile(r"^(Male|Female)_(Knight|Noble|Ranger|Peasant|Wizard)_.+\.gltf$", re.I)


def theme_of(name):
    for t in THEMES:
        if ("_%s_" % t) in name.lower():
            return t
    return None


def theme_tex(theme):
    t = theme.capitalize()
    return ["T_%s_BaseColor.png" % t, "T_%s_2_BaseColor.png" % t, "T_%s_3_BaseColor.png" % t,
            "T_%s_Normal.png" % t, "T_%s_ORM.png" % t]


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
    n = int(h[:16], 16); s = ""
    for _ in range(13):
        s += chars[n % 36]; n //= 36
    return s


def fake_hash(name):
    return hashlib.md5(name.encode()).hexdigest()


def copy_tex(filename, dest):
    for sub in ("", "Base", "Knight", "Noble", "Ranger", "Peasant", "Wizard"):
        src = os.path.join(TEXROOT, sub, filename) if sub else os.path.join(TEXROOT, filename)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(dest, filename))
            return True
    return False


count = 0
for f in sorted(os.listdir(PARTS)):
    if not PIECE_RE.match(f):
        continue
    name = os.path.splitext(f)[0]
    theme = theme_of(name)
    if theme is None:
        print("  SKIP (sem tema)", name); continue
    dest = os.path.join(DEST_ROOT, theme)
    os.makedirs(dest, exist_ok=True)
    for ext in (".gltf", ".bin"):
        src = os.path.join(PARTS, name + ext)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(dest, name + ext))
    # NÃO sobrescreve .gltf.import existente (o Godot pode tê-lo reimportado com hash real) — só cria se faltar.
    imp_path = os.path.join(dest, name + ".gltf.import")
    if not os.path.exists(imp_path):
        with open(imp_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(IMPORT_TMPL.format(uid=uid_for(name), name=name, hash=fake_hash(name), theme=theme))
    count += 1

for theme in THEMES:
    dest = os.path.join(DEST_ROOT, theme)
    os.makedirs(dest, exist_ok=True)
    for tex in theme_tex(theme) + SKIN_TEX:
        if not copy_tex(tex, dest):
            print("  MISSING TEX", theme, tex)

print("DONE pieces=", count, "themes=", THEMES)
