#!/usr/bin/env bash
# Baixa o zip de um objeto animado do PixelLab e extrai os frames p/ assets/ui/icons/anim/<key>/fN.png
# Uso: ./dl_anim.sh <object_id> <key>
# Saída: 0=ok · 3=animacao ainda processando (endpoint devolveu PNG, nao zip) · 2=erro real
set -euo pipefail
ID="$1"; KEY="$2"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/assets/ui/icons/anim/$KEY"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -sfL "https://api.pixellab.ai/mcp/objects/$ID/download" -o "$TMP/o.bin"
# zip começa com "PK"; se vier PNG (\x89PNG) a animacao ainda nao commitou
if [ "$(head -c 2 "$TMP/o.bin")" != "PK" ]; then
  echo "PROCESSING $KEY ($ID) — animacao ainda nao pronta"
  exit 3
fi

unzip -qo "$TMP/o.bin" -d "$TMP/x"
SRC_DIR="$(find "$TMP/x" -type d -path '*animations*/unknown' | head -1)"
if [ -z "$SRC_DIR" ]; then echo "FALHA: nenhuma pasta de animacao p/ $ID ($KEY)"; exit 2; fi

mkdir -p "$DEST"
rm -f "$DEST"/f*.png "$DEST"/f*.png.import
i=0
for f in $(ls "$SRC_DIR"/frame_*.png | sort -t_ -k2 -n); do
  cp "$f" "$DEST/f$i.png"
  i=$((i+1))
done
echo "OK $KEY: $i frames -> anim/$KEY"
