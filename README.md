# Yunkil

Modelado de sólidos para impresión 3D, en macOS e iPadOS.

El modelo no es una malla: es un **árbol de funciones de distancia con signo**.
Las booleanas son robustas por construcción, los sólidos salen estancos por
teorema, los acuerdos son un parámetro y el grosor de pared es una lectura
directa del campo.

Núcleo en Kotlin Multiplatform, interfaz en SwiftUI, raymarching en Metal.

```bash
./scripts/instalar.sh          # construye e instala en /Applications
./gradlew :core:jvmTest        # 48 tests del núcleo, sin GPU
```

- **Cómo funciona** → [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md)
- **Qué falta** → [PROXIMOS-PASOS.md](PROXIMOS-PASOS.md)
