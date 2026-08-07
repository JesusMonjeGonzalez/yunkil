# Yunkil — documentación técnica

Herramienta de modelado de sólidos para impresión 3D en macOS e iPadOS.
Este documento describe el sistema tal y como está construido hoy, no lo que se
planea. Para lo que falta, ver [PROXIMOS-PASOS.md](../PROXIMOS-PASOS.md).

---

## 1. La tesis

Yunkil no representa el modelo como una malla, sino como un **árbol de funciones
de distancia con signo** (SDF). Un sólido es una función `f(x,y,z)` que devuelve
la distancia al material más cercano: negativa dentro, positiva fuera, cero en la
superficie.

Esa elección resuelve de golpe cuatro problemas que en un kernel de contornos
(B-rep) son cuatro proyectos distintos:

| Problema | Con mallas / B-rep | Con SDF |
|---|---|---|
| Booleanas robustas | El problema clásico sin resolver de la geometría computacional | `min` y `max`. Sin casos degenerados |
| Sólido estanco y manifold | Hay que comprobarlo y repararlo | Se cumple por construcción |
| Acuerdos y redondeos | La parte más cara del kernel | Un parámetro (`smooth-min`) |
| Grosor de pared | Requiere análisis geométrico | Es el valor del campo por dos |

La cuarta fila es la que sostiene el producto: el analizador de fabricación que
viene en el subproyecto 2 obtiene su regla más difícil como una lectura directa.

El coste real está en la exportación: sacar una malla con aristas vivas exige
contorneado dual, que es el trabajo más delicado que queda pendiente.

---

## 2. Mapa de módulos

```
core/  (Kotlin Multiplatform → XCFramework)
├── kernel/    Vec3, Quat, Transform, Aabb, árbol SDF, evaluación exacta en CPU
├── doc/       piezas, parámetros, operaciones de edición, historial, ejemplos
├── msl/       compilador del árbol a Metal Shading Language
└── tools/     (solo JVM) volcado de casos para el arnés de paridad

apps/mac/Sources/  (SwiftUI + Metal)
├── App.swift          interfaz: árbol, paleta, inspector, barra de herramientas
├── Renderizador.swift pipeline Metal y sincronización con el editor
├── Camara.swift       cámara orbital y empaquetado de sus uniforms
└── Gobernador.swift   reparto del presupuesto de GPU

tools/paridad/  arnés en Swift que verifica el invariante crítico
```

La frontera entre Kotlin y Swift es estrecha y unidireccional: Swift pide al
editor que aplique una operación y recoge un shader y un puñado de números. El
núcleo no conoce Metal, SwiftUI ni gestos, y por eso se prueba entero sin GPU.

---

## 3. El kernel (`core/kernel`)

`SdfNode` es una interfaz sellada. Cada nodo ofrece tres cosas:

- `evaluar(p: Vec3): Float` — la distancia con signo. **Es la verdad de
  referencia de todo el sistema.**
- `cotas(): Aabb` — caja envolvente conservadora: puede sobrar, nunca faltar.
- `escalares: List<Float>` — todos sus valores numéricos, en orden fijo.

### Nodos disponibles

**Primitivas** — `Esfera`, `Caja` (con redondeo), `Cilindro` (con redondeo),
`Cono` (tronco de cono: da chaflanes y conicidades de desmoldeo), `Toro`,
`Capsula`.

**Booleanas** — `Union`, `Diferencia`, `Interseccion`. Todas aceptan `fusion`:
con cero son booleanas exactas; por encima mezclan los campos en una banda de esa
anchura y producen un acuerdo redondeado.

**Modificadores** — `Transformado`, `Vaciado` (cáscara de grosor constante),
`Simetria` (espejo respecto a un plano por el origen), `Repeticion` (copias
lineales, tope duro de 64).

**`AcuerdoLocal`** — la misma booleana, pero con el acuerdo **limitado a un sitio**: es el
filete de un canto concreto. La anchura de la mezcla se multiplica por una caída centrada
en un punto, así que dentro de esa esfera hay redondeo y fuera la booleana sigue exacta.
La ventaja sobre un kernel de contornos es que no hace falta topología: la arista se elige
apuntando con el cursor y el punto de impacto es el centro.

Es el único nodo del kernel que **no es 1-Lipschitz**, y eso está medido en vez de
escondido: el mínimo suave no se aparta del exacto más de `k/4` y la caída `(1−t²)²` tiene
pendiente máxima 1,54, así que el gradiente sube 0,385 por unidad de `fusion/radio`. La
prueba mide 1,383 en el peor caso frente al 1,385 que predice la cuenta. De ahí sale
`ShaderGenerado.pasoSeguro`, que el renderizador multiplica por el paso del gobernador: un
filete pequeño no cuesta casi nada y solo se paga cuando la mezcla es tan ancha como su
alcance.

### Restricciones deliberadas

- **La escala no uniforme está prohibida.** Deformaría el campo y las distancias
  dejarían de ser distancias, lo que envenenaría al analizador de fabricación.
  La uniforme sí se admite, compensando el factor en el resultado.
- **La repetición tiene tope constante** (`Repeticion.MAXIMO = 64`) porque se
  desenrolla en el shader.

---

## 4. El documento (`core/doc`)

El documento es una estructura **distinta** del árbol SDF, y a propósito.

`Pieza` es lo que el usuario edita: tiene nombre, tipo, parámetros en milímetros,
transformación, hijos y visibilidad. `Pieza.compilar()` la traduce al árbol SDF.
Separarlos mantiene el kernel puro y permite que el documento crezca sin tocar las
matemáticas.

Los parámetros de cada tipo se declaran en `TipoPieza.parametros` con etiqueta,
rango y valor por defecto. **La interfaz construye su inspector a partir de esa
declaración**, así que añadir una primitiva al kernel no obliga a tocar Swift.

### Operaciones y validación

Toda mutación pasa por un método de `Editor` que valida antes de aplicar. Si el
cambio no es legal, el documento queda intacto y `ultimoError` explica el motivo.
Las políticas concretas:

- Añadir sobre una primitiva coloca la pieza como **hermana** en lugar de
  rechazar: es lo que casi siempre quería quien pulsó el botón.
- Un parámetro fuera de rango **se recorta**, no se rechaza: un deslizador nunca
  debe poder atascarse.
- Un parámetro que no existe en ese tipo **sí se rechaza**.
- La raíz no se puede eliminar ni envolver.

Esta disciplina existe hoy por el deshacer. Mañana será el canal por el que un
modelo de lenguaje proponga cambios **sin poder ejecutar código**: solo emite
operaciones que el núcleo valida y puede rechazar.

### Historial

El historial guarda instantáneas del documento, no operaciones inversas. Con un
árbol inmutable una instantánea es una referencia, así que sale igual de barato y
no puede desincronizarse: es imposible escribir mal la inversa de una operación
que no existe. Profundidad máxima: 200.

Arrastrar un deslizador **no** anota historial en cada fotograma. El punto de
deshacer se marca al empezar el arrastre (`confirmarEdicionContinua`).

### Formato de archivo

`.yunkil` es JSON vía kotlinx.serialization, legible y diffable. Un archivo
corrupto se rechaza al abrir dejando el documento actual intacto; no se intenta
reparar ni se abre a medias.

---

## 5. El generador de MSL (`core/msl`)

Traduce el árbol a una función `float yk_map(float3 p, constant float *u)` más un
raymarcher completo. Dos reglas lo gobiernan.

### Regla 1 — los números son uniforms, nunca literales

El generador recorre el árbol en preorden asignando a cada nodo un tramo del
buffer `u`. `empaquetarUniforms()` recorre el árbol **en ese mismo orden**
recogiendo los valores, de modo que ambos no pueden desalinearse.

La consecuencia es el diseño de rendimiento del producto:

| Acción | Coste |
|---|---|
| Mover un deslizador | Reescribir un buffer. No recompila |
| Añadir o quitar una pieza | Regenerar y compilar el shader (milisegundos) |

`huellaTopologica` identifica la *forma* del árbol ignorando todos sus valores
numéricos. El editor compara huellas y le dice al renderizador si hace falta
recompilar; el renderizador no lo adivina.

### Regla 2 — todo bucle emitido lleva tope constante

`YK_MAX_PASOS`, `YK_PASOS_AO`. Ningún bucle del shader depende de un valor en
tiempo de ejecución. **Esta es la salvaguarda que impide colgar el driver
gráfico**, y vive en el generador precisamente para que no dependa del criterio de
quien escriba el shader. Un test lo verifica sobre el código emitido.

La repetición se desenrolla en lugar de emitir un bucle, por la misma razón.

---

## 6. El invariante crítico y cómo se protege

> `SdfNode.evaluar` en Kotlin y la función `yk_map` generada en MSL deben
> producir el mismo valor.

Si divergen, el viewport enseña una geometría y el analizador de fabricación
razona sobre otra. Todo lo que Yunkil promete se apoya en esta igualdad.

`tools/paridad` es un ejecutable Swift que compila el MSL generado en Metal real,
lo evalúa en un compute kernel sobre los mismos puntos que evaluó Kotlin y
compara. Las muestras van en dos poblaciones: una nube uniforme y otra proyectada
sobre la superficie, que es donde las fórmulas se rompen si están mal.

```
./gradlew :core:volcarParidad     # vuelca 19 casos con sus distancias esperadas
swiftc -O tools/paridad/main.swift -o build/paridad
./build/paridad core/build/paridad
```

Estado actual: **19 casos, 19 000 puntos, peor desvío 1,53e-05** frente a una
tolerancia de 1e-4.

---

## 7. El renderizador

Un cuadrilátero a pantalla completa y un fragment shader que raymarchea el campo.
No hay buffers de vértices, ni índices, ni buffer de profundidad: el raymarcher
resuelve la visibilidad por sí mismo.

El sombreado usa normales por gradiente del campo, luz difusa, un relleno cenital,
oclusión ambiental barata derivada del propio raymarch y un especular suave que
ayuda a leer la curvatura. El realismo no es un objetivo.

### Gestión de errores

- Si el shader no compila, **se conserva el pipeline anterior** y se informa. El
  usuario nunca ve una pantalla negra.
- Si se pierde el dispositivo Metal, se recrean los recursos desde el documento,
  que es la única fuente de verdad.

---

## 8. El gobernador de recursos

«Aprovechar el hardware al máximo» y «no poner en riesgo el equipo» solo son
compatibles si algo vigila y cede. Nunca se modifica ningún ajuste del sistema.

1. **Presupuesto derivado del dispositivo.** La cadencia objetivo la fija la
   pantalla (`NSScreen.maximumFramesPerSecond`), no una constante.
2. **Escala de render dinámica** entre el 40 % y el 100 % de la resolución
   nativa. La capa estira el resultado, que es más barato que recortar calidad de
   sombreado.
3. **Degradación térmica** vigilando `ProcessInfo.thermalState`: se cede
   resolución *antes* de que el sistema tenga que limitar por sí mismo.
4. **Techos duros en shader**, garantizados por el generador.

> **Cuidado con el vsync.** El dibujo está sincronizado con la pantalla, así que
> un fotograma nunca baja del intervalo de vsync por mucho margen que sobre. Medir
> «va sobrado» como «tarda bastante menos que el objetivo» es una condición
> imposible de cumplir, y deja la resolución caída para siempre. Este fallo ya se
> cometió una vez: la app corría al 40 % en un M4 Max. La condición de subida es
> «llegamos a tiempo», no «vamos sobrados».

Pendiente para iPadOS: **despacho por tiles**. Si un command buffer tarda
demasiado, el vigilante de la GPU termina la aplicación. Es el riesgo de caída
real en iPad y no está implementado todavía.

---

## 9. Compilar, ejecutar y probar

Requisitos: macOS con Xcode completo seleccionado (`xcode-select -s
/Applications/Xcode.app/Contents/Developer`), JDK 21.

```bash
./scripts/construir-mac.sh debug      # o release
./scripts/instalar.sh                 # copia a /Applications
./gradlew :core:jvmTest               # 295 tests del núcleo, sin GPU
./gradlew :core:volcarParidad && ./build/paridad core/build/paridad   # 22 casos
swiftc -O tools/rayo/main.swift apps/mac/Sources/Camara.swift -o build/rayo && ./build/rayo
./gradlew :core:banco                 # peticiones reales contra el modelo local
```

`tools/rayo` verifica lo que ninguna prueba del núcleo puede: que el rayo que sale del
cursor se construya **igual** que en el shader. Un signo invertido ahí no da error, da otra
pieza. `:core:banco` no falla la build a propósito: que un modelo local acierte 6 de 8 es
un dato, no un error de compilación.

### Qué cubren los tests

- **Kernel**: distancias contrastadas contra fórmulas cerradas conocidas por cada
  primitiva; propiedades de las booleanas (sin fusión, la unión es exactamente el
  mínimo); contención de las cotas sobre 3 000 puntos; normales unitarias.
- **Generador**: el número de uniforms reservados coincide con los empaquetados;
  ningún valor se cuela como literal; cambiar un parámetro no altera la huella
  pero cambiar la estructura sí; todo bucle emitido lleva tope.
- **Editor**: añadir, eliminar, envolver, reordenar, ocultar; recorte y rechazo de
  parámetros; deshacer y rehacer; ida y vuelta por JSON; rechazo de archivos
  corruptos.

---

## 10. Límites conocidos

*Revisado el 6 de agosto de 2026. Este apartado se había quedado desfasado: decía que no
había bocetos 2D, ni exportación, ni analizador, y las tres cosas existen desde el 4 de
agosto.*

- **Hay perfiles 2D** (`kernel.Perfil2D`, `EXTRUSION`, `REVOLUCION`, `BARRIDO`) pero **no
  hay solucionador de restricciones**: se acota con números y se pega con imanes. Es una
  decisión, no una carencia pendiente.
- **Hay exportación STL con certificado** (`malla/Exportacion.kt`) y analizador de
  fabricación (`fabricacion/Analizador.kt`). Falta 3MF y quedan las auto-intersecciones.
- **Solo macOS.** El núcleo compila para iOS pero no hay app de iPad, y antes de abrirla
  hay que resolver el despacho por tiles o el vigilante de la GPU mata la aplicación.
- **Sin gizmos dibujados**, pero sí hay manipulación directa: pinchar selecciona
  (`doc/Picking.kt`), ⌘ y arrastrar empuja la cara (`doc/Asas.kt`), y el clic derecho lleva
  filete, apoyo en el plato, aislar y sacar del grupo.
- **La malla importada no se pinta.** El viewport enseña su envolvente porque el generador
  de MSL emite matemática pura y un campo horneado necesita una textura 3D. La evaluación
  en CPU sí es el campo real, así que restar, ahuecar, analizar y exportar son exactos.
- **Un `AcuerdoLocal` no es 1-Lipschitz.** Está medido (1,383 de gradiente peor con la
  mezcla tan ancha como su alcance) y compensado con el paso seguro que publica el
  generador, no escondido.
- Kotlin 2.0.21 avisa de que Xcode 26.6 supera su versión probada (máx. 16.0).
  Compila y funciona, pero es el primer sospechoso si algo raro aparece en iOS.
