#!/bin/bash
# Construye `yunkil`, la orden de terminal, y la deja instalable.
#
# Es el mismo núcleo que la aplicación, enlazado como ejecutable nativo: no hay JVM que
# instalar en la máquina que lo corra, así que vale para un script, para un Makefile o
# para un paso de CI que examine una pieza antes de laminarla.
#
#     ./scripts/construir-cli.sh            # construye en build/yunkil
#     ./scripts/construir-cli.sh instalar   # y lo copia a ~/.local/bin
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
QUE="${1:-construir}"

echo "==> Enlazando el ejecutable nativo"
"$RAIZ/gradlew" -p "$RAIZ" :core:linkYunkilReleaseExecutableMacosArm64 --console=plain -q

BINARIO="$RAIZ/core/build/bin/macosArm64/yunkilReleaseExecutable/yunkil.kexe"
[ -f "$BINARIO" ] || { echo "no salió el binario en $BINARIO" >&2; exit 1; }

mkdir -p "$RAIZ/build"
cp "$BINARIO" "$RAIZ/build/yunkil"
chmod +x "$RAIZ/build/yunkil"
echo "==> Listo: $RAIZ/build/yunkil"

if [ "$QUE" = "instalar" ]; then
    DESTINO="$HOME/.local/bin"
    mkdir -p "$DESTINO"
    cp "$RAIZ/build/yunkil" "$DESTINO/yunkil"
    echo "==> Instalado en $DESTINO/yunkil"
    case ":$PATH:" in
        *":$DESTINO:"*) ;;
        *) echo "    (añade $DESTINO a tu PATH para llamarlo por su nombre)" ;;
    esac
fi
