# Yunkil — Subproyecto 1: Núcleo SDF + viewport Metal

Fecha: 2026-08-03
Estado: diseño aprobado, pendiente de plan de implementación

## Contexto

Yunkil es una herramienta de modelado de sólidos para impresión 3D, orientada a
macOS e iPadOS. Su tesis es que el modelo no debe ser una malla, sino un **árbol
de funciones de distancia con signo (SDF)**, porque esa representación resuelve
a la vez tres problemas que en un kernel B-rep son tres proyectos distintos:

1. Las operaciones booleanas son `min` / `max`: robustas por construcción, sin
   casos degenerados.
2. El sólido resultante es estanco y manifold por teorema, no por comprobación.
   Es el requisito número uno de la impresión 3D y sale gratis.
3. El grosor de pared en un punto interior es literalmente el valor del campo
   multiplicado por dos. La regla más difícil del analizador de fabricación se
   vuelve una lectura directa.

Además, el árbol se compila a un shader que se raymarchea en GPU, lo que elimina
todo el pipeline de mallas del camino interactivo, y se serializa a un DSL
compacto que un LLM puede escribir con fiabilidad.

El producto completo consta de cuatro subproyectos secuenciales:

| | Subproyecto | Aporta |
|---|---|---|
| **1** | Núcleo SDF + viewport Metal | Modelar y ver. La base de todo. |
| 2 | Analizador de fabricación + exportación autoverificada | Herramienta fiable. |
| 3 | Puente LLM (local primero) | Diferenciación de producto. |
| 4 | Puente Bambu Lab (3MF, LAN, seguimiento) | Del diseño al plato. |

**Este documento especifica únicamente el subproyecto 1.** Cada uno de los
siguientes tendrá su propia especificación y su propio plan.

## Objetivo del subproyecto 1

Componer una pieza sólida a base de primitivas y operaciones booleanas
paramétricas, y verla y manipularla en tiempo real en Mac y iPad. Sin analizador,
sin exportación, sin LLM.

Es la base de la que dependen los otros tres subproyectos, y el punto donde se
demuestra o se refuta la tesis técnica del producto.

## Restricciones de partida

- **Plataformas**: macOS e iPadOS/iOS. Android y web quedan fuera. Esta decisión
  elimina la divergencia de APIs gráficas: Metal y MSL son los únicos objetivos.
- **Arquitectura**: núcleo en Kotlin Multiplatform compilado a XCFramework;
  interfaz y renderizado en SwiftUI + Metal, compartidos entre Mac y iPad.
- **Usuario inicial**: el propio autor. No hay cuentas, nube, onboarding ni
  monetización en ningún subproyecto de esta serie.
- **Equipo de referencia**: Apple M4 Max, GPU de 32 núcleos, 36 GB unificados,
  Metal 4. El iPad es el objetivo restrictivo, no el Mac.
- **Toolchain verificado**: Xcode 26.6 con SDK iOS 26.5 y compilador Metal;
  Kotlin/Native 2.0.21; JDK 21. Requiere `xcode-select -s` hacia Xcode y la
  descarga de runtimes de simulador iOS, ninguno de los dos instalado a fecha de
  hoy.

## Arquitectura

```
YunkilCore (Kotlin Multiplatform → XCFramework)
├── kernel/      árbol SDF, evaluación exacta en CPU, cotas
├── documento/   parámetros, expresiones, operaciones, historial, formato .yunkil
└── codegen/     árbol SDF → función MSL

Yunkil (SwiftUI + Metal, objetivos macOS e iPadOS)
├── Viewport/    MTKView, raymarcher, cámara orbital, gobernador de recursos
└── UI/          árbol de piezas, inspector de parámetros, paleta de primitivas
```

La frontera es estrecha y unidireccional: Swift le pide al núcleo que aplique
operaciones y que le devuelva MSL y valores de uniforms. El núcleo no sabe nada
de Metal, de SwiftUI ni de gestos, y por eso es enteramente testeable sin GPU.

### `kernel`

Un tipo sellado `SdfNode` con evaluación exacta en CPU:

- **Primitivas**: `Caja(semilados, redondeo)`, `Esfera(radio)`,
  `Cilindro(radio, altura, redondeo)`, `Cono`, `Toro`, `Cápsula`.
- **Operaciones**: `Union`, `Diferencia`, `Interseccion`, cada una con un
  parámetro `fusion` que, cuando es mayor que cero, aplica *smooth-min*
  polinómico y produce un acuerdo suave entre las piezas.
- **Transformación**: `Transformado(nodo, transform)` con traslación y rotación.
  La escala no uniforme queda **prohibida**: rompe la métrica del campo y haría
  que las distancias dejaran de ser distancias, lo que envenenaría al analizador
  del subproyecto 2. La escala uniforme sí se admite, compensando el factor en el
  resultado para que el campo siga siendo una distancia verdadera.
- **Modificadores**: `Vaciado(nodo, grosor)` (offset a ambos lados),
  `Repeticion(nodo, cuenta, paso)`, `Simetria(nodo, plano)`.

Interfaz de cada nodo:

- `evaluar(p: Vec3): Float` — la distancia con signo. Es la **verdad de
  referencia** del sistema entero.
- `cotas(): Aabb` — caja envolvente conservadora, usada para saltos y encuadre.
- `normal(p: Vec3): Vec3` — por diferencias centrales sobre `evaluar`.

**Invariante crítico del proyecto**: `evaluar` en Kotlin y la función `map`
generada en MSL deben producir el mismo valor. Si divergen, el viewport enseña
una cosa y el analizador del subproyecto 2 razona sobre otra, y el producto
entero pierde su credibilidad. Este invariante se protege con un test dedicado,
descrito más abajo.

### `documento`

- `Parametro(nombre, valor, unidad, minimo, maximo)`. Las unidades son
  milímetros reales desde el primer día.
- **Expresiones**: un evaluador pequeño y cerrado, no un lenguaje. Admite
  números, referencias a parámetros, `+ - * /`, `min`, `max`, `abs`, `sqrt` y
  paréntesis. Detecta ciclos de referencias y los rechaza.
- `Documento(parametros, raiz: SdfNode, historial)`.
- **Operaciones declarativas**: `AnadirNodo`, `EliminarNodo`, `FijarParametro`,
  `FijarTransform`, `Reparentar`, `FijarFusion`. Toda mutación del documento pasa
  por una de ellas, y cada una se valida contra el estado actual antes de
  aplicarse.

  Esta decisión no es estética. Es exactamente el tipo que emitirá el LLM en el
  subproyecto 3. Diseñarlo así desde el principio es lo que hace que aquel
  subproyecto sea barato en lugar de una reescritura, y garantiza que el LLM
  nunca ejecute código: solo propone operaciones que el núcleo valida y puede
  rechazar.

- **Historial**: pila de operaciones con su inversa. Deshacer y rehacer son
  fiables por construcción, incluido para los cambios que en el futuro origine
  el LLM.
- **Formato de archivo `.yunkil`**: JSON vía kotlinx.serialization. Legible,
  diffable y versionado con un campo de versión de esquema desde la v1.

### `codegen`

Traduce el árbol a una función MSL `float map(float3 p)`.

Dos decisiones de diseño mandan aquí:

1. **Los parámetros se emiten como uniforms, nunca como literales.** Mover un
   deslizador solo reescribe un buffer de uniforms; no recompila nada y no
   pierde un solo fotograma. El shader únicamente se regenera cuando cambia la
   *topología* del árbol (se añade o se quita una pieza), lo que cuesta
   milisegundos y se cachea por hash de la topología.

2. **El generador escribe los techos, no los hereda.** Todo bucle emitido lleva
   un tope constante de iteraciones (`MAX_PASOS`, `MAX_DIST`). No se emite jamás
   un bucle sin cota. Esto no es una buena intención: es la salvaguarda que
   impide colgar el driver gráfico de macOS, que es el único riesgo real que
   este enfoque tiene sobre el equipo del usuario.

### Viewport (Swift + Metal)

- `MetalViewport`: un `MTKView` envuelto en `NSViewRepresentable` en macOS y en
  `UIViewRepresentable` en iPadOS. Dibuja un cuadrilátero a pantalla completa; el
  fragment shader raymarchea la función `map` generada.
- **Cámara orbital**: en Mac, trackpad y ratón; en iPad, dos dedos para orbitar y
  pinza para acercar. Encuadre automático a las cotas del modelo.
- **Sombreado**: normales por gradiente del campo, iluminación difusa y oclusión
  ambiental barata derivada del propio raymarch. Suficiente para leer la forma;
  el realismo no es un objetivo.

### Gobernador de recursos

Traduce el requisito de «aprovechar el hardware al máximo sin poner en riesgo el
equipo» en cuatro mecanismos concretos, porque «usar todo lo que haya» es
precisamente cómo se bloquea una máquina:

1. **Presupuesto derivado del dispositivo.** Al arrancar se consulta la GPU y se
   fijan resolución de render, escala dinámica y tope de pasos de raymarch. El
   M4 Max y un iPad no reciben las mismas constantes.
2. **Despacho por tiles.** El trabajo se reparte en command buffers cortos
   repartidos entre fotogramas. Este es el riesgo de caída real en iPadOS: si un
   command buffer tarda demasiado, el vigilante de la GPU termina la aplicación.
   Sin trocear, la app se cae en iPad; no es una hipótesis.
3. **Techos duros en shader**, ya garantizados por el generador.
4. **Degradación térmica.** Se vigila `ProcessInfo.thermalState` y la escala de
   render baja sola antes de que el equipo se caliente. Aprovechar el hardware
   es adaptarse a él, no exprimirlo hasta que proteste.

En ningún momento se modifican ajustes del sistema, ni se usa `sudo`, ni se toca
`iogpu.wired_limit_mb`.

## Flujo de datos

```
gesto o edición en la UI
   → Operacion
   → Documento la valida y la aplica  (si es inválida: se rechaza, estado intacto)
   → ¿cambió la topología del árbol?
        sí → regenerar MSL, compilar en segundo plano, cachear por hash
        no → nada
   → actualizar buffer de uniforms
   → siguiente fotograma
```

El camino habitual —arrastrar un deslizador— no toca el compilador en absoluto.

## Manejo de errores

- **Operación inválida**: se rechaza con un motivo legible y el documento queda
  intacto. Nunca hay estados a medio aplicar.
- **Fallo de compilación del shader**: se conserva el shader anterior en uso y se
  informa del error. El usuario nunca ve una pantalla negra.
- **Pérdida del dispositivo Metal**: se recrean los recursos y se recompila desde
  el documento, que es la única fuente de verdad.
- **Presupuesto de fotograma excedido**: baja la escala de render. Nunca se
  aborta ni se congela.
- **Archivo `.yunkil` corrupto o de esquema futuro**: se rechaza al abrir con un
  mensaje claro. No se intenta reparar ni se abre a medias.

## Estrategia de pruebas

- **Kernel**: distancias contrastadas contra fórmulas cerradas conocidas — la
  distancia a una esfera desde fuera es `|p| - r`, y así con cada primitiva.
  Tests de propiedad sobre las operaciones: la unión nunca devuelve una distancia
  mayor que la de sus operandos.
- **Paridad CPU ↔ GPU** *(el test que protege el invariante crítico)*: se compila
  el MSL generado, se evalúa sobre una nube de puntos aleatorios dentro y fuera
  del sólido, y se compara con `evaluar` de Kotlin. Tolerancia `1e-4`. Este test
  es el que impide que el producto mienta.

  Por necesitar GPU, no puede vivir entre los tests comunes de Kotlin: se
  implementa como test XCTest en macOS, que llama al núcleo a través del
  XCFramework y ejecuta el MSL en un compute kernel. Es el único test del
  subproyecto que depende de Xcode.
- **Documento**: propiedades del historial — aplicar cualquier operación y
  deshacerla devuelve exactamente el estado original. Ida y vuelta de
  serialización.
- **Expresiones**: evaluación correcta, detección de ciclos, rechazo de
  referencias inexistentes.
- **Rendimiento**: presupuesto de fotograma medido en Mac y en iPad, con un
  umbral que hace fallar la prueba si se supera. El rendimiento es un requisito,
  no una aspiración, y por tanto se verifica.

## Criterios de aceptación

1. Componer una pieza de veinte nodos o más y orbitarla a 60 fps o más en iPad y
   a 120 fps o más en Mac.
2. Paridad CPU/GPU dentro de `1e-4` sobre la batería completa de primitivas y
   operaciones.
3. Guardar y abrir `.yunkil` sin pérdida de información.
4. Deshacer y rehacer fiables sobre secuencias largas y mixtas.
5. Treinta minutos de sesión con cambios continuos de topología sin una sola
   caída ni fuga de memoria apreciable.
6. Ningún ajuste del sistema modificado.

## Fuera del alcance de este subproyecto

- Analizador de fabricación y sus reglas — subproyecto 2.
- Mallado, exportación STL/3MF y verificación de la malla — subproyecto 2.
- Puente LLM — subproyecto 3.
- Conexión con impresoras Bambu Lab — subproyecto 4.
- **Perfiles 2D** (polígonos, arcos y bézier extruidos o revolucionados). Son la
  respuesta a la carencia de bocetos y son imprescindibles para piezas reales,
  pero pertenecen al inicio del subproyecto 2, donde el analizador ya justifica su
  existencia.
- Restricciones paramétricas con solucionador geométrico, al estilo de Fusion.
  Quedan fuera del producto entero por ahora; la acotación numérica directa más
  las órdenes en lenguaje natural del subproyecto 3 cubren el caso de uso
  objetivo.
- Un laminador propio. Es un subsistema mayor que los otros cuatro juntos y
  hundiría el proyecto. Yunkil entrega 3MF orientado y delega el laminado.

## Riesgos conocidos

- **Rendimiento del raymarch en iPad** con árboles grandes. Mitigado por el
  gobernador de recursos y por saltos basados en cotas por nodo. Es el riesgo
  que este subproyecto existe para medir cuanto antes.
- **Divergencia CPU/GPU** por diferencias de precisión en coma flotante. Mitigada
  por el test de paridad y por mantener una sola definición de cada primitiva de
  la que se derivan ambas implementaciones.
- **Exactitud dimensional al exportar**. No afecta a este subproyecto, pero
  condiciona su diseño: por eso se prohíbe la escala no uniforme y por eso el
  campo debe ser una distancia verdadera y no una aproximación.
