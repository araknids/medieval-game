# Plano — Internacionalização (i18n) / Localização

> Documento de arquitetura/decisão. Discutido com o dono em 2026-06-03.
> **Status: planejado (não implementado).** O launch é em **inglês**; a estrutura multi-idioma entra
> junto do **cliente Godot** (ver BL-4 — versionar a API / estabilizar o contrato).

---

## Objetivo

Lançar em **inglês** agora e, depois (cliente Godot na Steam), suportar **vários idiomas com facilidade** —
em especial **chinês simplificado (zh-CN)**, que costuma ser um grande multiplicador de receita nesse
gênero. Adicionar um idioma deve ser "adicionar um arquivo de tradução", sem dor.

---

## Decisão: arquivos de tradução no CLIENTE + backend por CÓDIGOS

**Não usar tabela de tradução no banco** para texto estático (UI, nomes de item, lore, mensagens).
O padrão correto para "1 backend + vários clientes (web + Godot) + N idiomas":

> **O backend devolve CHAVE + dados estruturados, não frases prontas. Cada cliente traduz.**

```
❌ Hoje:   { "error": "Not enough stamina (50/100). Eat a fish or rest." }
✅ Alvo:   { "error": "not_enough_stamina", "have": 50, "need": 100 }
```

- Nomes de item/quest/buff → a **chave do enum** já é a chave de tradução (`SALMON_FILLET`,
  `KingdomQuestType.HUNT_SEA_MONSTER`, etc.). O cliente mapeia chave → texto localizado.
- Mensagens/eventos → **códigos** (`not_enough_stamina`, `ambushed_killed`) + params.

### Por quê (vantagens)
- **Backend agnóstico de idioma:** não precisa saber a língua do jogador, não duplica tradução no servidor,
  sem plumbing de `Accept-Language`.
- **Adicionar idioma = adicionar 1 arquivo no cliente** (sem deploy de backend). Tradutor mexe só nos arquivos.
- **Web e Godot compartilham as mesmas chaves.**
- **Godot tem localização nativa e forte** (ver abaixo), feita exatamente pra isso.

### A exceção (quando banco faz sentido)
Apenas **conteúdo dinâmico/editável**: texto escrito pelo jogador (mail — não se traduz), ou
anúncios/eventos que se queira mudar sem deploy. **Texto estático do jogo → arquivo, sempre.**

---

## Estado atual (2026-06-03)

- **Web** já tem i18n: `static/lang/en.json` + `static/lang/pt.json` (341 chaves cada) + função `t()`,
  com toggle EN↔PT; o idioma padrão é **`en`**.
- **Backend** hoje devolve **literais em inglês** (após o sweep de tradução). Funciona pro launch EN,
  mas para multi-idioma de verdade deve migrar para **códigos + params** (ver caminho abaixo).
- **Chaves naturais já existem:** os nomes dos enums (`Meal`, `ResourceType`, `Kingdom`,
  `KingdomQuestType`, `BuffType`…) servem como chave de tradução estável.

---

## Cliente Godot — localização nativa

- Usar o sistema de localização do Godot: **`tr("CHAVE")`** + arquivos de tradução **CSV** ou
  **PO/gettext** (um por idioma). Troca de idioma em runtime; lida com plural/placeholder.
- **Fonte CJK:** importar uma fonte com glifos chineses/japoneses/coreanos (ex.: **Noto Sans CJK**),
  senão zh/ja/ko aparecem como "□". Configurar fallback de fonte.
- **UTF-8** em tudo (já é o caso).
- **Layout flexível:** deixar a UI respirar — alemão/russo expandem o texto, CJK encolhe; evitar texto
  embutido em imagem.
- Não precisa de RTL para zh/es/pt/en (só seria necessário pra árabe/hebraico — fora de escopo).

---

## Caminho faseado (recomendado)

1. **Agora — launch EN:** inglês hardcoded (feito). Pode lançar assim. ✅
2. **Junto do cliente Godot / contrato de API (BL-4):** migrar as respostas user-facing do backend de
   *frases* → *códigos + params*. Versionar a API (`/api/v1`) e congelar esse contrato de chaves.
3. **Godot:** consome os códigos e localiza via CSV/PO, reusando as chaves (enum names + códigos de erro).
   Manter o web no mesmo conjunto de chaves (`lang/*.json`).
4. **Adicionar idiomas:** começar com **EN + zh-CN** e crescer (es, pt-BR, etc.). Tradutores mexem só nos
   arquivos de tradução.

### Catálogo de chaves (recomendação)
Manter **uma lista canônica de chaves** que web e Godot referenciam:
- IDs de conteúdo = nomes dos enums (`SALMON_FILLET`, `FISHING`, `HUNT_SEA_MONSTER`…).
- Códigos de erro/evento = snake_case (`not_enough_stamina`, `warrior_busy`, `ambushed_survived`…).
- Strings de UI = chaves já usadas no `lang/en.json`.

---

## Fora de escopo (agora)
- Migrar o backend para códigos (fica para a fase do Godot / BL-4).
- Traduzir comentários internos do código (sweep cosmético, separado; não afeta o jogador).
- RTL, formatação de número/moeda por locale (avaliar quando houver mais idiomas).

> **Resumo:** tabela no banco = não. **Arquivos de tradução no cliente + backend por códigos** = sim —
> casa perfeitamente com o Godot e com "ter o jogo em todas as línguas com facilidade".
