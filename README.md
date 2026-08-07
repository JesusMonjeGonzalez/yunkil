# Yunkil

Modelado de sólidos para impresión 3D en macOS, con núcleo preparado para compartir código con iPadOS.

El modelo no es una malla: es un **árbol de funciones de distancia con signo**.
Las booleanas son robustas por construcción, los sólidos salen estancos por
teorema, los acuerdos son un parámetro y el grosor de pared es una lectura
directa del campo.

Núcleo en Kotlin Multiplatform, interfaz en SwiftUI, raymarching en Metal.

```bash
./scripts/instalar.sh          # construye e instala en /Applications
./scripts/comprobar.sh         # núcleo + paridad CPU↔GPU + rayo + app
./gradlew :core:jvmTest        # 336 pruebas del núcleo, sin GPU
./gradlew :core:banco --args="URL MODELO 3"   # el modelo local, tres vueltas por caso
```

Se modela con el cursor: pinchar selecciona la pieza, ⌘ y arrastrar empuja su cara, y el
clic derecho lleva filete de un canto, apoyar esa cara en el plato, aislar y sacar del
grupo. Vistas de siempre en 1/3/7/9 y proyección paralela en 5.

El sólido se examina contra un perfil de impresora antes de entregarlo: pared,
detalle, voladizo, apoyo y esbeltez se miden sobre el campo, cada aviso nombra la
pieza que lo causa y trae un arreglo que se puede pulsar. La malla no sale sin pasar
su propio examen —cerrada, bien orientada, sin auto-intersecciones y con la
desviación medida—, y se entrega en 3MF con las unidades declaradas y la pieza ya
apoyada en el plato, o en STL.

Cuando la pieza la escribe un modelo de lenguaje, lo que falla es el contacto entre
partes. Yunkil no se limita a avisarlo: si sabe qué operación une lo que quedó
suelto, la aplica, vuelve a medir el campo y solo entonces enseña la propuesta.

- **Cómo funciona** → [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md)
- **Qué falta** → [PROXIMOS-PASOS.md](PROXIMOS-PASOS.md)
- **DAFO y dirección competitiva** → [docs/DAFO-COMPETITIVO.md](docs/DAFO-COMPETITIVO.md)
- **Estado de cierre** → [docs/ESTADO-2026-08-03.md](docs/ESTADO-2026-08-03.md)
- **Modelos de IA, privacidad y el banco** → [docs/IA.md](docs/IA.md)
- **Qué tienen los competidores y cómo se hace aquí** → [docs/TOP10-COMPETIDORES.md](docs/TOP10-COMPETIDORES.md)
