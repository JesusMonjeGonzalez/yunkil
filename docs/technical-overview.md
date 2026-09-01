# Yunkil

Este README es la única fuente de verdad para la visión, el estado, la arquitectura,
las prioridades y las reglas de desarrollo de Yunkil. Si el código cambia, este archivo
se actualiza en la misma entrega. No se mantienen roadmaps, estados fechados ni planes
paralelos.

## Visión

Yunkil es un modelador de sólidos para impresión 3D en macOS. Su representación principal
no es una malla, sino un árbol paramétrico de funciones de distancia con signo (SDF)
compartido por edición, render, selección, análisis y exportación.

El producto busca unir tres capacidades que normalmente están separadas:

1. Describir, enseñar o editar una pieza mediante IA sin ejecutar código generado.
2. Entregar geometría editable y verificablemente imprimible, no una malla opaca sin cotas.
2. Sostener el **encaje con un objeto real** como una relación del documento, no como una
   cota que alguien calculó una vez y nadie puede volver a justificar.

La tercera es la que distingue a Yunkil de un laminador. Bambu Studio y OrcaSlicer laminan,
colocan en el plato y mandan a la máquina; lo que no hacen —ni pueden, porque reciben una
malla— es saber para qué existe una cota. Un STL no sabe que sus 19,6 mm son 20 medidos
menos la holgura de una boquilla concreta, así que cambiar de material obliga a rehacer la
pieza en la herramienta donde se modeló.

El núcleo es Kotlin Multiplatform. La aplicación macOS usa SwiftUI y Metal. Existen targets
Kotlin para iOS, pero no hay todavía una aplicación iPad terminada.

## Estado actual

### Modelado técnico

- Primitivas: esfera, caja, cilindro, cono, toro y cápsula.
- Perfiles 2D predefinidos y libres para extrusión, revolución y barrido.
- Editor visual básico de perfiles con rejilla, arrastre de vértices y ajuste ortogonal.
- Unión, diferencia e intersección exactas o suavizadas.
- Filete y chaflán local señalando un punto de la superficie.
- Vaciado, desfase positivo/negativo, simetría y repeticiones lineales y circulares.
- Patrón circular en X/Y/Z, hasta 64 copias y ángulo total editable.
- Taladros, paredes, nervios, encajes, patrones normalizados y perfiles de fabricación.
- Perfiles de fabricación propios: calibrados con el cupón o editados umbral a umbral, y
  guardados aparte del documento porque son de la máquina y no de la pieza. **Con
  versiones**: reemplazar un perfil propio archiva el anterior y `CatalogoDePerfiles.restaurar`
  vuelve a cualquiera de sus versiones —restaurar también archiva, porque restaurar es
  editar—. El historial persiste junto al catálogo.
- Selección por raymarching CPU, manipulación directa de caras, sección y orientación.
- Gizmo de mover, girar y escalar sobre el centro de la pieza, con resalte al pasar el
  ratón y ajuste de 1 mm y 15° manteniendo ⇧.
- Historial transaccional con deshacer y rehacer.

### Piezas y variantes

- **Cable paramétrico**: un tubo de radio variable sobre una polilínea 3D —el mismo
  nodo que las curvas orgánicas— como pieza del documento. Se declara con `Editor.anadirCable`
  y se reescribe con `fijarCable`; se compila, se acota, se taladra y se exporta con
  certificado como cualquier otra pieza. Sus puntos y radios persisten en el `.yunkil`,
  con su validación al abrir.
- **Variantes paramétricas**: `Editor.generarVariantes` clona una pieza una vez por
  valor del parámetro —el tapón para 19, 20 y 21 mm—, las nombra con su valor, las
  coloca en fila sin que se toquen y lo deja todo en un solo punto de deshacer. El
  encaje no viaja a las copias a propósito: las variantes existen para distinguir
  justamente lo que un encaje compartido aplanaría.
- **Plantillas**: guardar una pieza —o un subárbol— en una biblioteca local
  (`BibliotecaDePlantillas`) e insertarla después con identificadores frescos, en una
  transacción deshacible. Con los encajes viajan sus medidas del mundo; una malla
  importada no se deja guardar porque su campo no viaja.

### Encajes con el mundo real

El documento guarda **medidas del objeto real** —nombre, valor y procedencia: con calibre,
con regla, de catálogo, de norma o a ojo— y las piezas se atan a ellas mediante un
**encaje**: contra qué medida encajan, en qué sentido (`ENTRA` o `RECIBE`), sobre qué eje y
con qué clase de ajuste (`PRESION`, `AJUSTADO`, `DESLIZANTE` o `LIBRE`, que son factores 0,
0,5, 1 y 2 sobre la holgura tabulada del perfil).

La cota **se deriva** de ahí. No es un número que alguien escribió:

- Corregir la medida mueve todas las piezas que dependen de ella. Las medidas viven en el
  documento y no en la pieza porque el tapón y la abrazadera del mismo tubo comparten
  diámetro; con una copia por pieza, corregir sería acordarse de corregir en varios sitios.
- Cambiar de perfil de fabricación vuelve a derivar todas las cotas gobernadas. Pasar de
  0,4 mm de boquilla a 0,6 mueve las piezas solas.
- La cota gobernada **no se edita a mano**. El inspector la enseña con su procedencia
  —«entra en agujero del tubo: 20 mm con calibre · holgura 0,2 mm deslizante»— y ofrece
  `Soltar encaje` como salida explícita. Qué parámetros están gobernados se resuelve
  midiendo, no con una tabla por tipo de pieza: se ensaya el cambio y se mira si la
  extensión en el eje gobernado se mueve.
- La operación `holgura` del DSL declara la relación en vez de redimensionar una vez, y da
  de alta la medida con su nombre en el mismo cambio, de modo que deshacer devuelve las dos
  cosas y nunca queda una medida huérfana.

Y se **comprueba sobre la geometría final**, no sobre el plan que la declaró. Entre la
declaración y el STL hay booleanas, vaciados y brochas que pueden habérsela comido:

- `ENTRA` pregunta cuánto ocupa. Se mide la extensión del material en el eje gobernado, en
  la zona de la pieza y un poco más allá, para que una pestaña fusionada después —que
  sobresale y ya no entra— se vea. El borde se afina por bisección contra el campo.
- `RECIBE` pregunta cuánto cabe, que no es lo contrario: un pasador en el centro de un
  agujero deja los bordes despejados. Se mide la mayor bola que entra, que en un campo de
  distancias es el máximo del propio campo dentro del hueco, acotado por el volumen que la
  pieza reclama.

Contra geometría conocida el error es de una centésima de milímetro incluso muestreando a
0,6 mm. Cada encaje va al informe de fabricación **cumpla o no**, con lo declarado y lo
medido, y **falla cerrado**: si no se puede medir, se dice y no se aprueba.

Lo que esto promete se puede demostrar sin tener la impresora delante: no que la pieza
entre —eso depende además de la máquina—, sino que **la geometría exportada tiene la
holgura declarada contra la medida dada**.

### Cupón de calibración

Para pasar de ahí a «entra en tu máquina» hace falta medir la máquina, y para eso está el
cupón: una placa con ocho agujeros pasantes a holguras escalonadas alrededor de la que
tabula el perfil, más **un solo pasador** de 8 mm. Se imprime, se prueba el pasador agujero
por agujero, y el primero en el que entra da la holgura real. Con un pasador único todo lo
que varía está en la placa; una fila de pasadores distintos mediría a la vez cuánto engorda
el agujero y cuánto engorda el pasador, y no habría forma de despejar ninguna de las dos.
Las estaciones se cuentan desde una ranura en el extremo apretado, porque el texto
paramétrico no existe todavía y un número de 3 mm impreso en FDM se lee peor que una marca.

`PerfilFabricacion.calibradoCon` toca **una sola cosa**, la holgura, porque el cupón mide
una sola cosa; y marca el perfil como `CALIBRADO`, que hasta ahora era un estado que nada
podía producir.

### Holgura proporcional al diámetro

La holgura del cupón es un número fijo por máquina, y como tal es la buena para cotas de
un par de centímetros. En un agujero de 120 mm se queda corta: el error de la máquina
crece con el tamaño, y las guías de ajuste FDM expresan la holgura grande como fracción
del diámetro. Un encaje puede activar `holguraProporcional` —`Editor.fijarHolguraProporcional`—
y su cota pasa a derivarse del **mayor** entre el piso de la máquina y la fracción del
diámetro que pide su clase (`Estandares.fraccionDeAjuste`: 0 % presión, 0,25 % ajustado,
0,5 % deslizante, 1 % libre, por lado). Nunca aprieta: lo que corrige es el caso en que
el número fijo no llega. La verificación de encajes y el informe usan la misma holgura
efectiva, para que «cumple» no salga falso por defecto en los diámetros grandes.

### Dos cotas independientes: encajes por parámetro

Una pieza ya lleva **una lista de encajes**, no uno. La derivación de siempre escala
uniformemente —coherente con el resto del sistema, pero atar el diámetro de un cilindro
le movía la altura—. Con `Encaje.porParametro`, la cota se deriva moviendo **el parámetro
responsable del eje** (`Pieza.parametroQueGobierna`: el `radio`, la `anchura`, la
`altura`…), y ningún otro número de la pieza se mueve. El tapón que debe encajar por
diámetro y por altura lleva dos encajes por parámetro, cada uno con su medida. Donde no
hay un parámetro responsable claro —un cono, un toro, una escultura— no se deja
declarar, y en una pieza no se mezclan modos: o todos por parámetro o todos por escala,
porque una escala uniforme pisaría al que gobierna por parámetro. El esquema del
documento sube a la **versión 2** con migración explícita del `encaje` singular.

### La procedencia de una medida se corrige después de medir

Las medidas que da de alta la IA nacen `a ojo`, y así deben nacer: el modelo se las han
dicho, no las ha medido. `Editor.fijarProcedencia` deja subirlas a `con calibre` en cuanto
quien midió lo dice, y `medidasSinUso` señala las que ya no justifican ningún encaje. El
informe exportable avisa de todo encaje verificado contra una medida a ojo.

El cupón se exporta apto a la resolución que sugiere la aplicación y a 0,6 / 0,5 / 0,4 /
0,3 mm, y hay una prueba que lo fija. Estuvo bloqueado hasta que se arregló la diagonal del
contorneado: su escalón —el vástago sobre el pie— hacía que la malla se cruzara consigo
misma y el certificado se negaba a escribir el archivo.

El perfil calibrado **se guarda**, en `~/Library/Application Support/Yunkil/perfiles.json`
y aparte del documento: la calibración es de la máquina, no de la pieza, y viajar dentro de
un `.yunkil` haría que abrir el archivo de otro te cambiara la impresora. `CatalogoDePerfiles`
los suma a los de fábrica, y `PerfilFabricacion.porNombre` pasa por él: el nombre del perfil
viaja como texto —el informe se pide con él, y el editor aislado que analiza en otro hilo lo
resuelve por su cuenta—, así que mientras esa búsqueda solo miraba la lista de fábrica,
calibrar y pedir el examen devolvía en silencio el análisis del perfil de partida. Un perfil
calibrado recuerda de cuál salió, para poder decirlo y para saber a dónde volver si se borra.

Lo que falta para cerrar la cadena ya no es código: es imprimir cupones en máquinas de
verdad y comprobar que la holgura que sale encaja.

### Motor orgánico nativo

- Contrato cerrado `yunkil.organico.v1` producido por la IA.
- Anatomía semántica mediante cuerpo, cabeza, extremidades, orejas, cola, ojos y detalles.
- Esferas, cápsulas y troncos orientados fusionados como SDF.
- Curvas 3D semánticas: una cola, una trompa, un cuerno curvado, un mechón o un cable son
  una sola parte con hasta 12 puntos de control y un grosor por punto.
- La escultura se persiste dentro de `.yunkil`; no se degrada a STL al aceptarla.
- Brochas continuas para añadir, inflar y quitar volumen.
- Brochas de campo: alisado local, pellizco y arrastre de volumen.
- Máscaras persistentes que limitan cualquier brocha posterior.
- Plano de simetría configurable por eje o desde la cara señalada.
- Radio y fuerza de brocha configurables.
- Suavidad global editable sin recompilar la topología.
- Un trazo completo es una sola operación de deshacer.
- Una escultura seleccionada puede enviarse otra vez a la IA para modificar su contrato
  completo conservando identificadores semánticos.
- **Edición semántica de partes**: `moverParteOrganica`, `engordarParteOrganica` y
  `quitarParteOrganica` editan una parte por su identificador —«la cola», «la oreja»—
  sin reescribir el contrato ni volver a pedirle nada a la IA. El cambio pasa por el
  mismo validador del contrato: si la parte deja de tocar a su padre, se rechaza con su
  motivo. Al quitar una parte, las que colgaban de ella se reatajan a su soporte; el
  cuerpo no se quita.
- **Selección orgánica por punto**: `parteOrganicaBajo` dice qué parte hay bajo un punto
  del mundo —la respuesta de un clic que contesta «la cola» en vez de «la escultura»—.
- La propuesta orgánica se ve como fantasma en el viewport antes de aceptarla, se corrige
  en rondas cuando el contrato no valida, se rechaza si el documento cambió desde que la
  IA lo vio y su desenlace va a la bitácora como cualquier propuesta paramétrica.
- Render, análisis y exportación usan el mismo campo que el modelado técnico.

#### Curvas como una sola parte

Una cola curvada se escribía encadenando cápsulas rectas. Funcionaba, pero el presupuesto
de partes de la IA se iba en la cola, el grosor solo podía cambiar de tramo a tramo —el
afilado quedaba escalonado— y bastaba que un extremo se despistara un milímetro para
partir la cola en dos.

`Cordon` es un tubo de radio variable que recorre una polilínea 3D. Su campo es el mínimo
de un cono redondeado por tramo: cada tramo es una distancia exacta al casco convexo de
dos bolas, así que los codos empalman redondeados por construcción, el nodo sigue siendo
1-Lipschitz y el trazador salta a paso completo sin coser nada.

La curva llega al nodo ya muestreada. El motor orgánico interpola los puntos de control
con Catmull-Rom, que **pasa por ellos** en vez de quedarse por dentro como una Bézier: si
el modelo dice que la punta está en (30, 40, 0), la punta está ahí. El grosor interpola
lineal y no por spline, porque una spline sobre los radios sobrepasaría en los giros y
podría cruzar el cero, y un radio negativo no es una punta afilada sino un campo del
revés. Con dos puntos de control no se suaviza nada: una recta suavizada sigue siendo la
misma recta y gastar nueve vértices en decirlo solo encarece el bucle del shader.

#### Brochas que deforman el campo

Añadir y quitar volumen son booleanas: la esfera de la brocha entra en el árbol como una
parte más. Alisar, pellizcar y arrastrar no lo son y no se simulan con la misma operación
bajo otra etiqueta; cada una es un nodo SDF propio con evaluación CPU, cotas, escalares,
emisión MSL y casos en el arnés de paridad.

- `AlisadoLocal` sustituye el campo por su media en un entorno, mezclada según el peso de
  la zona. Promediar un campo de distancia es un flujo por curvatura media: lima los
  bultos y rellena los surcos. Fuera de sus bolas devuelve el campo del hijo bit a bit.
- `PellizcoLocal` aprieta el espacio contra el eje de la normal señalada, así que el
  material converge hacia ese eje y la cresta se estrecha. Con fuerza negativa ensancha.
- `MoverLocal` arrastra el espacio dentro de la bola, de modo que el material se estira sin
  partirse: el mapa es continuo e inyectivo por construcción.

Las tres dejan de ser 1-Lipschitz y **lo declaran**. Cada nodo publica una cota de
gradiente derivada y comprobada numéricamente, el generador la compone por el camino del
árbol y de ahí sale el paso seguro que aplican el raymarcher de Metal y el picking en CPU.
El motor orgánico limita el gradiente acumulado de la cadena y recorta la fuerza de un
brochazo antes de pasarse; si no cabe, lo dice en vez de dejar un campo intrazable.

Los sitios de brocha van en listas dentro del nodo y no un nodo por muestra: una trazada
continua funde sus muestras en la zona anterior, así que no recompila el shader en cada
fotograma ni multiplica el coste del alisado.

Las máscaras son un factor que llevan las tres brochas, no una capa aparte: donde la
máscara vale 1 el peso es cero y el material se queda porque nadie lo toca. Se guardan en
`.yunkil` y una brocha lanzada sobre una zona protegida se rechaza con su motivo.

El motor orgánico genera figuras estilizadas e imprimibles. No ofrece todavía escultura
artística equivalente a Blender o ZBrush: las curvas existen pero no se agarran con el
ratón, y faltan selección directa de partes, detalle superficial fino, retopología y un
modelo neuronal propio.

### IA paramétrica y multimodal

- Proveedor local mediante Hearthia y proveedor remoto mediante OpenCode Go.
- La IA emite JSON declarativo contra un DSL cerrado; nunca ejecuta Kotlin, Swift, Python
  ni comandos del sistema.
- El catálogo del prompt se genera desde los mismos tipos y parámetros que valida el núcleo.
- `NECESITA_DATOS` permite pedir escala o cotas sin inventar geometría.
- Las imágenes se reducen a 1024 px y solo se envían a modelos con capacidad visual
  declarada o comprobada.
- El crítico visual compara la referencia original con frente, lado, planta e isométrica.
- La propuesta dice si la pieza **se ha llegado a mirar**. El crítico falla abierto a
  propósito, pero ya no falla callado: «la he mirado y es la pieza», «protestó sin decir
  qué» y «no había visor» eran antes la misma lista vacía.
  Si no hay visor, red o respuesta legible, deja pasar la pieza a propósito: es un revisor
  *de más* y uno que se cae no puede tumbar un plan que los revisores exactos aprobaron.
  Hoy eso no se distingue de haberla mirado y aprobado.
- La referencia acompaña todas las rondas de corrección, no solo la primera.
- El modo `Pieza técnica` genera operaciones paramétricas; `Figura orgánica` genera una
  escultura semántica nativa.

Los dos modos **no** tienen las mismas garantías, y conviene no confundirlos. Lo que ya
comparten: el contrato orgánico se interpreta y se corrige en rondas si no valida, la
propuesta se ve como fantasma en el viewport antes de aceptarla, la aceptación está ligada
a la revisión del documento que vio la IA —cambiar algo a mano entre la propuesta y el
aceptar la rechaza con su motivo, no pisa trabajo nuevo— y el desenlace va a la bitácora
igual que el paramétrico, con deshacer inmediato contado como `DESHECHO`.

Lo que sigue siendo solo del paramétrico: la revisión geométrica medida —el revisor
aplica el plan en aislamiento y le reprocha al modelo lo que sale mal medido, con rondas
de corrección geométrica—, el crítico visual, la explicación operación a operación con
aceptación parcial y el registro en `propuestas.jsonl` de las operaciones desmarcadas. El
orgánico valida el contrato y lo ofrece entero: se acepta o se descarta, sin casillas.
Igualar lo que falta es la prioridad 1.

Una foto no tiene escala. Para piezas funcionales debe proporcionarse al menos una medida
real. Si falta, Yunkil debe preguntar antes de modelar. Para figuras orgánicas puede usarse
una altura objetivo y conservar proporciones visuales.

### Fabricación y exportación

- Análisis FDM de pared mínima, detalle, apoyo, voladizo, esbeltez e interferencias.
- Cuatro perfiles de partida: Bambu P1S · PLA, Bambu A1 · PETG, Prusa MK4 · PLA y una
  genérica de 0,6 para pieza funcional. No hay perfiles de ABS ni de resina.
- Cada aviso identifica la pieza y puede incluir una corrección ejecutable.
- El análisis pasa la malla por el **mismo** examen que la exportación: si el informe
  declara la pieza apta, el certificado la aprueba a esa resolución.
- Búsqueda de orientación y asentado sobre el plato.
- Contorneado dual con aristas más fieles que marching cubes.
- Certificado de cierre, orientación, degenerados, auto-intersecciones, desviación y volumen.
- Exportación STL binaria y 3MF con unidades y orientación declaradas.
- **Placa 3MF multiobjeto**: `Editor.exportarPlacaTresMf` junta varios cuerpos —una pieza
  y su cupón, las variantes de un encaje, un ensamblaje— en un 3MF con un objeto con
  nombre por pieza, colocados en fila sobre el plato. Cada cuerpo pasa **su propio**
  certificado; si uno no es apto no se escribe nada y se nombra la pieza.
- **Ensamblajes e interferencias**: `Editor.declararEnsamblaje` marca qué cuerpos deben
  ir separados y montarse después —la única situación en la que un solape es un problema
  y no técnica de modelado—, y `VerificadorDeEnsamblajes` mide el mm³ de material común
  por par, o la holgura mínima cuando no se tocan, con la muestra descortada a favor de
  decir menos. Lo que no se puede medir se dice y no se aprueba.
  `distanciaEntre` mide el mismo número entre dos piezas cualesquiera, sin declarar nada;
  `separarEnsamblaje` coloca los cuerpos del ensamblaje sobre el plato con holgura de
  montaje, y `empaquetarEnPlaca` es la operación general: filas sobre la base, dentro del
  volumen de impresión, en una sola transacción que o cabe entera o no se toca nada.
- **Advertencias rápidas de edición**: `advertenciasRapidas` responde en milisegundos
  con lo que se sabe sin muestrear el campo —parámetros por debajo del detalle mínimo de
  la boquilla, piezas que no caben en el plato—. No sustituye al análisis; lo precede.
- **Comparador de perfiles**: `compararPerfiles` corre el análisis de la misma pieza bajo
  varios perfiles y lo resume en apto, puntuación y avisos con más peso. Cuesta un
  análisis por fila, y se dice.
- **Hitos de deshacer**: `marcarHito` guarda el documento con nombre —«antes de los
  agujeros»— y `deshacerHasta` vuelve a él por el historial en los dos sentidos; si el
  camino se perdió editando por debajo, se dice y no se finge.
- **Estimación de impresión**: peso, metros de filamento de 1,75 mm y coste por pieza,
  desde el volumen del certificado y la densidad y el precio por kg del perfil —de tabla
  cuando el perfil no los lleva, y dicho—. Sin relleno ni soportes, y se dice.
- **Informe de fabricación exportable**: `Editor.exportarInformeMarkdown` escribe el
  informe —métricas, hallazgos, encajes medidos cumpla o no, estimación, ensamblajes y
  avisos de procedencia— en Markdown, para el taller y el correo, con los mismos números
  que deciden el certificado.
- Importación STL ASCII/binaria, soldadura de vértices y horneado a campo de distancia.
- Una malla no se escribe si no supera el certificado. En la placa, tampoco.

## Arquitectura

```text
core/src/commonMain/kotlin/yunkil/
├── kernel/       SDF, deformaciones de escultura, matemáticas, perfiles y campos
├── doc/          documento, Editor, historial, picking, manipulación y encajes
├── msl/          generación del raymarcher Metal y uniforms
├── fabricacion/  perfiles, análisis FDM, orientación y verificación de encajes
├── malla/        contorneado, certificado, STL y 3MF
├── ia/           DSL, intérprete, aplicación, revisión y conversación
├── imagen/       vistas del campo y PNG
└── organico/     contrato semántico, compilación SDF y brochas

apps/mac/Sources/
├── App.swift
├── Renderizador.swift
├── Camara.swift
├── Gizmo.swift
├── Gobernador.swift
├── AIService.swift
├── PanelDePropuesta.swift
├── CriticaVisual.swift
└── EditorVisualDePerfil.swift

core/src/macosArm64Main/  `yunkil`, la orden de terminal: el mismo núcleo sin ventana

tools/
├── paridad/      arnés CPU ↔ Metal
├── fantasma/     arnés de la previsualización
├── rayo/         arnés del rayo de cámara
├── gizmo/        arnés del gizmo: proyectar, agarrar, mover y girar
└── estado/       arnés de la aplicación: conduce `EstadoDeLaApp` sin abrir ventana
```

### Invariantes

- `SdfNode.evaluar` es la referencia geométrica.
- `SdfNode.cotas` puede sobrar, pero nunca dejar material fuera.
- CPU y MSL deben evaluar la misma forma.
- Los números viajan como uniforms; cambiar una cota no recompila el shader.
- Cambiar topología sí regenera la huella y recompila Metal.
- Todo bucle emitido en MSL tiene un límite constante.
- Cada cara del contorneado se parte por su diagonal más corta. Depende solo de la
  geometría de la cara, así que dos celdas vecinas eligen igual y sus mitades no se pliegan
  una contra otra.
- Una arista del perfil que cae sobre el eje de revolución no cuenta para la distancia: al
  girar se colapsa en el eje y no barre superficie. Sin la excepción, cualquier pieza maciza
  de torno medía cero en su propio eje y el mallador ponía allí astillas.
- Un nodo que no sea 1-Lipschitz publica su cota de gradiente, y el paso de trazado sale
  de ella tanto en Metal como en el picking en CPU.
- La escala es uniforme: una escala no uniforme invalidaría las distancias y el analizador.
- Una cota gobernada por un encaje se deriva de su medida y del perfil vigente, y se vuelve
  a derivar después de **cada** cambio del documento, no solo donde se declara.
- Un encaje declarado se mide sobre la geometría final y el resultado va al informe aunque
  no dispare ningún aviso. Un encaje que no se puede medir no se aprueba.
- Toda mutación pasa por `Editor`, valida antes de aplicar y es deshacible. Un cambio que
  produjera un árbol que no se puede construir se rechaza con su motivo y deja el
  documento exactamente como estaba; nunca se guarda un documento que no compila.
- Si el informe de fabricación declara una pieza apta, el certificado de exportación la
  aprueba a esa misma resolución. El informe no puede ser más optimista que el examen que
  decide si se escribe el archivo.
- Todo plan de IA se ejecuta primero sobre un editor aislado.
- Todo archivo exportado pasa por el mismo certificado.

### Documento y persistencia

`Documento` y `Pieza` son estructuras inmutables distintas del árbol SDF. Una pieza conserva
nombre, tipo, parámetros, transformación, hijos y visibilidad; `Pieza.compilar()` produce el
campo que consumen render, picking, análisis y exportación.

`.yunkil` es JSON mediante `kotlinx.serialization`. Guarda el árbol paramétrico, selección,
conversación, medidas del mundo, encajes y contratos orgánicos, incluidas las zonas de alisado, pellizco, arrastre y
máscara y el plano de simetría de cada escultura. Los campos de mallas importadas se reconstruyen desde
su archivo de origen al abrir. El esquema actual es la versión 1; los documentos históricos
sin versión migran explícitamente a ella y las versiones futuras se rechazan sin tocar el
documento abierto.

`Editor.versionDocumento` es una revisión monotónica en memoria para invalidar propuestas
de IA obsoletas; no es una versión del formato de archivo.

Abrir un archivo no es solo leerlo. Que el JSON encaje en las clases no lo convierte en un
documento, así que la misma frontera comprueba coherencia antes de entregarlo: que no haya
identificadores repetidos ni vacíos, que ningún número sea NaN o infinito, que las escalas
sean positivas, que una MALLA diga de qué archivo salió —el campo horneado no viaja en el
`.yunkil`—, que una ESCULTURA lleve un contrato que se pueda construir, que un contorno
libre tenga puntos suficientes, que ninguna medida esté repetida o valga cero, que ningún
encaje apunte a una medida que no está en el archivo —callarse ahí dejaría una pieza que
dice encajar con algo que nadie sabe cuánto mide— y que el árbol entero compile. Lo que no se puede arreglar
sin cambiar la pieza se rechaza nombrando el motivo, y el documento abierto no se toca. Lo
que sí —una selección que apunta a una pieza que ya no está— se sanea al vuelo, porque
negarse a abrir el archivo por eso sería castigar a quien no ha hecho nada.

Al abrir se reservan además los identificadores del archivo. El contador arranca en cero
con el proceso, y sin esa reserva un proyecto de otra sesión producía una pieza nueva con
un identificador que ya estaba dentro del árbol: a partir de ahí `buscar` y `mapear`
acertaban a la primera que encontraran, sin dar error en ningún sitio.

## Uso

- Clic: selecciona una superficie.
- Arrastrar: orbita.
- Opción + arrastrar: desplaza la cámara.
- Comando + arrastrar una cara: cambia su medida.
- Clic derecho: operaciones contextuales, filete, apoyar, aislar y agrupar.
- Teclas 1/3/7/9: vistas ortográficas; 5 alterna proyección paralela.
- En una escultura, elegir `Añadir` o `Quitar` y arrastrar sobre la superficie aplica brocha.
- `Alisar`, `Pellizcar` y `Proteger` se sellan arrastrando; `Mover` se agarra y se tira.
- El plano de simetría se elige por eje o desde la última cara señalada.
- `⌘Z` deshace la última edición o el trazo orgánico completo.
- Una cota con candado la manda un encaje: se cambia editando su medida, o se libera con
  `Soltar encaje`.

Un parámetro puede declarar un hueco prohibido alrededor del cero, y su deslizador lo
salta hacia el lado al que iba en vez de pararse dentro. Hoy lo usa el ángulo total de una
repetición circular, donde cero no significa nada. La alternativa —rechazar el valor—
atascaría el deslizador a mitad de arrastre, que se siente peor que saltar.

## Compilar y comprobar

Requisitos: macOS 14+, Apple Silicon, JDK 21, Xcode/Command Line Tools y Metal.

```bash
./scripts/instalar.sh                 # construye e instala en /Applications
./scripts/construir-mac.sh debug      # genera build/Yunkil.app
./scripts/construir-cli.sh instalar   # `yunkil` en ~/.local/bin
./scripts/comprobar.sh                # núcleo, CPU/Metal, fantasma, rayo, gizmo, app y orden
./scripts/comprobar.sh nucleo         # pruebas rápidas del núcleo
./gradlew :core:jvmTest               # pruebas Kotlin/JVM
./gradlew :core:banco                 # banco IA local; informativo, no bloquea
./gradlew :core:bancoDeHilo           # memoria conversacional
./gradlew :core:pruebaDeVista         # crítico visual
./gradlew :core:verMalla              # exportar, reimportar y comparar una malla
```

La cifra vigente de pruebas la imprime `./scripts/comprobar.sh`; no se congela aquí porque
cambia con cada entrega.

## IA, privacidad y operación

### Proveedores

- Local: Hearthia en `http://127.0.0.1:9292`; las peticiones no salen del Mac.
- OpenCode Go: usa la cuenta conectada y consume su cuota.

La app descubre modelos locales mediante `/models` y consulta `/props` cuando necesita
confirmar visión. Si existe un modelo visual conocido puede seleccionarlo automáticamente.

OpenCode Go recibe el texto, el contexto estructural necesario y las referencias adjuntas.
No recibe el archivo `.yunkil` ni un STL completo. La credencial se obtiene de la conexión
de OpenCode, no se muestra en la interfaz ni se escribe en logs.

### Bitácora

Las propuestas —paramétricas y orgánicas— se registran localmente en:

```text
~/Library/Application Support/Yunkil/propuestas.jsonl
```

Cada línea contiene petición, plan, rondas y desenlace. Puede borrarse sin afectar modelos
ni documentos.

### Problemas habituales

- `Hearthia no responde`: iniciar el gateway local en el puerto 9292.
- `Falta conectar OpenCode Go`: ejecutar `/connect` en OpenCode.
- `El modelo no admite imágenes`: seleccionar o instalar uno con visión declarada.
- `Plan no aplicable`: Yunkil pedirá corrección automática indicando operación y motivo.
- `Documento cambió`: repetir la petición sobre el estado actual.
- `NECESITA_DATOS`: responder la cota solicitada en el mismo hilo.
- Una propuesta nunca se aplica sin confirmación.

## Límites conocidos

- El editor visual de perfiles solo manipula segmentos rectos; arcos y Bézier existen en el
  núcleo pero no tienen edición visual completa.
- Los perfiles propios ya tienen versiones —cada reemplazo archiva el anterior y se puede
  restaurar—, pero ninguna holgura calibrada se ha contrastado todavía con impresiones
  sostenidas,
  así que Yunkil garantiza que la geometría tiene la holgura declarada; que esa holgura sea
  la buena para tu máquina depende de lo bien que hayas leído tu cupón.
- Un encaje gobierna la extensión de la pieza en **un** eje. Por omisión la ajusta
  escalando uniformemente —atar el diámetro de un cilindro mueve su altura—; con
  `porParametro` mueve el parámetro responsable del eje y deja los demás quietos. En una
  pieza no se mezclan los dos modos, y donde no hay parámetro responsable no se declara.
- La comprobación de un encaje `RECIBE` mide la mayor **bola** que cabe. En un rebaje más
  ancho que hondo lo que limita es el fondo y no la pared, así que dice menos holgura de la
  que hay. Se queda corta hacia el lado seguro, que es el que corresponde a una
  comprobación que falla cerrado.
- Las medidas del mundo admiten una **tolerancia ±** (`Editor.fijarTolerancia`): la
  incertidumbre se come la holgura, y una verificación contra una medida incierta en
  más milímetros de los que sobran se declara no medible en vez de cumplida.
- Las medidas del mundo se dan de alta con procedencia `a ojo` cuando las escribe la IA:
  al modelo se lo han dicho, no lo ha medido. Subirlas a `con calibre` es cosa de quien
  midió, y el núcleo ya lo permite (`Editor.fijarProcedencia`), pero la interfaz todavía
  no trae el mando.
- El 3MF se escribe sin compresión —el de placa multiobjeto incluido—.
- Las capacidades nuevas de esta entrega —variantes, plantillas, placa multiobjeto,
  informe Markdown, ensamblajes, cables y holgura proporcional— entran por el núcleo y
  están probadas; la interfaz todavía no trae paneles para todas. No se afirma en la
  interfaz lo que la interfaz no expone.
- No existe aplicación iPad ni render por tiles.
- Las partes orgánicas ya se mueven, engordan y quitan por su identificador desde el
  núcleo, pero en el viewport todavía no se agarran: ni sus puntos de control ni la
  selección por clic está cableada en la interfaz.
- Las curvas existen solo en el motor orgánico. El DSL paramétrico crea con
  `parametros: Map<String, Float>`, que no admite una lista de puntos, así que un cable o
  un latiguillo técnico todavía no puede pedirse como pieza paramétrica.
- Las brochas de campo se aplican en un orden fijo —pellizco, arrastre y alisado por
  fuera—, no en el orden en que se dieron los brochazos.
- El pellizco no hace nada sobre una superficie plana: aprieta tangencialmente, así que
  necesita un relieve al que estrechar.
- Las zonas protegidas no se pintan todavía en el viewport; el panel dice cuántas hay y
  permite quitarlas, pero no se ven sobre la figura.
- La simetría refleja el brochazo al pintarlo; no obliga a que la figura entera sea
  simétrica ni refleja lo esculpido antes de fijar el plano.
- No hay reconstrucción neuronal, retopología, materiales, color, rigging ni animación.
- El examen de fabricación cuesta segundos incluso en una pieza pequeña —medido: nueve con
  el núcleo en release sobre una caja de 14 mm—, porque muestrea la superficie a densidad de
  boquilla mida lo que mida la pieza. Corre en segundo plano y avisa del progreso, pero no
  es una operación que se pueda pedir a cada cambio.
- El arnés de la aplicación conduce el estado, no la interfaz: `tools/estado` comprueba qué
  se selecciona, qué gesto abre un punto de deshacer y cuándo se ofrece un asa, pero de la
  disposición y los controles dibujados no hay nada automatizado.
- `App.swift` y `Editor.kt` concentran demasiadas responsabilidades y deben dividirse por
  dominio sin duplicar estado.
- Falta validación sostenida mediante impresiones externas y perfiles calibrados por máquina.

## Prioridades vigentes

Orden obligatorio hasta que este README cambie. El orden de lo que sigue al punto 0 viene
de una auditoría externa cuya tesis se acepta: el salto siguiente no es añadir primitivas,
sino cerrar confianza, reproducibilidad e interacción. Añadir formas nuevas queda detrás.

0. **Validación con impresiones reales.** Es lo único que queda entre «la geometría tiene la
   holgura que declaraste» y «esta pieza entra en tu máquina». La cadena de código está
   entera —cupón, holgura medida, perfil guardado y activo—; falta imprimir cupones en
   máquinas distintas, medirlos y comprobar que la holgura que sale encaja de verdad.
1. Igualar el resto del flujo orgánico de IA al paramétrico: revisión geométrica medida,
   crítico visual y aceptación parcial de un contrato. El fantasma, las rondas de
   validez, la revisión de documento ligada a la propuesta y la bitácora ya están.
2. Selección y modificación directa de partes orgánicas en el viewport: el núcleo ya
   resuelve «qué parte hay bajo este punto» y las edita por su identificador; falta el
   gesto, incluidos los puntos de control de una curva agarrados con el ratón.
3. Paneles de la interfaz para lo que el núcleo ya hace: variantes, plantillas, placa
   multiobjeto, informe exportable, ensamblajes con su verificación y separación,
   cables, la procedencia y la tolerancia de las medidas, el comparador de perfiles, la
   edición semántica de partes orgánicas y los hitos de deshacer. El núcleo está
   probado; la interfaz no lo expone.
4. Editor visual de perfiles con líneas, arcos, Bézier, cotas y restricciones.
5. Curvas en el DSL de la IA paramétrica. El cable ya existe como pieza del documento y
   el motor orgánico lleva años suyo las curvas; falta que el catálogo del modelo pueda
   pedirlas con puntos, que hoy `parametros: Map<String, Float>` no admite.
6. Reconstrucción multivista propia y backend Core ML/Metal para imagen a geometría.
7. SVG y texto paramétrico como perfiles multicontorno.
8. Pintado de las zonas protegidas en el viewport.
9. División automática de figuras, pasadores, huecos de resina y multicolor.
10. Puente Bambu, segunda mitad: la placa 3MF multiobjeto ya existe —pieza y cupón en la
    misma placa, ajustes por objeto en el laminador—; falta abrirla directamente en
    Bambu Studio. Va detrás de lo anterior a propósito: es fontanería y no responde a
    por qué abrir Yunkil, que es lo que responden los encajes.
11. Después: 3MF comprimido e iPad con render por tiles.

No se persigue replicar render, animación, rigging o composición de Blender. La ventaja de
Yunkil debe ser generar, editar semánticamente y certificar piezas técnicas y figuras
imprimibles dentro del mismo sistema.

## Reglas de desarrollo

- No afirmar una capacidad sin implementación y prueba o arnés asociado.
- No copiar código GPL de OpenCADStudio u OpenSCAD dentro de Yunkil. Sus ideas y formatos
  pueden reimplementarse limpiamente; componentes permisivos requieren atribución.
- No ejecutar código producido por la IA. Solo contratos cerrados y validados.
- No rebajar el certificado para hacer pasar una malla defectuosa.
- Todo nodo nuevo necesita evaluación CPU, emisión MSL, cotas y comprobación de paridad.
- Toda edición compleja debe ser transaccional y deshacerse en un paso coherente.
- No añadir compatibilidad hacia atrás sin un consumidor o dato persistido que la necesite.
- No fijar cifras de pruebas, benchmarks o modelos recomendados como verdades permanentes.
- No crear otro roadmap, estado, `CLAUDE.md` o documento de prioridades. Actualizar este
  README y eliminar cualquier fuente paralela.
- No hacer commits, publicar ni modificar configuración del sistema sin petición explícita.
