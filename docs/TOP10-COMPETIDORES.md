# Yunkil — top 10 de los competidores y cómo lo hacemos nosotros

Revisado el 6 de agosto de 2026. **Los diez puntos están hechos** —el 9 en su
parte de salto por cotas, con los tiles de iPad en los próximos pasos—; lo que se
aprendió al hacerlos está en [PROXIMOS-PASOS.md](../PROXIMOS-PASOS.md), incluida una
corrección de fondo: el picking se resolvió en CPU y no en el shader, porque `evaluar` es
la verdad de referencia y un segundo invariante de paridad no se sostiene para algo que
solo ocurre al hacer clic — y así funciona también sobre una malla horneada, que el shader
todavía no pinta. Complementa a
[DAFO-COMPETITIVO.md](DAFO-COMPETITIVO.md): allí está la posición estratégica,
aquí las **capacidades concretas** que tienen ellos, qué gesto o algoritmo son
exactamente, y con qué código nuestro se resuelven.

Los diez están ordenados por lo que más estorba hoy al usar la app, no por
dificultad.

## Quién es cada uno

| Competidor | En qué es el techo | Qué le duele |
|---|---|---|
| **Shapr3D** | UX de precisión en Mac/iPad, historial, importación Parasolid v38 | IA aún cosmética (`Capture with AI` es render, no geometría) |
| **Plasticity** | Filetes y booleanas Parasolid, modelado directo para producto | Sin fabricación, sin IA, sin cotas de impresión |
| **Fusion 360** | Profundidad CAD/CAM + `Text-to-Command` del Autodesk Assistant | Pesado, nube, curva de entrada |
| **nTop** | Modelado implícito por campos: es nuestra misma tecnología, en industrial | Precio y complejidad de otro planeta |
| **Zoo (Text-to-CAD)** | Generación de CAD por lenguaje | Declara que **no garantiza fabricabilidad** |
| **MakerLab (Bambu) + Meshy 6** | Imagen → imprimible en 2 min, watertight, multicolor automático, 97 % de pase en laminador | Blob sin cotas, no editable paramétricamente |

## La tabla

| # | Lo que tienen | Quién | Estado | Cómo se hizo / se hace |
|---|---|---|---|---|
| 1 | Selección de sólido en el viewport | Todos | **hecho** | `doc/Picking.kt`: trazado en CPU y atribución por contorno más cercano. En vez de `float2(d,id)` en el shader, que habría exigido un segundo invariante de paridad |
| 2 | **Empujar/tirar de una cara** | Shapr3D, Plasticity | **hecho** | `doc/Asas.kt`: cada `TipoPieza` declara cara → parámetro, con compensación del centro por lo *conseguido*. ⌘+arrastre sobre la propia cara |
| 3 | **Filete y chaflán por arista elegida** | Plasticity (Parasolid) | **hecho** | `kernel.AcuerdoLocal`: `smooth-min` con caída centrada en el punto pinchado, y paso de trazado seguro publicado por el generador |
| 4 | **Menú contextual rico** y atajos de siempre | Todos | **hecho** | Menú en el árbol y en el viewport, `MenusDeYunkil` con ⌘D, F, ⇧F, 1/3/7/9, 5, Supr, y proyección ortográfica |
| 5 | **Plano de sección en vivo** | Todos | **hecho** | Corte en `yk_distancia`: `max(d, plano)` al raymarcher; cara de corte teñida por grosor de pared leído del campo (marcha de `Analizador.espesorEn` en el shader); hoja visible con rejilla; se arrastra sin modificador |
| 6 | **Detección de interferencias** entre cuerpos | Shapr3D 2026 (Section View) | **hecho** | `revisarEncuentros` en `Analizador.kt`: volumen solapado por muestreo de la caja común; holgura = `min(dA)` sobre la superficie de B contra `holguraEncaje` del perfil; corrección `[Separar/Holgar]` mueve la pieza |
| 7 | **Historial navegable y reeditable** | Shapr3D, Fusion | **hecho** (planes de IA) | `Documento.planesAplicados` + `Editor.reproducirPlanes()` y `reemplazarPlanEnHistoria(i, plan)` con replay por el mismo `Aplicador` |
| 8 | **Copiloto sobre la selección** (`achaflana estas aristas 0,5`) | Fusion Assistant | **hecho** | Selección con cotas en el `Contexto`; modo edición con tope de 3 operaciones (`Interprete.MAXIMO_DE_EDICION`); campo permanente en la barra |
| 9 | **Fluidez garantizada** en escenas grandes; táctil sin caídas | Shapr3D en iPad | Salto por cotas de nodo **hecho**; tiles pendientes de iPad | `yk_marcha` podada por cajas (poda exacta, verificada en Metal: 22 casos, nunca exagera) + `yk_map` intacta para paridad |
| 10 | **Un STL imprimible en dos minutos** | MakerLab + Meshy | **hecho** (flujo de una acción) | Arrastrar STL → `importarMallaDesde` hornea → analiza al momento → informe con la pieza nombrada y arreglos ejecutables → exportar verificado |

---

## 1. Selección desde el viewport y gizmo

**Ellos.** En Shapr3D tocas un cuerpo y queda seleccionado con su bounding box;
arrastras el gizmo y se mueve. Es la operación más repetida de un CAD.

**Nosotros.** Falta el eslabón: saber *qué pieza* devolvió la distancia mínima.
Es una ampliación limpia del generador, no un rediseño:

- `MslGenerator` emite hoy `float yk_map(float3 p, constant float *u)`. Pasa a
  `float2`, con `.y` = índice de nodo. En cada booleana el índice viaja con el
  ganador: `d.y = select(a.y, b.y, b.x < a.x)` en la unión, y el del minuendo en
  la diferencia — quien recorta no es quien se ve.
- El sombreado y el gradiente usan solo `.x`, así que no cambia nada más.
- La paridad CPU/GPU se mantiene añadiendo `SdfNode.evaluarConId` en Kotlin y un
  caso al arnés de `tools/paridad`. **Esto no es opcional**: si el id divergiera,
  se seleccionaría una pieza y se movería otra.
- Un compute kernel de un solo hilo raymarchea el rayo del cursor y devuelve
  punto, normal e id. Ese kernel es también el que hace falta para los puntos 3
  y 6, así que se escribe una vez.

El gizmo edita el `Transformado` de la pieza y confirma con
`confirmarEdicionContinua`, la misma disciplina de historial que ya usan los
deslizadores.

## 2. Empujar y tirar de una cara

**Ellos.** El gesto insignia de Shapr3D: seleccionas una cara plana, aparece una
flecha y la pieza crece por ese lado. En B-rep es una operación topológica.

**Nosotros.** No hay caras, y no vamos a inventarlas. Pero sí hay algo mejor
definido: **cada `TipoPieza` puede declarar sus asas**, igual que ya declara sus
parámetros en `TipoPieza.parametros` y la interfaz construye el inspector solo.

```
CAJA: asa(+X) → parámetro "ancho", desplaza el centro +Δ/2
CILINDRO: asa(+Y) → "altura", desplaza el centro +Δ/2; asa(radial) → "radio"
EXTRUSION: asa(+Y) → "altura"
```

Sin la compensación del centro la cara opuesta se mueve también, y eso es
exactamente lo que hace que empujar una cara «no funcione» en una herramienta
paramétrica. Con ella, arrastrar una cara de una caja es lo que espera cualquiera
que venga de Shapr3D, y **sigue siendo un parámetro con su número**, así que la
cota no se pierde.

Límite honesto que hay que decir en la interfaz: una pieza `MALLA` horneada no
tiene asas. Se mueve y se escala, no se empuja.

## 3. Filete y chaflán sobre la arista que elijas

**Ellos.** Es el foso de Plasticity: el motor de filetes de Parasolid con radio
constante, tangencia y solape resuelto. Con años de casos límite detrás.

**Nosotros.** Tenemos `fusion` en las booleanas —que redondea *todo* el
encuentro— y redondeo por primitiva. Falta el caso real: «este canto de aquí, a
2 mm». En campos se hace con una **máscara de influencia**:

```
AcuerdoLocal(a, b, centro, radio, k):
  w = k · caida(|p − centro| / radio)      caida suave, 1 en el centro, 0 en el borde
  d = smoothMin(a, b, w)
```

- Se mantiene 1-Lipschitz mientras `k ≤ radio` y la caída sea C¹. Hay que
  probarlo como propiedad, igual que ya se prueba que la unión sin fusión es
  exactamente el mínimo.
- **La arista se elige pinchando**, y el punto de impacto del kernel del punto 1
  es el centro de la máscara. No hace falta topología: es la ventaja del campo.
- El chaflán es el mismo nodo con `max` sesgado en vez de `smoothMin`.

Y la desventaja, dicha antes de que la descubra un usuario: es un filete «de
bola», el radio no es constante a lo largo de la arista si la arista se curva.
Para una pieza impresa a 0,4 mm de boquilla eso no se ve; para una superficie de
producto sí. No prometemos Parasolid.

## 4. Acciones, atajos y clic derecho

Ninguna de estas cuesta más de una tarde y son las que hacen que la app se sienta
terminada. Todas se apoyan en métodos de `Editor` que ya existen.

**Clic derecho en el árbol:** Renombrar (F2 / doble clic) · Duplicar (⌘D) ·
Copiar/Pegar (⌘C/⌘V) · Aislar (solo esta visible) · Ocultar (⇧H) ·
Envolver en diferencia · Extraer del padre · Exportar solo esta pieza ·
Acotar… · Analizar solo esta.

**Clic derecho en el viewport:** Seleccionar esta pieza · Filete aquí… (punto 3)
· Taladro aquí… (`Roscas` ya acepta un punto) · Sección por este plano ·
Enfocar la cámara en la pieza (F) · Poner esta cara sobre el plato.

**Atajos que echa de menos cualquiera que venga de un CAD:** ⌘D duplicar ·
F enfocar la selección · ⇧F encajar todo · 1/3/7 planta, alzado y perfil ·
5 alternar ortográfica ·  ⌥arrastre orbitar · Supr borrar · ⌘Z/⇧⌘Z (ya está).

**Y dos que faltan y son de fondo:** reparentar arrastrando en el árbol, y el
aviso al cerrar con cambios sin guardar. La segunda es un defecto, no una mejora.

## 5. Plano de sección en vivo

**Ellos.** Todos lo tienen; Shapr3D lo usa además para revisar interferencias.

**Nosotros.** Es lo más barato de esta lista y lo que mejor se ve en un vídeo:
en el raymarcher, `d = max(d, plano(p))` y pintar la superficie de corte con un
color distinto cuando la normal es la del plano. El plano se arrastra con el
mismo gizmo del punto 1.

Vale para algo que solo nosotros podemos hacer: **teñir la cara de corte según el
grosor de pared** leído del campo. Ver por dónde la pieza es demasiado fina, en
vivo, mientras mueves el corte. Eso no lo puede hacer un laminador ni un B-rep
sin un análisis aparte.

**Hecho así:**

- `yk_distancia` envuelve `yk_map` con el semiespacio del plano —el corte deja el
  lado positivo de la normal— y es lo que usan la marcha, la normal y la oclusión.
  `yk_map` no se toca: la paridad CPU/GPU sigue comprobando la verdad del material,
  y el plano es estado de vista (como la cámara), no parte del árbol. Verificado:
  22 casos en Metal real, peor desvío 1,53e-05.
- La cara de corte se reconoce por la normal: sobre el plano, el gradiente del
  campo recortado ES la normal del plano, así que no hace falta ninguna topología.
- El tinte lee el grosor de pared marchando hacia dentro del material con la misma
  idea que `Analizador.espesorEn` (bucle de tope constante, salida temprana). Rojo
  por debajo del mínimo del perfil activo, ámbar hasta el doble, verde por encima.
  El umbral lo manda el núcleo (`Editor.grosorMinimoDePared`), no la vista.
- El rectángulo visible del plano se pinta analíticamente (como el plato), con
  rejilla de 10 mm, para que se vea y se pueda agarrar donde no corta material.
- Se activa con la tecla 6, el botón de la barra o el menú Vista, y corta por el
  centro del modelo mirando a la cámara. «Sección por este plano» del clic derecho
  lo coloca sobre el punto señalado. Se arrastra sin modificador sobre el plano,
  con el mismo `avanceDeArrastre` de las caras.

Límite honesto: el tinte mide el material que queda hacia dentro del corte; si el
plano pasa justo por una superficie, la lectura es local y no el espesor total de
la pared — es una vista, el analizador FDM sigue siendo la verdad.

## 6. Interferencias y holguras de encaje

**Ellos.** Shapr3D 2026 estrenó resaltado de interferencias en Section View. En
B-rep detectar solape es una intersección booleana que hay que calcular y medir.

**Nosotros.** Es una lectura, y es la clase de regla que justifica toda la
arquitectura. Se añade a `fabricacion/Analizador.kt` junto a las que ya hay
(grosor, voladizo, detalle, base, esbeltez, volumen):

- **Solape**: existe `p` con `dA(p) < 0` y `dB(p) < 0`. Muestreando la caja común
  se obtiene además el **volumen solapado** en mm³, que es el número que hace
  falta para decidir si es un error o una unión buscada.
- **Holgura**: `min(dA)` sobre la superficie de B. Si sale 0,05 mm y el perfil
  activo pide 0,20, la pieza **no entrará** una vez impresa, y eso hoy solo se
  descubre imprimiendo.
- Cada hallazgo con su corrección ejecutable, como el resto: `[Holgar 0,15 mm]`
  aplica un `Vaciado`/escalado real al documento y entra en el historial.

**Hecho así:**

- `revisarEncuentros` mira pares de piezas que cuelgan de la raíz **sin fusión**
  (con la raíz fundida, todo solape es una unión buscada y denunciarla sería
  ruido). El ancestro común se sube por `padreDe`, y el muestreo tiene tope de
  celdas para que el coste no explote con la pieza.
- El solape reporta el volumen en mm³ con severidad según tamaño (≥ 100 mm³ es
  `fallará`, menos es `probable`): el número es el dato para decidir si era una
  unión buscada.
- La holgura se mide en la banda alrededor de la superficie de B (`|dB| < paso`)
  dentro de la caja expandida por la holgura que pide el perfil. Por debajo de
  `holguraEncaje` (0,2 mm en el perfil P1S) avisa con la medida y el umbral.
- La corrección `[Separar…]` / `[Holgar…]` mueve la pieza con la nueva acción
  `MOVER_PIEZA` (dirección unitaria + distancia), que entra en el historial como
  cualquier edición.

Este punto es el que más se parece a vender la tesis: la regla que a ellos les
cuesta un subsistema, a nosotros nos cuesta muestrear dos campos.

## 7. Historial navegable

**Ellos.** El árbol de historial de Shapr3D y la línea de tiempo de Fusion
permiten volver a la operación 12, cambiarle un número y reproducir las 30
siguientes.

**Nosotros.** Mitad hecho y mitad no, y conviene distinguirlo:

- **Lo que ya tenemos y no está expuesto**: el documento *es* paramétrico. Cambiar
  el radio de un cilindro que taladraste hace veinte pasos ya funciona y
  recompila solo lo que toca. Eso es la mitad valiosa del historial editable, y
  no se ve porque el inspector no lo presenta como historia.
- **Lo que no tenemos**: replay. Las instantáneas de `Documento` dan deshacer
  lineal, y las operaciones que consumen el estado —`acotar` escala el árbol
  entero— no se pueden reordenar.

Propuesta, sin reescribir el núcleo: hacer que las **operaciones de dominio**
(`taladro`, `pared`, `patron`, `acotar`, `perfil`) queden guardadas como lista
—ya viajan así desde la IA y ya se registran en `ia/Bitacora.kt`— y que el
documento pueda reconstruirse ejecutándolas. Con eso se gana editar «los agujeros
eran M3, ponlos M4» sin deshacer treinta pasos. Las primitivas sueltas siguen
siendo estado, no historia; mezclarlo todo cuesta un rediseño y no lo pide nadie.

**Hecho así:**

- `Documento.planesAplicados` guarda cada plan de la IA que se aplicó, tal como lo
  emitió el modelo y con sus alias: la reproducción usa el mismo `Aplicador` que la
  primera vez, así que si la geometría divergiera sería un fallo del núcleo y no de
  la maquinaria.
- `Editor.reproducirPlanes()` ejecuta la historia sobre un documento vacío y
  devuelve el resultado. Probado: el campo reconstruido tiene las mismas cotas que
  el original.
- `Editor.reemplazarPlanEnHistoria(i, nuevo)` reconstruye desde cero con el plan
  nuevo en su sitio y aplica el resultado como **una sola edición** —un solo ⌘Z—.
  «Los agujeros eran M3, ponlos M4» sin deshacer treinta pasos: probado con el
  campo (radio del agujero) antes y después.
- Los planes sobreviven al guardado y a la apertura del `.yunkil` (el campo es
  serializable con compatibilidad hacia atrás).

Límite honesto: esto cubre la mitad *editable* del historial —las operaciones que
vienen de la IA—. El deshacer sigue siendo lineal con instantáneas. Reordenar
primitivas sueltas a mano es el rediseño que el documento ya dice que no pide
nadie.

## 8. La IA como copiloto, no solo como generador

**Ellos.** Fusion `Text-to-Command`: «parte este cuerpo con el plano de
construcción», «pon un chaflán de 0,5 mm en todas las aristas». Es un traductor
de intención a comando **sobre lo que tienes seleccionado**. Zoo genera la pieza
entera, reconoce que no garantiza fabricabilidad, y es el caso difícil.

**Nosotros estamos haciendo el caso difícil y saltándonos el fácil.** Toda la
maquinaria (`ia/Operacion.kt`, `Interprete`, `Aplicador`, `RevisorDeGeometria`)
sirve igual para una orden de tres palabras, y una orden de tres palabras:

- gasta 300 tokens en vez de 8.000, así que el modelo local responde en un
  segundo y no se queda sin contexto razonando;
- acierta mucho más, porque no tiene que inventar coordenadas;
- se usa cincuenta veces por sesión, no una.

Qué falta, concreto: meter la **selección actual** en `ia/Contexto.kt` (hoy
manda cotas y nombres de todo el documento, sin decir qué está señalado), un
campo de texto permanente en la barra en vez de la tarjeta modal «Crea
describiendo», y un tope de tres operaciones por propuesta. El diff previo y el
punto único de deshacer ya están.

**Hecho así:**

- El contexto ahora dice quién está señalado **y cuánto ocupa** —`Seleccionada: #id
  «Base» · x[..] y[..] z[..] · máx 3 ops`—, que es lo que un modelo necesita para
  razonar sobre «este canto» sin re-leer el árbol.
- El intérprete distingue **edición de generación** (`Interprete.interpretar(edicion)`):
  con selección, un plan que intenta `crear` se rechaza, y más de 3 operaciones
  también, con el motivo exacto. La app decide el modo mirando si hay selección y
  si la petición no empieza por «crea/haz/hazme…» (`esPeticionDeCrear`).
- El prompt explica el modo edición en dos líneas; el test de presupuesto
  (14.000 caracteres, el límite que ya había) sigue pasando —se recortaron las
  frases que el bloque nuevo duplicaba, como la vez anterior—.
- El campo permanente ya existía (`creadorVibe` sobre el viewport), así que ese
  tercio del punto estaba hecho.

El resultado: una orden de tres palabras gasta 300 tokens, responde en un segundo
y se usa cincuenta veces por sesión — el caso fácil que estábamos saltándonos
para hacer el difícil.

## 9. Rendimiento

Tres cosas distintas, en orden de riesgo:

1. **Tiles en iPad, que es una caída real, no una lentitud.** Si un command
   buffer se pasa de tiempo, el vigilante de la GPU mata la app. Partir el
   drawable en regiones y encolar un buffer por región. Ya está anotado en
   PROXIMOS-PASOS; es el bloqueo de la app de iPad y no debe empezar el proyecto
   iPad sin esto resuelto.
2. **Salto por cotas de nodo.** El shader hace hoy sphere tracing evaluando el
   árbol completo en cada paso: con veinte piezas se paga veinte veces un
   espacio vacío. `MslGenerator` puede emitir, antes de cada rama, un descarte
   contra la `Aabb` del nodo —que el kernel ya calcula y ya prueba que es
   conservadora— y devolver la distancia a la caja. Es el algoritmo que permite
   subir el número de piezas sin bajar la resolución, y encaja en la regla de que
   todo bucle lleva tope constante porque no añade bucles.

   **Hecho así:** el generador emite ahora dos cuerpos. `yk_map` sigue siendo la
   verdad exacta —la que comprueba la paridad CPU/GPU— y `yk_marcha` añade, en
   cada unión, diferencia y acuerdo local, un descarte contra la caja del segundo
   hijo: `if (cajaB > a + k) d = a else …`. La poda es **exacta** (si la caja está
   más lejos que `a` más la mezcla, la rama no puede ganar), así que el valor del
   campo no cambia y el trazado no puede pasarse de largo: solo se ahorran
   evaluaciones. Las cajas viajan al final del buffer de uniforms (6 floats por
   nodo, en preorden, sin tocar el orden que ya comprueba la paridad), y el arnés
   de paridad ahora verifica además que `yk_marcha ≤ yk_map` en los 22 casos
   contra Metal real: si la poda exagerara, el test lo cazaría. La marcha usa la
   podada; la normal, la oclusión y el tinte de la sección siguen con la exacta.
3. **Textura 3D para el campo horneado.** Hoy una pieza `MALLA` se pinta como su
   envolvente: el CPU es exacto pero la vista miente. `Renderizador` gana un
   `MTLTexture` de tipo 3D y el generador emite un `sample()` para ese nodo. Es
   tocar el enlace de recursos, que es lo que lo ha aplazado.

Y la trampa ya aprendida, que queda escrita para que no se repita: la condición
para **subir** resolución es «llegamos a tiempo», nunca «vamos sobrados». Con
vsync, «sobrados» es inalcanzable y la app se queda al 40 % para siempre.

## 10. El STL de fuera, devuelto imprimible

**Ellos.** MakerLab (Bambu) con Meshy 6 hace imagen → modelo en dos minutos,
watertight, multicolor asignado solo, y presume de 97 % de pase en laminador. Es
gratis y está dentro del ecosistema donde vive el usuario. Ahí no se compite: ya
está decidido que no reconstruimos malla desde imagen, y es la decisión correcta.

**Pero eso deja un hueco enorme justo detrás.** Lo que sale de esas
herramientas —y los millones de STL de MakerWorld y Printables— es geometría sin
cotas, con paredes de 0,6 mm, voladizos de 70° y agujeros que la impresora
cerrará. El propio ecosistema admite que «hay que revisar escala, grosor,
soportes y color antes de imprimir». Nadie hace esa revisión con verdad de campo,
y nosotros ya tenemos las tres piezas: `LectorStl`, `CampoDeMalla` y el
analizador.

Falta convertirlo en **producto de una sola acción**, y eso es diseño de flujo,
no algoritmo nuevo:

```
arrastras un STL sobre la app
  → se hornea a campo y se malla
  → informe FDM con la pieza nombrada en cada aviso
  → arreglos ejecutables: engrosar pared, achaflanar, reorientar, holgar agujeros
  → 3MF con la orientación óptima aplicada
  → «abrir en OrcaSlicer»
```

Es lo más corto que hay entre lo que ya está construido y algo que alguien
enseña a otro. Recomendación firme: **este es el punto que convierte la beta
técnica en algo demostrable**, por delante del generador conversacional, porque
no depende de que un modelo acierte.

**Hecho así:** el hueco era el cable entre piezas que ya existían. La vista
acepta STL arrastrados (`draggingEntered`/`performDragOperation`), y el flujo
`importarMallaDesde` hace importar → encuadrar → **analizar al momento** en la
misma pasada. El analizador ya nombra la pieza en cada aviso y cada hallazgo
trae su corrección ejecutable —incluidas las nuevas de interferencia y holgura
del punto 6—, y el exportador ya se niega a entregar una malla que no pasa el
examen. Falta el 3MF orientado y el puente a OrcaSlicer, que están en los
próximos pasos: el bucle demostrable está cerrado sin depender de un modelo.

---

## Lo que no copiamos, y por qué

- **Laminador propio.** Subsistema mayor que los otros cuatro juntos.
- **Reconstrucción de malla desde imagen.** Sale un blob sin cotas que no
  podríamos ni verificar ni editar; el foso se cae y un navegador lo hace gratis.
- **Solucionador de restricciones geométricas.** Se acota con números y se pega
  con imanes. Un solver es tan grande como el resto del programa.
- **Render fotorrealista.** El sombreado existe para leer curvatura y cotas.
- **Nube y colaboración.** El argumento es lo contrario: local y privado.

## Orden recomendado

1. Punto 1 (id en `yk_map` + picking), porque desbloquea 2, 3, 5 y 6.
2. Punto 10 (flujo del STL de fuera), porque es lo único demostrable hoy sin
   depender de un modelo.
3. Punto 4 (clic derecho y atajos), porque es una tarde y cambia la sensación.
4. Punto 6 (interferencias y holguras), porque es la regla que vende la tesis.
5. Punto 9.1 (tiles) antes de tocar el proyecto iPad, no después.

## Fuentes

- [Shapr3D — changelog](https://support.shapr3d.com/hc/en-us/articles/7536511116188-Changelog)
- [Autodesk Assistant en Fusion](https://www.autodesk.com/products/fusion-360/blog/a-guide-to-autodesk-assistant-in-fusion/)
- [Fusion — hoja de ruta 2026](https://www.autodesk.com/products/fusion-360/blog/fusion-roadmap-2026/)
- [Plasticity vs Shapr3D](https://sourceforge.net/software/compare/Plasticity-vs-Shapr3D/)
- [Meshy y MakerWorld](https://3dprintingindustry.com/news/meshy-and-makerworld-team-up-to-put-ai-3d-model-generation-in-bambu-lab-users-hands-250281/)
- [Text-to-3D en MakerWorld vía Meshy](https://filamentfeed.com/article/meshy-ai-text-to-3d-makerworld-june-2026)
- [nTop — plataforma](https://www.ntop.com/platform/)
- [Zoo — text-to-CAD](https://zoo.dev/text-to-cad)
