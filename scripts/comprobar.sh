#!/bin/bash
# Comprueba Yunkil entero: núcleo, paridad CPU↔GPU, rayo de cámara y compilación de la app.
#
# Existía la deuda de que el arnés de paridad «se compila a mano con swiftc», y una
# comprobación que hay que recordar cómo se lanza es una comprobación que no se lanza.
# Lo que se verifica aquí es, en este orden y por este motivo:
#
#   1. El núcleo, que es donde vive toda la geometría y todas las reglas.
#   2. La paridad CPU↔GPU: si el shader y `SdfNode.evaluar` divergen, el viewport
#      enseña una pieza y el analizador razona sobre otra. Es el invariante que
#      sostiene el resto del producto.
#   3. La previsualización fantasma: se dibuja de verdad y se cuentan los píxeles, que
#      es lo único que dice si lo que se añade sale verde y lo que se quita, rojo.
#   4. El rayo de la cámara: un signo invertido aquí no da error, da otra pieza.
#   5. El gizmo: proyectar es la vuelta exacta del rayo, y arrastrar mueve y gira hacia
#      donde se arrastra. Otro sitio donde un signo no da error, da un gesto al revés.
#   6. Que la aplicación de escritorio compile y enlace contra el núcleo real.
#   7. El estado de la aplicación conducido como lo conduce un usuario: la capa que decide
#      qué se le pide al núcleo y cuándo, que es la que no tenía nada que la mirara.
#   8. Que la orden de terminal enlace y arranque: es el mismo núcleo sin ventana, y se
#      rompe en silencio si nadie la construye.
#
#     ./scripts/comprobar.sh          # todo
#     ./scripts/comprobar.sh nucleo   # solo las pruebas del núcleo, que es lo rápido
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
QUE="${1:-todo}"
cd "$RAIZ"
mkdir -p build

fallos=0
paso() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

paso "Pruebas del núcleo"
./gradlew :core:jvmTest --console=plain -q
pruebas=$(grep -ho 'tests="[0-9]*"' core/build/test-results/jvmTest/*.xml | tr -dc '0-9\n' | awk '{s+=$1} END {print s}')
echo "$pruebas pruebas, 0 fallos"

if [ "$QUE" = "nucleo" ]; then
    exit 0
fi

paso "Paridad CPU ↔ GPU"
./gradlew :core:volcarParidad --console=plain -q
swiftc -O tools/paridad/main.swift -o build/paridad-arnes
./build/paridad-arnes core/build/paridad || fallos=$((fallos + 1))

paso "Previsualización fantasma"
swiftc -O tools/fantasma/main.swift apps/mac/Sources/Camara.swift -o build/fantasma-arnes
./build/fantasma-arnes core/build/paridad || fallos=$((fallos + 1))

paso "Rayo de la cámara"
swiftc -O tools/rayo/main.swift apps/mac/Sources/Camara.swift -o build/rayo-arnes
./build/rayo-arnes || fallos=$((fallos + 1))

paso "Gizmo de mover y girar"
swiftc -O tools/gizmo/main.swift apps/mac/Sources/Camara.swift apps/mac/Sources/Gizmo.swift -o build/gizmo-arnes
./build/gizmo-arnes || fallos=$((fallos + 1))

paso "Aplicación de escritorio"
./scripts/construir-mac.sh debug >/dev/null
echo "Yunkil.app construida"

paso "Estado de la aplicación"
# Conduce `EstadoDeLaApp` sin abrir ventana: qué se selecciona, qué gesto abre un punto de
# deshacer, cuándo se ofrece un asa. Va después de construir la app porque reutiliza su
# framework, y sin optimizar porque compilar toda la interfaz con -O no cambia lo que mide.
swiftc -D PRUEBAS -target arm64-apple-macos14.0 \
    tools/estado/main.swift apps/mac/Sources/*.swift \
    -F core/build/bin/macosArm64/debugFramework -framework YunkilCore \
    -o build/estado-arnes
./build/estado-arnes || fallos=$((fallos + 1))

paso "Orden de terminal"
# Enlaza el ejecutable nativo y lo hace hablar. Comparte la compilación con el framework
# de la aplicación, así que lo que se paga aquí es el enlazado.
./scripts/construir-cli.sh >/dev/null
if salida=$(./build/yunkil perfiles); then
    echo "${salida%%$'\n'*}"
else
    echo "la orden de terminal no arrancó"
    fallos=$((fallos + 1))
fi

if [ "$fallos" -gt 0 ]; then
    printf '\n\033[1;31m%s comprobaciones fallaron\033[0m\n' "$fallos"
    exit 1
fi
printf '\n\033[1;32mTodo comprobado\033[0m\n'
