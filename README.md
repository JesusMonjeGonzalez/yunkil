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
3. Sostener el **encaje con un objeto real** como una relación del documento, no como una
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
- Selección por raymarching CPU, manipulación directa de caras, sección y orientación.
- Historial transaccional con deshacer y rehacer.

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
holgura declarada contra la medida dada**. Los perfiles siguen siendo `de fábrica`: no hay
cupón de calibración, así que `OrigenDelPerfil.CALIBRADO` es hoy un estado inalcanzable.

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
  Si no hay visor, red o respuesta legible, deja pasar la pieza a propósito: es un revisor
  *de más* y uno que se cae no puede tumbar un plan que los revisores exactos aprobaron.
  Hoy eso no se distingue de haberla mirado y aprobado.
- La referencia acompaña todas las rondas de corrección, no solo la primera.
- El modo `Pieza técnica` genera operaciones paramétricas; `Figura orgánica` genera una
  escultura semántica nativa.

Los dos modos **no** tienen las mismas garantías, y conviene no confundirlos. Solo el
paramétrico interpreta el plan, lo aplica en aislamiento, lo revisa geométricamente y lo
corrige en rondas; solo él tiene fantasma en el viewport, explicación, aceptación parcial,
bitácora en `propuestas.jsonl` y una propuesta ligada a la revisión monotónica exacta del
documento que vio la IA. El orgánico pide el contrato, lo valida y lo ofrece para aceptar
o descartar entero: no dibuja fantasma, no corrige en rondas, no se registra y no comprueba
que el documento siga siendo el mismo al aceptar. Igualarlos es la prioridad 3.

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
- Importación STL ASCII/binaria, soldadura de vértices y horneado a campo de distancia.
- Una malla no se escribe si no supera el certificado.

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
├── Gobernador.swift
├── AIService.swift
├── PanelDePropuesta.swift
├── CriticaVisual.swift
└── EditorVisualDePerfil.swift

tools/
├── paridad/      arnés CPU ↔ Metal
├── fantasma/     arnés de la previsualización
├── rayo/         arnés del rayo de cámara
└── gizmo/        escrito, todavía sin ejecutar por `comprobar.sh`
```

### Invariantes

- `SdfNode.evaluar` es la referencia geométrica.
- `SdfNode.cotas` puede sobrar, pero nunca dejar material fuera.
- CPU y MSL deben evaluar la misma forma.
- Los números viajan como uniforms; cambiar una cota no recompila el shader.
- Cambiar topología sí regenera la huella y recompila Metal.
- Todo bucle emitido en MSL tiene un límite constante.
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
./scripts/comprobar.sh                # núcleo, CPU/Metal, fantasma, rayo y app
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

Las propuestas paramétricas se registran localmente en:

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
- No hay todavía gizmo visible para mover y girar. `tools/gizmo/` es un arnés escrito por
  delante de la función: `comprobar.sh` no lo ejecuta y no comprueba nada hoy. Un arnés que
  no corre parece cobertura sin serlo, así que o entra en el guion cuando exista el gizmo o
  se borra.
- Los perfiles de fabricación son constantes verificadas; no tienen editor, persistencia,
  calibración ni versión propia. Sin cupón de calibración, `OrigenDelPerfil.CALIBRADO` no
  lo produce nada y todas las holguras siguen siendo de fábrica: Yunkil garantiza que la
  geometría tiene la holgura declarada, no que esa holgura sea la buena para tu máquina.
- Un encaje gobierna la extensión de la pieza en **un** eje y la ajusta escalando
  uniformemente, así que atar el diámetro de un cilindro también mueve su altura. Es
  coherente con la escala uniforme del resto del sistema, pero significa que una pieza que
  deba encajar por dos cotas independientes todavía no se puede declarar.
- La comprobación de un encaje `RECIBE` mide la mayor **bola** que cabe. En un rebaje más
  ancho que hondo lo que limita es el fondo y no la pared, así que dice menos holgura de la
  que hay. Se queda corta hacia el lado seguro, que es el que corresponde a una
  comprobación que falla cerrado.
- Las medidas del mundo se dan de alta con procedencia `a ojo` cuando las escribe la IA:
  al modelo se lo han dicho, no lo ha medido. Subirlas a `con calibre` es cosa de quien
  midió, y hoy solo se puede hacer desde el núcleo, no desde la interfaz.
- El contorneado deja agujeros en codos cerrados a ciertas resoluciones. Una cola de 4 mm
  de radio con un giro cerrado falla el certificado a 0,8 y a 0,4 mm y lo pasa a 0,5 y a
  0,3 mm. No depende de la primitiva: la misma cola como cordón y como cadena de cápsulas
  falla igual, con el mismo número de triángulos. No es el salto de celdas del muestreo
  —desactivarlo no cambia nada—, así que queda por diagnosticar. El certificado hace lo
  que debe y se niega a escribir, de modo que el síntoma es un export que no sale, no una
  pieza rota; el reintento automático al doble de detalle tampoco lo salva porque la
  mitad de una resolución que falla también falla. La resolución que sugiere la
  aplicación para una figura de ese tamaño —0,3 mm— sí exporta, así que esto se ve
  pidiendo un mallado grueso a mano, no en el camino normal.
- El 3MF se escribe sin compresión.
- No existe aplicación iPad ni render por tiles.
- Las curvas se escriben en el contrato y se reeditan reescribiéndolo o pidiéndoselo otra
  vez a la IA; sus puntos de control no se pueden agarrar todavía en el viewport.
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
- Importar una malla y rehornearla al abrir corren en el hilo principal: la aplicación se
  queda quieta mientras rasteriza los triángulos contra la rejilla. Se avisa antes para que
  no parezca colgada, pero avisar no es no bloquear. El análisis, en cambio, sí corre
  aparte sobre una copia del documento; lo que le falta es comprobar al terminar que el
  documento sigue siendo el que midió.
- No hay ni una prueba automatizada de la aplicación macOS. `comprobar.sh` la construye y
  verifica paridad, fantasma y rayo de cámara, pero nada de lo que hay en `App.swift`.
- `App.swift` y `Editor.kt` concentran demasiadas responsabilidades y deben dividirse por
  dominio sin duplicar estado.
- Falta validación sostenida mediante impresiones externas y perfiles calibrados por máquina.

## Prioridades vigentes

Orden obligatorio hasta que este README cambie. El orden viene de una auditoría externa
cuya tesis se acepta: el salto siguiente no es añadir primitivas, sino cerrar confianza,
reproducibilidad e interacción. Añadir formas nuevas queda por detrás de eso.

1. Que importar y rehornear una malla no bloqueen la interfaz. El análisis ya corre fuera
   del hilo principal sobre una copia, pero al terminar no comprueba que el documento
   siga siendo el que midió, así que puede pintar un informe de otra pieza.
2. Pruebas automatizadas de la aplicación macOS. Hoy `comprobar.sh` la construye y prueba
   la paridad, el fantasma y el rayo de cámara, pero no hay ni una prueba de la app.
3. Igualar el flujo orgánico de IA al paramétrico: fantasma, revisión por rondas, revisión
   de documento ligada a la propuesta y bitácora.
4. Decir cuándo la crítica visual no ha llegado a mirar la pieza. Que falle abierta es
   deliberado —es un revisor de más, y uno que se cae no puede tumbar un plan que los
   revisores exactos ya aprobaron—, pero hoy no se distingue «la ha visto y le parece
   bien» de «no ha podido verla».
5. Selección y modificación directa de partes orgánicas, incluidos los puntos de control
   de una curva agarrados en el viewport.
6. Gizmo de mover/girar, ensamblajes ligeros e interferencias.
7. Perfiles de fabricación persistentes, editables, calibrables y versionados, con **cupón
   de calibración**: una probeta de pasadores y agujeros a holguras escalonadas que se
   imprime, se mide y fija la holgura del perfil marcándolo como `CALIBRADO`. Es lo que
   convierte «la geometría tiene la holgura que declaraste» en «la pieza entra en tu
   máquina», y lo único que hoy separa una cosa de la otra.
8. Validación con impresiones reales y perfiles calibrados por máquina.
9. Editor visual de perfiles con líneas, arcos, Bézier, cotas y restricciones.
10. Curvas en el DSL paramétrico, para cables, latiguillos y guías técnicas.
11. Reconstrucción multivista propia y backend Core ML/Metal para imagen a geometría.
12. SVG y texto paramétrico como perfiles multicontorno.
13. Pintado de las zonas protegidas en el viewport.
14. División automática de figuras, pasadores, huecos de resina y multicolor.
15. Puente Bambu: 3MF multiobjeto con plato y ajustes por pieza, y abrir directamente en
    Bambu Studio. Hoy el 3MF lleva un solo objeto, así que una pieza y su cupón de
    calibración no pueden salir en la misma placa. Va detrás de lo anterior a propósito:
    es fontanería y no responde a por qué abrir Yunkil, que es lo que responden los
    encajes.
16. Después: 3MF comprimido e iPad con render por tiles.

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
