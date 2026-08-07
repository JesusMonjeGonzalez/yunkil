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

ICONSET="$RAIZ/build/Yunkil.iconset"
rm -rf "$ICONSET"
mkdir -p "$ICONSET"
for spec in "16 icon_16x16.png" "32 icon_16x16@2x.png" "32 icon_32x32.png" "64 icon_32x32@2x.png" "128 icon_128x128.png" "256 icon_128x128@2x.png" "256 icon_256x256.png" "512 icon_256x256@2x.png" "512 icon_512x512.png" "1024 icon_512x512@2x.png"; do
    set -- $spec
    sips -s format png -z "$1" "$1" "$RAIZ/apps/mac/Yunkil.svg" --out "$ICONSET/$2" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/Yunkil.icns"

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
    <key>CFBundleIconFile</key><string>Yunkil.icns</string>
    <key>LSMinimumSystemVersion</key><string>14.0</string>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSPrincipalClass</key><string>NSApplication</string>
    <key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>
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
