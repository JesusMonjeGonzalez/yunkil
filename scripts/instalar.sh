#!/bin/bash
# Instala Yunkil en /Applications para que aparezca en Launchpad y en Spotlight.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${1:-release}"
ORIGEN="$RAIZ/build/Yunkil.app"

"$RAIZ/scripts/construir-mac.sh" "$CONFIG"

DESTINO="/Applications/Yunkil.app"
if [ ! -w /Applications ]; then
    # Sin permiso en /Applications se usa la carpeta del usuario, que Launchpad
    # y Spotlight indexan igual y no necesita sudo.
    DESTINO="$HOME/Applications/Yunkil.app"
    mkdir -p "$HOME/Applications"
fi

rm -rf "$DESTINO"
cp -R "$ORIGEN" "$DESTINO"
# Se refresca el registro para que aparezca sin reiniciar el Finder.
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister \
    -f "$DESTINO" 2>/dev/null || true

echo "==> Instalada en $DESTINO"
