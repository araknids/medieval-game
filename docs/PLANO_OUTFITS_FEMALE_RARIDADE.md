# Plano — Outfits Femininos + Variantes de Skin por Raridade [OUTFITS_FEMALE][SKIN_RARIDADE]

## Contexto

O paper-doll 3D ([[reference-godot-paperdoll]], `Outfits.gd`) hoje usa só uma fatia do pack pago
**Quaternius "Modular Character Outfits – Fantasy"**: **só Male**, **4 dos 5 temas** (Knight/Noble/Ranger/Peasant,
sem Wizard), **só a cor padrão** (`T_<Tema>_BaseColor`). O pack tem MUITO mais:
- **Female** completo (mesmas 5 famílias, riggadas no mesmo esqueleto Unreal→Humanoid).
- **3 variações de cor por tema** (`T_<Tema>_BaseColor`, `_2_BaseColor`, `_3_BaseColor`) — Normal+ORM compartilhados.

O dono quer (1) **importar a Female**, (2) **importar todas as variantes de cor**, e (3) **mapear raridade → cor**,
podendo **validar** o resultado. Decisões fechadas com o dono:
- **Gênero**: campo no backend (`Player.gender`, default MALE), escolhido na **criação do personagem** + **toggle em Settings**.
- **Raridade→cor**: **3 bandas + brilho** — Comum/Incomum→cor1, Raro→cor2, Épico/Lendário→cor3, **mais emissão por raridade**
  (reusa `Weapons.RARITY_TINT/RARITY_GLOW`) p/ as 5 raridades ficarem inconfundíveis com só 3 texturas.
- **Wizard**: **pular** por ora (importa só os 4 temas atuais).

Resultado: a aparência final de uma peça = **tema (do nome do item)** × **cor (da raridade)** × **gênero (do Player)**,
três eixos ortogonais. O `outfitTheme` determinístico do backend **não muda** (continua 4 temas, % 4).

---

## Eixo 1 — Importar Female (assets + pipeline)

### Ferramentas Blender/Python (já existem, parametrizar)
- **`godot-client/tools/import_outfits.py`** (python puro, sem Blender): hoje hardcoded Male/3 temas.
  Reescrever p/ tabela `PIECES` com **Male + Female** dos 4 temas + **variantes de cor** + **skin Female**:
  - Female basenames diferem: Knight = `Female_Knight_Body_Armor/Arms/Feet/Legs/Head_Armet/Acc_Pauldrons_Round`
    (note **Feet/Legs sem `_Armor`** e **`Pauldrons`** no plural); Noble/Ranger análogos; Peasant só Body/Arms/Feet/Legs.
  - Copiar variantes `T_<Tema>_2_BaseColor.png` e `_3_BaseColor.png` de **`Textures/<Tema>/`** (só existem lá) p/ a pasta do tema.
  - Copiar skin Female `T_Regular_Female_*` (de `Outfits/`) p/ cada pasta de tema (peças Female referenciam por nome).
  - Gera `.gltf.import` com o bloco `retarget/bone_map → Humanoid_map.tres` (igual hoje).
- **`godot-client/tools/split_base.py`** (Blender headless): hoje hardcoded `Base_Male_%s.gltf`.
  Parametrizar o prefixo de gênero (arg `male|female` ou derivar do src). Rodar em
  `addons/quaternius_ik_rigged/Godot - UE/Superhero_Female_FullBody.gltf` → `assets/base/Base_Female_{Head,Torso,Arms,Legs,Feet}.gltf`.

### Frontend
- **`Outfits.gd`**: adicionar `SLOT_PIECE_FEMALE` (basenames Female) e tornar as funções de path **gênero-aware**:
  `piece_path_item(it, slot, gender)`, `icon_path_item(it, slot, gender)`, `_base(theme, slot, gender)`.
  `_dir_for(base)` já resolve a pasta pelo nome ("Knight"/"Noble"/"Ranger"/fallback peasant) p/ Male **e** Female.
- **`DollView.gd` / `BustView.gd`**: base do personagem por gênero — `Male_rigged.tscn` vs `Female_Rigged.tscn`
  (mesmo `GeneralSkeleton`, 65 ossos → peças grudam igual). `BASE_HEAD`/`BASE_PART` viram `Base_Female_*` quando female.
  Mover a instanciação do personagem de `_ready()` p/ um `_ensure_character(gender)` lazy (rebuild se o gênero mudar).
- **Threading do gênero**: `UiKit.current_gender` (espelha `UiKit.current_class`), setado quando o warrior chega.
  `Character.gd:243/717` e `Shell.gd:550/558/567` passam o gênero pro `apply(inv, class_id, gender)`.

---

## Eixo 2 — Variantes de cor + raridade (runtime recolor)

A malha importa a textura "crua" do `.gltf` (`T_<Tema>_BaseColor`). Pra trocar a cor por raridade,
**override de material em runtime** (as 3 variantes só diferem no albedo; Normal+ORM iguais):

- **`Outfits.gd`** ganha:
  ```
  # rarity 1..5 (Comum/Incomum/Raro/Épico/Lendário) → índice de variante (0=cor1, 1=cor2, 2=cor3)
  const VARIANT_FOR_RARITY := [0, 0, 1, 2, 2]
  const RARITY_TINT  := [Color(.82,.84,.88), Color(.45,.85,.45), Color(.35,.60,1), Color(.72,.40,.95), Color(1,.78,.28)]
  const RARITY_GLOW  := [0.0, 0.12, 0.22, 0.38, 0.6]   # emissão por raridade — PLACEHOLDER, afinar em engine
  static func variant_tex_path(theme, rarity) -> String   # T_<Tema>[_2|_3]_BaseColor.png na pasta do tema
  static func recolor_mesh(mi: MeshInstance3D, theme, rarity) -> void  # swap albedo + emissão, POR superfície
  ```
- `recolor_mesh` é **seguro por superfície**: só troca o albedo da superfície cujo material já aponta p/
  `T_<Tema>_BaseColor` (não mexe na pele exposta). Aplica emissão (tint+energy da raridade) na mesma superfície.
- **`DollView`/`BustView`** chamam `recolor_mesh` logo após grudar cada peça de armadura, passando
  `theme = Outfits.theme_for_item(it)` e `rarity = it.rarity`.

> Ícones 2D (Character/UiKit) ficam na cor base por ora (borda de raridade já existe). Re-render por variante = follow-up.

---

## Eixo 3 — Backend: gênero

- **`enums/Gender.java`** (novo): `MALE, FEMALE`.
- **`model/Player.java`**: `@Enumerated(STRING) @Column(columnDefinition="varchar(8) default 'MALE'") Gender gender = MALE;` + getter/setter.
- **`config/SchemaMigrator.java`**: novo `patchPlayerGenderColumn()` →
  `ALTER TABLE players ADD COLUMN IF NOT EXISTS gender varchar(8) NOT NULL DEFAULT 'MALE'` (segue o padrão dos outros patches).
- **`controller/AuthController.java`**: `RegisterRequest` ganha `String gender` (opcional); `register()` valida MALE/FEMALE
  (default MALE) e seta no player antes do save inicial. (PlayerService.register cria o player; setar gender logo após.)
- **`controller/WarriorController.java`**: `WarriorResponse` expõe `gender` (de `player.getGender()`), p/ o front escolher base/peças.
- **Toggle**: `POST /api/warrior/gender {gender}` (ou em PlayerController) seta `player.setGender(...)` e devolve o response —
  espelha o padrão de `language`. Permite trocar/testar contas existentes.

---

## Arquivos-chave

| Camada | Arquivo | Mudança |
|---|---|---|
| Tool | `godot-client/tools/import_outfits.py` | Male+Female+variantes+skin Female |
| Tool | `godot-client/tools/split_base.py` | prefixo de gênero parametrizado |
| Front | `godot-client/Outfits.gd` | mapa Female + helpers de variante/recolor |
| Front | `godot-client/ui/DollView.gd`, `ui/BustView.gd` | base por gênero + recolor por raridade |
| Front | `godot-client/ui/UiKit.gd` | `current_gender` |
| Front | `godot-client/ui/Shell.gd`, `ui/Character.gd` | passar gênero no apply |
| Front | `godot-client/ui/Login.gd`, `ui/Settings.gd`, `net/BackendClient.gd` | picker na criação + toggle + API |
| Back | `enums/Gender.java`, `model/Player.java`, `config/SchemaMigrator.java` | campo + migração |
| Back | `controller/AuthController.java`, `controller/WarriorController.java` | registro + response + toggle |

---

## Validação

1. **Preview de cor (Blender, independe do Godot)** — `tools/render_rarity_preview.py`: renderiza o **Body** de um tema
   (Male+Female) nas **3 variantes de cor** → contact sheet PNG. Valida **qual cor é Raro/Épico/Lendário** na hora.
2. **Build backend**: `mvn -o clean test` (mudança de assinatura compartilhada — regra [[feedback-verificar-clean-test]]).
3. **Em engine** (o dono): **abrir o Godot uma vez** p/ importar os novos assets (gera `.png.import`/`.scn`), depois
   abrir a **Ficha do Personagem** e equipar itens de raridades diferentes → ver a cor + brilho mudando; trocar o
   gênero no Settings → ver o boneco trocar de base/peças. Afinar `RARITY_GLOW` se o brilho ficar forte/fraco.
