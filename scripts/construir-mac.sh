#!/bin/bash
# Construye Yunkil.app para macOS.
#
# Se arma el paquete a mano en lugar de mantener un proyecto de Xcode: son cuatro
# ficheros y así el build es reproducible desde la terminal y desde cualquier agente.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${1:-debug}"
APP="$RAIZ/build/Yunkil.app"

case "$CONFIG" in
    debug)   TAREA="linkDebugFrameworkMacosArm64";   DIR_FW="debugFramework";   OPTIM="-Onone" ;;
    release) TAREA="linkReleaseFrameworkMacosArm64"; DIR_FW="releaseFramework"; OPTIM="-O" ;;
    *) echo "uso: $0 [debug|release]" >&2; exit 1 ;;
esac

echo "==> Núcleo Kotlin ($CONFIG)"
"$RAIZ/gradlew" -p "$RAIZ" ":core:$TAREA" --console=plain -q

FRAMEWORKS="$RAIZ/core/build/bin/macosArm64/$DIR_FW"
[ -d "$FRAMEWORKS/YunkilCore.framework" ] || { echo "falta YunkilCore.framework" >&2; exit 1; }

echo "==> Paquete de la aplicación"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>Yunkil</string>
    <key>CFBundleDisplayName</key><string>Yunkil</string>
    <key>CFBundleExecutable</key><string>Yunkil</string>
    <key>CFBundleIdentifier</key><string>com.yunkil.mac</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>0.1</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>14.0</string>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSPrincipalClass</key><string>NSApplication</string>
</dict>
</plist>
PLIST

echo "==> Swift + Metal"
swiftc $OPTIM \
    -target arm64-apple-macos14.0 \
    -parse-as-library \
    -F "$FRAMEWORKS" \
    -framework YunkilCore \
    -o "$APP/Contents/MacOS/Yunkil" \
    "$RAIZ"/apps/mac/Sources/*.swift

# Firma local: sin ella macOS niega la red y algunas APIs, y Gatekeeper protesta al abrir.
codesign --force --sign - "$APP" 2>/dev/null || echo "aviso: no se pudo firmar (no es bloqueante)"

echo "==> Listo: $APP"
