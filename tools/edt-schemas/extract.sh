#!/usr/bin/env bash
# Извлекает схемы формата EDT из установленной 1C:EDT в schemas/edt/<версия>.
#
# Состав плагинов берётся из bundles.info установки: в общем пуле p2 лежат сразу
# несколько версий EDT, и смешивать их нельзя.
#
# Использование:
#   tools/edt-schemas/extract.sh <версия> <каталог установки EDT>
#
# Каталог установки - тот, где лежит configuration/org.eclipse.equinox.simpleconfigurator.
set -euo pipefail

if [ $# -lt 2 ]; then
	echo "Использование: $0 <версия EDT> <каталог установки EDT>" >&2
	exit 2
fi

VERSION="$1"
INSTALLATION="$2"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUNDLES="$INSTALLATION/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
OUT="$ROOT/schemas/edt/$VERSION"

if [ ! -f "$BUNDLES" ]; then
	echo "Не найден список плагинов: $BUNDLES" >&2
	exit 1
fi

CLASSES="$(mktemp -d)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$CLASSES" "$STAGE"' EXIT

javac -d "$CLASSES" "$ROOT/tools/edt-schemas/ExtractEdtSchemas.java"

# Извлекаем во временный каталог: неудачная попытка не должна стирать схемы,
# которые уже лежат в репозитории
java -cp "$CLASSES" ExtractEdtSchemas "$BUNDLES" "$STAGE"

rm -rf "$OUT"
mkdir -p "$(dirname "$OUT")"
mv "$STAGE" "$OUT"

echo "Схемы EDT $VERSION: $OUT"
