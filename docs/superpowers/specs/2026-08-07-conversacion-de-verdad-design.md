# La conversación de verdad

*7 de agosto de 2026.*

Yunkil dice que su foso es **conversación editable + fabricación verificada**. La
segunda mitad está construida y verificada. La primera es un campo de texto de un
solo tiro que aplica sin preguntar. Este documento describe cómo se construye la
primera mitad al nivel de la segunda.

## El problema, en concreto

`EstadoDeLaApp.construirConIA` hace: interpretar → revisar → **aplicar**. De ahí
salen tres carencias que se notan al primer minuto de uso:

1. **No ves lo que va a pasar.** El plan se aplica y descubres el resultado
   mirando el viewport. Si está mal, deshaces.
2. **Es todo o nada.** Un plan de seis operaciones con cinco buenas y una mala se
   descarta entero.
3. **No hay hilo.** Para decir «más grueso» hay que redescribir la pieza, porque
   la siguiente petición nace sin memoria de la anterior.

Y una cuarta, más de fondo: **nadie mira la pieza**. `RevisorDeGeometria` mide
números —sólidos sueltos, restas que no cortan, cotas— y eso subió el suelo, pero
un modelo que coloca el asa donde no va produce números perfectos.

## Qué se construye

### 1. Ver antes de aplicar

**`PlanDeModelado.explicar()`** (núcleo, `yunkil.ia`). Traduce el plan a español
legible y determinista, una línea por operación, con las cotas resueltas donde el
núcleo las conoce:

```
Caja 60 × 40 × 20 mm llamada «Base»
4 taladros M3 pasantes en patrón VESA 100
Redondeo de 1,5 mm en el canto Base ↔ Tapa
```

Es una función pura sobre el plan, así que se prueba sin documento ni modelo. Y
sirve además para etiquetar el historial navegable que ya existe
(`Documento.planesAplicados`), que hoy se muestra sin nombre.

**Propuesta pendiente en vez de aplicación directa.** `construirConIA` termina en
un estado `propuestaPendiente` en lugar de mutar el documento. La interfaz ofrece
**aceptar**, **descartar** y **aceptar parcial** con una casilla por operación. El
`Aplicador` ya es transaccional con un único punto de deshacer, así que aplicar un
subconjunto no exige maquinaria nueva: es el mismo camino con menos operaciones.

Regla de coherencia: si el usuario desmarca una operación de la que dependen otras
—por alias o por objetivo—, se desmarcan también las dependientes y se dice por
qué. Aplicar un `taladro` sobre un alias que ya no se crea produce una omisión
silenciosa, que es exactamente el modo de fallo que el revisor existe para evitar.

**Bitácora granular.** `Bitacora` registra hoy aplicada / descartada / deshecha.
Con la propuesta revisable pasa a registrar **qué operaciones concretas** aceptó la
persona y cuáles no. Es el mismo archivo JSONL y el mismo canal; cambia la
resolución del dato. Sigue sin leerlo nadie a propósito: es el dataset del paso 3
de la hoja de ruta de IA.

### 2. Hilo con memoria

**`yunkil.ia.Conversacion`** (núcleo). Turnos con rol, texto, plan asociado y
desenlace. Se guarda en el `.yunkil` junto a `planesAplicados`, porque un hilo que
se pierde al cerrar no es memoria.

Dos decisiones con motivo:

- **La historia no repite geometría.** El `Contexto` del documento ya se recalcula
  cada turno con las cotas medidas y es la verdad. Si la historia también
  describiera cotas, las dos se desincronizarían en cuanto el usuario moviera algo
  a mano, y el modelo creería la vieja. La historia lleva **intención** («pediste
  una caja con tapa»), el contexto lleva **hechos** («Base: x[−30,30] y[0,40]»).
- **Presupuesto duro.** El mensaje de sistema tiene tope de 14.000 caracteres, con
  una prueba que lo hace fallar, y el modelo local trabaja con 16K de contexto. Los
  dos últimos turnos viajan enteros; los anteriores se colapsan a una línea
  («pediste X → se aplicó Y»). La historia va en mensajes de usuario/asistente, no
  dentro del sistema.
- **Se declara la edición a mano.** Si el documento cambió por acción del usuario
  desde el último turno, el turno siguiente lo dice explícitamente. Sin eso el
  modelo corrige de memoria una geometría que ya no existe.

### 3. Que la IA vea lo que hizo

**`yunkil.ia.Vistas`** (núcleo, `commonMain`). Traza el campo SDF a cuatro vistas
ortográficas —frente, lado, planta, isométrica— **en Kotlin puro**: sin Metal, sin
malla, sin dependencia de plataforma. Sombreado Lambert sobre el gradiente del
campo, que es el mismo que ya usa el analizador. Se montan en una imagen 2×2 para
gastar un solo bloque de imagen en el contexto del modelo.

Necesita un codificador **PNG en `commonMain`**: sin comprimir, con bloques deflate
*stored* y CRC propio. Mismo patrón que ya usa `TresMf` para el ZIP, y se verifica
igual: decodificando lo escrito, no confiando en que salió bien.

La crítica del VLM entra por el mismo canal que los reparos de
`RevisorDeGeometria`, con **una restricción que no se negocia**: el VLM solo puede
**añadir** reparos, nunca aprobar ni retirar los del revisor determinista, y su
crítica se etiqueta como opinión en la interfaz. Un crítico con falsos positivos
convierte el bucle en un generador de reintentos infinitos; el revisor geométrico
se diseñó con esa regla y aquí vale doble, porque un VLM alucina donde una
comprobación de cotas no puede.

Beneficio colateral que paga el trabajo aunque el VLM decepcione: las cuatro vistas
son la **miniatura del documento** y la previsualización de cada turno del hilo.

### 4. Rediseño del shell

- Panel de conversación fijo en lugar del campo flotante `creadorVibe`; el
  inspector convive con él. El viewport manda y el resto se retira.
- Identidad visual propia. Hoy es gris de sistema y SF Symbols de serie: funciona,
  pero al lado de Shapr3D lee como demo técnica.
- **Previsualización fantasma en el viewport.** El plan propuesto ya se ejecuta en
  un `Editor` aislado dentro de `revisar`; ese árbol se compila también a shader y
  `MslGenerator` emite un cuerpo de comparación que tiñe en verde lo que se añade y
  en rojo lo que se quita. Toca `Renderizador.swift` y el generador, así que
  **entra en el arnés de paridad como cualquier otro cuerpo**. Va al final porque
  el aceptar/descartar textual ya desbloquea el flujo por sí solo.
- **Partir `App.swift` (2163 líneas) y `Editor.kt` (2202).** No es refactor
  gratuito: el panel nuevo vive justo donde hoy hay dos mil líneas en un archivo, y
  editar a ciegas ahí es donde se cuelan los fallos. Se parte por responsabilidad
  —estado, viewport, conversación, inspector, analizador, menús— no por tamaño.

## Qué se deja fuera, y por qué

**Bocetos 2D interactivos y gizmo de rotación.** Son la brecha real contra
Shapr3D y hay que cerrarla algún día, pero compiten donde ellos llevan diez años.
La conversación es donde no tienen nada.

**Cambiar de modelo o afinar uno.** El banco ya dejó dicho que el seguimiento de
esquema no es el cuello de botella. Nada de esta tanda depende de qué modelo corra
por debajo.

## Orden de construcción

1. `explicar()` + propuesta revisable + aceptar / parcial / descartar
2. Hilo persistente con afinado
3. Vistas ortográficas + miniatura + bucle visual VLM
4. Fantasma en el viewport + rediseño del shell + partir los archivos grandes

Cada paso deja la aplicación usable. El 1 y el 2 son el espinazo; el 3 y el 4
pueden pararse sin dejar nada a medias.

## Cómo se verifica

`./scripts/comprobar.sh` verde al cerrar cada paso, sin excepción — núcleo,
paridad CPU↔GPU en Metal real, arnés del rayo y construcción de la app.

Pruebas nuevas que tienen que existir:

| Qué | Cómo se comprueba |
|---|---|
| `explicar()` es determinista | Mismo plan → mismo texto; cubre las 19 operaciones del vocabulario |
| Aceptar parcial | El documento resultante contiene exactamente el subconjunto marcado |
| Dependencias | Desmarcar un `crear` desmarca las operaciones que usan su alias |
| Presupuesto del hilo | Con 20 turnos, el prompt sigue bajo 14.000 caracteres |
| El hilo no miente | Un cambio a mano entre turnos aparece declarado en el siguiente |
| PNG | Lo escrito se vuelve a decodificar y coincide píxel a píxel |
| Vistas | Una esfera de radio conocido ocupa la fracción de imagen que predice la cuenta |
| Paridad del fantasma | El cuerpo de comparación entra en `tools/paridad` contra Metal real |

Y el banco (`./gradlew :core:banco`) corre **antes y después** del paso 2. Si el
hilo no mejora la segunda petición de una conversación, quiero ese número por
delante y no una intuición.

## Riesgo declarado

El paso 3 es el que más puede decepcionar. `bonsai-ternary-27b` es el único modelo
local con `mmproj` y trabaja con 16K de contexto; puede perfectamente mirar cuatro
vistas y devolver vaguedades que no accionan nada. Si ocurre, se dice con la
medida delante y el bucle se retira; las cuatro vistas se quedan porque valen como
miniatura y previsualización por sí solas.
