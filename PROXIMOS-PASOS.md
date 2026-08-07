# Yunkil — próximos pasos

Estado a 6 de agosto de 2026. Para cómo funciona lo que ya existe, ver
[docs/ARQUITECTURA.md](docs/ARQUITECTURA.md). Para qué tienen los competidores y cómo se
hace aquí, [docs/TOP10-COMPETIDORES.md](docs/TOP10-COMPETIDORES.md).

### Hecho el 6 de agosto de 2026 (noche)

**El arnés de paridad no compilaba, y tapaba un fallo de 20 mm.** Es lo más grave de
la jornada y conviene contarlo entero. Al extraer el lanzamiento del shader a una
función para poder correr también la versión podada, el `cmd` que se consultaba
después se quedó fuera de alcance: `tools/paridad/main.swift` dejó de compilar. Como
se lanza a mano con `swiftc`, nadie lo notó, y la frase «el arnés comprueba además
que `yk_marcha ≤ yk_map` en los 22 casos contra Metal real» se escribió sobre una
comprobación que nunca llegó a ejecutarse.

Al arreglarlo y correrlo, un caso de 22 divergía: `acuerdo_union`, con la marcha
devolviendo **hasta 20 mm más** que el campo exacto. La causa está en el generador y
es de una línea: `emitir` toma la base de uniforms del nodo *antes* de reservarla,
pero la poda ya había adelantado el cursor para poder leer la caja antes de decidir
si bajaba por la rama. Con `cursorYaReservado` el bloque queda **detrás** del cursor,
y el nodo leía los catorce huecos del siguiente. No da error de compilación, no
afecta a `yk_map` —que no poda, y es la que comprueba la paridad— y solo estropea el
cuerpo que dibuja la pantalla. Un rayo que avanza 20 mm de más se salta la pieza.

Dos cosas más salieron de ahí. La comprobación de la poda era **de un solo lado**
(«que no se pase»), y con eso la mitad del fallo pasaba desapercibida: cuando los
uniforms equivocados daban un número *menor*, la marcha era segura para el trazado y
seguía dibujando otra pieza. La poda es exacta por construcción, así que ahora se
comprueba la igualdad. Y el fallo no era solo del acuerdo local: `ParidadDeCuerposTest`
compara los índices de uniforms que lee cada cuerpo y **fallaba también en unión y en
diferencia**, que es lo que un arnés de GPU de un solo lado no podía ver.

Y al arnés le faltaba lo más obvio: **los modelos que la aplicación abre de verdad**.
Los 22 casos se escribieron a mano para cubrir cada tipo de nodo, y ninguno era la
pieza que el usuario tiene delante al arrancar. Ahora se vuelcan también `demo_soporte`
y `demo_rejilla` compilados desde su documento; comprobado revirtiendo el arreglo a
propósito, los dos fallan (8 de 24 casos), así que el viewport llevaba toda la tarde
dibujando geometría equivocada y ningún caso escrito a mano lo decía. Son 24 casos y
el peor desvío global es 3,05e-05.

**Una sola orden para comprobarlo todo** (`scripts/comprobar.sh`). Era la deuda que
permitió lo anterior: una comprobación que hay que recordar cómo se lanza es una
comprobación que no se lanza. Corre las pruebas del núcleo, vuelca y verifica la
paridad CPU↔GPU, el arnés del rayo y la construcción de la app.

**El plan se cose solo** (`Editor.coserPlan`). El banco dejó dicho que el único modo
de fallo que queda es el contacto entre piezas, y que enseñarle al modelo la operación
literal subía la geometría limpia de 6/8 a 7/8 pero seguía dependiendo de que la
transcribiera bien y de gastar una ronda entera. Si el revisor ya sabe qué operación
une lo que quedó suelto, se aplica: `RevisorDeGeometria.reparos` devuelve el `colocar`
con su cara deducida de dónde está cada sólido, y `coserPlan` lo prueba en un banco y
**vuelve a medir el campo**. Solo devuelve el plan cosido si los sólidos quedaron
unidos de verdad; si no, `null` y el fallo sigue su camino hacia la ronda de
corrección. Coser nunca puede dejar la pieza peor.

Dos decisiones dentro: el arreglo entra con **holgura negativa** —solapa el grosor de
una pared, limitado a un cuarto de la pieza— porque dos sólidos que se tocan en un
plano son un sólido para el campo y un filo para el mallador; y se prueba primero
**sin centrar** y solo después centrando, porque recentrar desharía un desplazamiento
que podía ser intencionado. Cuál sirve no se supone: se mide.

**El intérprete resuelve por nombre.** El revisor le enseña al modelo los nombres de
las piezas —«Tapa», «Base»—, no sus alias, y hasta ahora `colocar` con un nombre se
omitía sin explicación. Ahora `resolver` cae al nombre cuando no hay alias, y con dos
piezas del mismo nombre no elige ninguna: adivinar cuál quería el modelo es el tipo de
suposición que produce piezas mal montadas sin que nadie se entere.

**3MF** (`malla/TresMf.kt`). El STL no dice en qué unidades está; de ahí sale el
clásico modelo que entra en el laminador a 1/25 de su tamaño. El 3MF lo declara, y
además es lo que abren Bambu Studio y OrcaSlicer de forma nativa, que es el puente del
subproyecto 4. La conversión que importa es de **Y arriba a Z arriba** con la pieza
apoyada en el plato: sin ella, lo que el analizador midió como voladizo no tiene nada
que ver con lo que la máquina va a imprimir. Se hace con `(x,y,z) → (x,−z,y)`, que es
un giro y no un espejo —determinante +1, el sentido de los triángulos se conserva—, y
hay una prueba que mide el volumen con signo sobre las coordenadas ya convertidas
justo para cazar esa confusión. El ZIP se escribe a mano, sin comprimir, y el paquete
se comprueba abriéndolo con `java.util.zip` y con el `unzip` del sistema: un ZIP
escrito a mano puede tener el CRC mal y parecer perfecto desde dentro. El diálogo de
exportar ofrece los dos formatos y propone `pieza.3mf`.

**Auto-intersecciones en el certificado.** Era la última comprobación de la lista del
examen de mallas. Una superficie puede ser cerrada, estar bien orientada, no tener
degenerados y aun así cruzarse consigo misma, y entonces el laminador no sabe qué es
dentro. Rejilla espacial con una celda por triángulo y cruce de arista contra cara;
medido: 711 ms para 376.000 triángulos, que al lado del mallado a esa resolución no se
nota. **Dicho lo que no hace**: en las pruebas hechas hasta ahora no ha cazado ningún
caso real, porque el contorneado prefiere perder la pared fina antes que cruzarla.
Se queda porque un examen que afirma «esta malla está bien» sin haber mirado eso está
afirmando algo que no ha comprobado.

Y de intentar romperlo salió un fallo de verdad: una chapa de 0,3 mm mallada a 1 mm da
**cero triángulos**, y una malla vacía pasa por «cerrada» y «bien orientada» de vacío.
Lo paraba el volumen, pero el informe decía «MALLA NO APTA, triángulos 0» y dejaba al
usuario deduciendo. Ahora lo dice con palabras y con el arreglo.

**El arrastre de STL nunca funcionó.** Se dio por hecho esta misma tarde. Tenía dos
fallos: la vista no llamaba a `registerForDraggedTypes`, así que no recibía ni un
mensaje de arrastre, y la comprobación del tipo hacía `$0 as? String` sobre un
`PasteboardType`, que es una estructura y no un `NSString` puenteado — la conversión
**siempre** falla. El compilador lo avisaba. Ahora se mira la extensión de los archivos
que trae el arrastre, que es lo que se quería desde el principio.

**El banco repite y cuenta los cosidos.** `--args="URL MODELO N"` corre cada caso N
veces y saca la proporción y el desglose por caso, que era el punto 1 de la lista de
abajo: sin eso, comparar dos modelos con ocho números sueltos no significa nada. Y una
fila nueva, «Cosidos por Yunkil», aparte del resto a propósito: mezclarla taparía justo
el número que dice cuánto trabajo está haciendo el producto por el modelo.

*Pendiente de medir*: el cosido no se ha corrido contra un modelo real todavía —el
stack local estaba parado— y ese número es el que dice si la geometría limpia sube.
*Pendiente conocido*: el 3MF se escribe sin comprimir, así que ocupa unas tres veces
lo que el STL equivalente.

### Hecho el 6 de agosto de 2026 (tarde)

**Sección en vivo** (punto 5 del top 10). `yk_distancia` envuelve `yk_map` con el
semiespacio del plano —el corte deja el lado positivo de la normal— y es lo que usan
la marcha, la normal y la oclusión; `yk_map` no se toca y la paridad sigue intacta
(22 casos en Metal, peor desvío 1,53e-05). La cara de corte se reconoce por la
normal y se tiñe por el grosor de pared leído del campo, con la misma marcha que el
analizador: rojo por debajo del mínimo del perfil, verde por encima del doble. El
rectángulo visible del plano se pinta analíticamente con rejilla de 10 mm, se activa
con la tecla 6 y se arrastra sin modificador con `avanceDeArrastre`. Límite honesto:
el tinte mide el material que queda hacia dentro del corte; el analizador FDM sigue
siendo la verdad.

**Interferencias y holguras** (punto 6). `revisarEncuentros` en el analizador mira
pares de piezas que cuelgan de la raíz **sin fusión** (con la raíz fundida, todo
solape es unión buscada y denunciarla sería ruido). El solape reporta el volumen en
mm³ —el dato para decidir si es error o unión buscada— y la holgura se mide en la
banda alrededor de la superficie de B contra `holguraEncaje` del perfil. La
corrección `[Separar…]`/`[Holgar…]` mueve la pieza con la nueva acción
`MOVER_PIEZA` (dirección unitaria + distancia), que entra en el historial.

**Historial navegable** (punto 7). `Documento.planesAplicados` guarda cada plan de
la IA que se aplicó, con sus alias; `Editor.reproducirPlanes()` lo ejecuta sobre un
documento vacío con el mismo `Aplicador`, y `reemplazarPlanEnHistoria(i, nuevo)`
reconstruye con el plan nuevo en su sitio como una sola edición —un solo ⌘Z—.
Probado: «los agujeros eran M3, ponlos M4» cambia el radio del agujero medido en el
campo sin deshacer nada. Los planes sobreviven al guardado y a la apertura.

**Copiloto sobre la selección** (punto 8). El contexto ahora dice quién está
señalado y cuánto ocupa (`Seleccionada: #id «Base» · x[..] y[..] z[..] · máx 3 ops`),
y el intérprete distingue edición de generación: con selección, un plan que intenta
`crear` se rechaza y más de 3 operaciones también, con el motivo exacto. La app
decide el modo mirando si la petición no empieza por «crea/haz/hazme…». El prompt
sigue bajo el presupuesto de 14.000 caracteres (se recortaron las frases que el
bloque nuevo duplicaba).

**Salto por cotas de nodo** (punto 9.2). El generador emite dos cuerpos: `yk_map`
sigue siendo la verdad exacta —la que comprueba la paridad— y `yk_marcha` añade, en
cada unión, diferencia y acuerdo local, un descarte contra la caja del segundo
hijo (`if (cajaB > a + k) d = a else …`). La poda es **exacta**: la caja contiene al
sólido, así que si está más lejos que lo ya calculado la rama no puede ganar, y el
valor del campo no cambia — solo se ahorran evaluaciones. Las cajas viajan al final
del buffer (6 floats por nodo en preorden, sin tocar el orden de los escalares), y
el arnés de paridad ahora comprueba además que `yk_marcha ≤ yk_map` en los 22 casos
contra Metal real. La marcha usa la podada; la normal, la oclusión y el tinte de la
sección siguen con la exacta.

**El STL de fuera devuelto imprimible** (punto 10). El hueco era el cable entre
piezas que ya existían: la vista acepta STL arrastrados y `importarMallaDesde`
importa → encuadra → analiza al momento. El informe ya nombra la pieza en cada
aviso y cada hallazgo trae su corrección ejecutable; el exportador ya se niega a
entregar una malla que no pasa el examen. Falta el 3MF orientado y el puente a
OrcaSlicer, que están en la lista de abajo.

Con esto el top 10 de Yunkil está completo: selección, empujar/tirar, filete,
clic derecho, sección en vivo, interferencias, historial navegable, copiloto,
salto por cotas y el STL de una acción. Lo que queda de fondo sigue en la lista.

### Hecho el 6 de agosto de 2026

**Señalar en el viewport** (`doc/Picking.kt`). Mover una pieza era teclear números porque
faltaba el eslabón anterior: saber qué hay debajo del cursor. Se resuelve en el núcleo y
no en el shader, y la decisión tiene motivo: `evaluar` es la verdad de referencia del
sistema, así que señalar no puede discrepar de lo que se exporta ni de lo que analiza el
analizador, y no hay que sostener un segundo invariante de paridad para algo que solo
ocurre al hacer clic. De paso funciona sobre una `MALLA` horneada, que el generador de MSL
todavía no sabe emitir.

La regla de atribución es un hecho geométrico y no una política: la superficie del punto
de impacto pertenece a la pieza cuyo propio contorno pasa por ahí. De eso sale gratis lo
que uno quiere al pinchar la pared de un agujero —que se seleccione el taladro y su
diámetro aparezca en el inspector—, sin escribir ninguna excepción. La maquinaria es
`Pieza.solo(id)`: podar el árbol a la rama que lleva a una pieza, para que el contorno que
se compara pase por los mismos modificadores (un `VACIADO` no deja la superficie donde
está el contorno, sino a medio grosor de él).

**Empujar y tirar de una cara** (`doc/Asas.kt`). Cada `TipoPieza` declara sus asas —qué
parámetro gobierna cada cara—, igual que ya declaraba sus parámetros, así que añadir una
primitiva no obliga a tocar el código del arrastre. Lo que hace que el gesto no se sienta
roto es la **compensación del centro**: las primitivas están centradas, así que crecer 10
mm mueve las dos caras 5, y hay que desplazar el centro medio crecimiento para que la de
enfrente se quede quieta. Y se compensa **lo conseguido, no lo pedido**: al topar con el
máximo, compensar el arrastre entero movería la pieza sin que creciera.

En la app es ⌘ y arrastrar sobre la propia cara. No hay flechas que dibujar y no es una
carencia: el renderizador no tiene tubería de vértices, y agarrar la superficie es como se
siente en Plasticity.

**Filete de un canto concreto** (`kernel.AcuerdoLocal`). `fusion` redondea *todo* el
encuentro entre dos sólidos; esto multiplica la anchura de la mezcla por una caída
centrada en el punto pinchado, así que dentro de esa esfera hay filete y fuera la booleana
sigue exacta. La ventaja sobre un B-rep es que **no hace falta topología**: la arista se
elige apuntando. La limitación, dicha antes de que la descubra nadie: es un filete «de
bola», y si la arista se curva dentro de la esfera el radio no sale constante.

Lo que salió al hacerlo, y es el hallazgo que importa: **una mezcla cuya anchura cambia
con la posición no es 1-Lipschitz**. La prueba midió 1,383 de gradiente peor, y la cuenta
lo predice —el mínimo suave no se aparta más de `k/4` y la caída `(1−t²)²` tiene pendiente
máxima 1,54, luego 0,385 por unidad de `fusion/radio`—. En vez de esconderlo, `MslGenerator`
publica un **paso de trazado seguro** por árbol (`ShaderGenerado.pasoSeguro`) y el
renderizador lo multiplica por el del gobernador: un filete pequeño no cuesta casi nada y
solo se paga cuando la mezcla es tan ancha como su alcance. Con tres casos nuevos en el
arnés de paridad, así que el shader está comprobado contra Metal real (22 casos, peor
desvío 1,53e-05).

**Interacciones de modelado y clic derecho.** Aislar, mostrar todo, sacar del grupo
—conservando la posición en el mundo, o no serviría de nada—, copiar y pegar con
identificadores renovados, y **apoyar una cara en el plato**: se pincha la cara que va
abajo y la pieza se gira y se baja. Menú contextual en el árbol y en el viewport, barra de
menús de verdad con los atajos de siempre (⌘D, F, ⇧F, 1/3/7/9, 5, Supr), y **proyección
ortográfica**, que en una herramienta de cotas se quiere la mitad del tiempo.

Los atajos van en `MenusDeYunkil` y el estado subió a la escena: un `keyboardShortcut`
declarado dentro de la vista se anuncia pero no se registra, y el usuario pulsa ⌘D y no
pasa nada. Es el mismo fallo que ya se cazó en Editorcito.

**Que la IA modele bien, no solo que modele.** Dos operaciones nuevas en el vocabulario,
elegidas por lo que un modelo hace mal por naturaleza:

- `filete` con `objetivo` y `contra`. El modelo no tiene cursor, así que nombra las dos
  piezas del canto y **el punto lo mide Yunkil** intersecando sus envolventes. Que se
  invente una coordenada del espacio es justo el fallo que se evita.
- `apoyar` con el nombre de la cara. Un modelo sabe cuál es la cara plana grande de lo que
  acaba de diseñar y no sabe escribir el cuaternión que la tumba.

Y un fallo encontrado al hacerlo: la tabla de alias del intérprete mandaba `apoyar` a
`asentar`, que baja el modelo entero sin girar nada **y tira el objetivo por el camino**.
Un modelo que pedía apoyar una cara obtenía un movimiento vertical y ninguna queja.

Al prompt se le añadió **el orden de trabajo en siete pasos** (forma, sitio, quitar,
ahuecar, acotar, taladrar, acabar) y los **principios de modelado** —un solo cuerpo, la
masa donde está el esfuerzo, los cantos vivos se rompen, pensar en cómo se imprime,
simetría y repetición en vez de copias, cada cota con un motivo—. El orden importa y estaba
disperso: acotar escala los agujeros, así que taladrar va después.

El prompt topó con su propio límite al hacerlo: llegó a 15.358 caracteres y **hay una
prueba que lo rechaza por encima de 14.000**. No es estilo: con 16K de contexto, cada
carácter del mensaje de sistema es uno que el modelo no puede gastar en razonar, y ya pasó
una vez que el plan salía entero y correcto en el razonamiento y se cortaba antes del JSON.
Se recortó comprimiendo las reglas que mis bloques nuevos duplicaban, sin perder ni un
dato concreto. Quedó en 13.997.

**El banco existe** (`tools/Banco.kt`, `./gradlew :core:banco`). Era lo que faltaba para
que la elección de modelo dejara de ser criterio y pasara a ser dato: ocho peticiones
reales contra el modelo local por la cadena real —interpretar, revisar en banco aislado,
aplicar— con **asertos comprobables sobre el documento resultante**, no sobre el texto que
escribió el modelo: que la escuadra salga de un contorno y no de cajas, que la oreja de
rack quepa en el plato, que el tubo sea un BARRIDO, que el ancho acotado mida lo pedido.

## Dónde estamos

El núcleo, el editor macOS, el viewport Metal y la paridad CPU/GPU funcionan.
Existe ya un primer flujo de exportación STL con examen y escritura atómica, además
de creación guiada por IA local/OpenCode Go. Desde el 6 de agosto hay manipulación
directa: señalar, empujar caras, filetear un canto y apoyarlo en el plato.

Sigue siendo una beta técnica. Las auto-intersecciones y el 3MF salieron de la lista la
noche del 6 de agosto; lo que queda es la **malla importada en el viewport**, el
**despacho por tiles** antes de tocar iPad, la **compresión del 3MF**, y sobre todo
**validación con impresiones externas**, que es el único gate que no se puede aprobar
escribiendo código.

### Hecho el 5 de agosto de 2026

- **`acotar`**: llevar una cota concreta a su medida real escalando el conjunto.
  `escalar` obliga a saber cuánto mide algo para sacar el factor, y esa división es
  donde el modelo falla. Ahora la hace el núcleo, que sí conoce las cotas.
  Con `objetivo` a `modelo` acota el montaje entero conservando proporciones, que es
  la forma correcta de responder a «que mida 8 cm de ancho»: modelar con
  proporciones cómodas y cerrar con una medida, en vez de repartirla a mano.
- **`BARRIDO`**: sección circular recorriendo un camino. Es la familia que no se
  podía hacer —tubos doblados, marcos, aros, asas, canaletas, grapas— y que antes se
  aproximaba encadenando cilindros, con codos de canto vivo y coordenadas a ojo.
  En SDF es una cadena de cápsulas: exacta, 1-Lipschitz, con los codos redondeados
  por construcción, y **reutiliza `Perfil2D`**, así que el camino se da con la misma
  operación `perfil` y viaja con el mismo empaquetado de uniforms. `cerrado` decide
  marco o tubo. Con shader propio (`yk_barrido`), así que la paridad CPU/GPU se
  mantiene. Dos ejemplares nuevos: asa en U y marco rectangular.
- **`taladro` acepta un punto del contorno.** Salió de una ejecución real: a un
  modelo local con visión se le dio el dibujo de una escuadra en L, razonó bien todo
  el plan y se atascó exactamente aquí —«*the centroid of this L-shape is roughly
  around (25,25)… this is tricky*»— hasta plantearse **tirar el contorno y volver a
  apilar cajas**, que da peor pieza. `desplazamiento` cuenta desde el centro de la
  caja envolvente, y ese centro es justo lo que no puede saber: los contornos arrancan
  en su esquina. Con `"punto":[u,v]` da la coordenada que **acaba de escribir** en
  `perfil` y la conversión la hace el núcleo. Busca el contorno hacia abajo en el
  árbol, así que el segundo agujero —que ya apunta a la DIFERENCIA del primero—
  también funciona. Probado además que los dos caminos aterrizan en el mismo sitio.
- **El intérprete tolera separadores**: `set_size`, `setSize` y `set size` son la
  misma operación. Se compara sin separadores en vez de ir añadiendo variantes a la
  tabla cada vez que un modelo escribe en snake_case, que es siempre.
- 223 pruebas del núcleo, 0 fallos (1 anotada como defecto abierto) (eran 174 al empezar el día; la cuenta de «161»
  que traía este documento estaba desfasada).

### Malla importada → campo de distancias *(5 de agosto de 2026)*

**El núcleo ya acepta geometría que no se puede escribir como fórmula.** Es el salto
que faltaba: un pato de dibujos, una figura escaneada o cualquier STL descargado son
cien mil triángulos, y ninguna combinación de primitivas los reproduce. En cuanto la
malla se hornea a campo, deja de ser un cuerpo extraño y **compone con todo lo demás**:
restarle una ranura, ahuecarla con `pared`, taladrarle imanes, acotarla a una medida
exacta, pasarle el analizador FDM y exportarla verificada. Coger un STL cualquiera y
devolverlo imprimible *y comprobado* no lo hace nadie.

- `LectorStl`: binario y texto, sobre bytes y no sobre archivo, para poder verificarlo
  sin tocar disco. Detecta el formato por el **tamaño declarado** y no por la palabra
  «solid», que es con lo que se atraganta la mitad de los lectores. Suelda vértices
  repetidos: sin eso cada arista se queda sin pareja y una malla cerrada sale abierta.
- `CampoDeMalla`: distancia exacta en una banda por rasterizado de triángulos, y el
  signo por **paridad de cruces** de una recta vertical. Fuera de la banda el valor se
  satura a ±banda, que es una cota inferior de la distancia real: es lo que necesitan
  el trazado y el salto de espacio libre para seguir siendo correctos.
- Verificado de ida y vuelta contra una forma con fórmula: esfera analítica → mallada →
  horneada → comparada con la analítica. Y una malla importada vuelve a mallarse
  **cerrada y bien orientada**.

**Dos fallos que salieron al hacerlo, los dos cazados por las pruebas:**

1. La primera versión decidía el signo inundando «fuera» desde el borde y parando al
   llegar a la banda. Está mal: la banda es de dos celdas y media, así que una celda
   que está fuera pero a dos celdas de la superficie no toca ninguna celda de fuera y
   salía marcada como dentro. Bolsas de material inventado y seis aristas abiertas.
2. La paridad tampoco salió a la primera. Una columna de la rejilla cae **exactamente**
   sobre la diagonal que comparten dos triángulos —y cae siempre, porque las mallas
   tienen diagonales que pasan por los puntos de la rejilla—, los dos la daban por
   buena, se contaban dos cruces en la misma z y la paridad se anulaba. Un cubo con el
   centro «fuera». Se arregló con la regla top-left del rasterizado clásico.

**Ya se puede usar desde la app**: botón «Importar STL…» en la paleta. La pieza entra
como `TipoPieza.MALLA`, se le puede taladrar, ahuecar, restar y acotar, y sale por el
exportador con su certificado. El campo no se guarda en el `.yunkil` —son megas— así
que se guarda la **ruta del original** y al abrir se vuelve a hornear; si el archivo
se movió, se dice cuál falta en vez de dejar un hueco. Probado de punta a punta contra
disco: exportar → importar → taladrar → exportar certificado.

**Defecto abierto: agujeros al exportar a ciertas resoluciones.** La misma pieza
importada y taladrada sale estanca a 0,6, 0,4 y 0,35 mm, y **agujereada a 0,5**. Que
no sea monótono descarta que falte resolución. Descartado también: no es la regla de
bordes de la paridad —se arregló y este caso no se movió— ni columnas cayendo sobre
vértices —se probó a desplazarlas y no cambió, y encima abrió una arista en un caso
que antes cerraba, así que se retiró—. La causa no está localizada.
Queda como prueba con `@Ignore` en `ImportarMallaTest` para que se ponga verde sola el
día que se arregle. **El exportador lo detecta y se niega a entregar el archivo**, así
que el fallo es «prueba con otra resolución», no un STL roto en manos de nadie.

De paso salió una regla que se queda: el exportador **no afina por debajo del paso del
campo horneado**. Refinar a ciegas convertía una pieza que salía estanca a 0,4 mm en
una agujereada a 0,125, y mallar más fino que los datos no saca detalle porque no está.

**Lo que falta, y no es poco:**

- **El viewport no lo pinta.** El generador MSL emite matemática pura con un buffer de
  uniforms; un campo horneado necesita una textura 3D, o sea tocar el enlace de
  recursos del renderizador. Mientras tanto se emite la envolvente y se dice.
  **La evaluación en CPU sí es el campo real**: restar, ahuecar, analizar y exportar
  son exactos desde ya. Lo aproximado es la vista previa.
- **Persistencia.** Un `.yunkil` es JSON y un campo horneado son megas. Hay que
  decidir entre archivo adjunto binario o guardar la ruta del original y volver a
  hornear al abrir. Lo segundo es más limpio y se rompe si mueven el archivo.
- **El detalle tiene tope.** Un campo a resolución *r* no reproduce nada menor que *r*
  y redondea las aristas vivas. En una figura orgánica no se nota; en una pieza
  mecánica de cantos limpios sí. Por eso esto es la vía para traer geometría de fuera,
  no para sustituir a las primitivas: una caja sigue siendo una caja exacta.

### Patrones de montaje normalizados *(5 de agosto de 2026)*

`Estandares` y la operación `patron`. Es `Roscas` llevada al montaje: aquí «se parece»
y «encaja» son cosas distintas y medio milímetro las separa. Cotas contrastadas contra
EIA-310 y VESA FDMI, no de memoria.

- **RACK_19**: 1U = 44,45 mm, panel 482,6 mm, carriles a 465,1 mm, altura de panel
  n×44,45 **− 0,79 mm** para que no roce con el de arriba. El reparto vertical **no es
  regular** —12,7 / 15,875 / 15,875 dentro de cada unidad, con la media pulgada a
  caballo entre dos— y repartirlo por igual desplaza la fila entera.
- **VESA** 75, 100, 200×100, 200 y 400, cada uno con su rosca.
- **RACK_19_OREJA**: la columna de un solo lado. Es la que hay que usar casi siempre,
  y salió de que el analizador rechazara el panel entero con razón: 482,6 mm no caben
  en el plato de 256 de una P1S. Lo que la gente imprime son las dos orejas.
- **RASPBERRY_PI** 4/5 y Zero. El patrón de la grande **no está centrado**: 3,5 mm de
  margen por un lado y 23,5 por el de los USB. Es el fallo que tiene media Printables.

**Que los estándares se alcancen solos.** Tener la operación en el prompt no basta: un
modelo pequeño no usa algo que solo ha visto nombrado en una lista, lo usa cuando lo ha
visto aplicado. Hay tres ejemplares que la aplican —oreja de rack, adaptador VESA y
base de Raspberry Pi—, los tres pasan la revisión geométrica y el control de plato, y
hay prueba de que las peticiones normales los **recuperan**: «unas orejas para el rack
de 19 pulgadas» trae el de la oreja.

**Un fallo que lo hacía inalcanzable, encontrado por el camino.** La `CAJA` topaba en
400 mm y un panel de rack mide 482,6. La anchura se recortaba **en silencio**, los
agujeros de `patron` caían fuera de la pieza y salía una bandeja sin taladrar. Los
límites de las primitivas suben a 600 mm de lado y 300 de radio, fijados por lo que hay
que poder construir y no por un número redondo, y una prueba ata las dos cosas: cada
estándar del catálogo tiene que caber en lo que una primitiva puede medir.

### El catálogo de ejemplares no se estaba usando *(5 de agosto de 2026)*

`Ejemplares.relevantes` **no lo llamaba nadie**. Veintiún planes verificados —cada uno
interpretado, aplicado sin omisiones y pasado por la revisión geométrica— y el modelo
no veía ninguno jamás. Se había escrito el catálogo, se había escrito la recuperación
y se había escrito la prueba de que recupera el correcto; faltaba el cable.

Es la explicación de por qué el modelo seguía apilando cajas con coordenadas a ojo en
vez de usar `patron`, `taladro` o `acotar`: **una operación nombrada en una lista no se
usa, una operación vista aplicada sí.** Ahora la petición viaja hasta el constructor
del prompt (`instruccionesParaModelo(nombrePerfil, peticion)`), que engancha los dos
ejemplares más cercanos. Dos y no más: el mensaje de sistema ya es largo y el tercero
desplazaría a las reglas.

Y una nota sobre la prueba que lo cubre: el primer aserto comprobaba que aparecía
`RACK_19_OREJA`, y pasaba **por el motivo equivocado** —ese texto sale también en el
prompt base, porque el catálogo de estándares se interpola en las reglas—. Se cambió
por la petición del propio ejemplar. Un aserto que puede pasar por casualidad no
comprueba nada.

### Presupuesto de tokens, medido *(5 de agosto de 2026)*

Estaba en 4.000 para el proveedor local. Con una imagen, el modelo local con visión
razona el plan **entero y correcto** y se queda sin sitio antes de escribir el JSON: la
respuesta se corta a mitad de un pensamiento y desde fuera parece que no sabe hacerlo.
Sube a 8.000 sin imagen y 12.000 con ella (16.000 en la nube). En local es memoria de
contexto, no dinero.

### Qué modelo usar

- **Texto**: `qwen3.6-35b-a3b`. La tarea no es razonar, es emitir JSON estricto contra
  un esquema largo sin inventarse un campo; eso premia seguimiento de instrucciones y
  contexto, y un MoE con 3B activos lo da rápido. Si se atasca en piezas compuestas,
  `qwen3.6-27b` denso.
- **Imagen**: `bonsai-ternary-27b`, que es el único del stack con `mmproj`.
- **Piezas difíciles**: OpenCode Go.

Queda dicho que **solo se ha medido bonsai**. Lo demás es criterio sobre la tarea, no
dato. Lo que falta para convertirlo en dato es un banco: una lista de peticiones con
asertos comprobables —número de agujeros, separación, cotas, estanqueidad— corrida
contra cada modelo por la cadena real. La maquinaria ya está toda (`Interprete`,
`RevisorDeGeometria`, `Exportador`); falta el arnés y el cliente HTTP.

### Decisión sobre imagen → 3D *(5 de agosto de 2026)*

No se va a reconstruir malla desde imagen (Meshy, Tripo, Hunyuan3D y compañía). Esa
salida es un blob orgánico sin cotas, sin estanqueidad garantizada y sin caras
planas: Yunkil no podría ni verificarlo ni editarlo paramétricamente, y todo el foso
—conversación editable más fabricación verificada— se cae. Eso ya lo hace un
navegador gratis y mejor.

Lo que sí se hace es **la imagen como referencia para modelar paramétricamente**: un
modelo de visión lee la foto o el boceto y emite *operaciones*, y sale una pieza con
cotas, editable, y pasada por el mismo crítico y el mismo analizador FDM.

El problema real de ese camino es que **una foto no tiene escala**: el mismo soporte
puede ser de móvil o de camión. La solución no es que el modelo adivine milímetros
—siempre fallará— sino que saque **proporciones** de la imagen y la persona dé **una
medida conocida**. `acotar` es exactamente esa pieza, y ya está.

El camino de imagen ya está montado de punta a punta:

- `Vocabulario.instruccionesDeImagen` y `Editor.instruccionesParaModeloConImagen`
  añaden las reglas de imagen **sobre** el prompt de siempre, no en su lugar: la
  geometría no cambia por mirar una foto, cambia de dónde sale la información.
- `ImagenDeReferencia` (en `AIService.swift`) reduce siempre la foto a 1024 px y la
  pasa a JPEG. No es una optimización: doce megapíxeles en base64 son seis millones
  de caracteres y el modelo local tiene 16K de contexto. Sin reducir no falla «lento»,
  falla con un error del servidor que no menciona la imagen por ningún lado.
- `AITransport` manda partes de contenido en los dos dialectos —`image_url` para el
  compatible con OpenAI, `source/base64` para el de Anthropic— y solo cuando hay
  imagen; sin ella el contenido sigue siendo una cadena suelta.
- La tarjeta «Crea describiendo» tiene botón de adjuntar, el nombre del archivo, y al
  lado el campo de **medida real**, que es lo único que pone a escala lo que el modelo
  lee en la foto. Si falta, se avisa de que la pieza saldrá proporcionada pero a un
  tamaño cualquiera.
- La imagen viaja en **todas** las rondas de corrección. Quitarla al corregir dejaría
  al modelo arreglando de memoria una geometría que ya no ve.
- Aviso —no candado— cuando el modelo local elegido probablemente no ve imágenes. Del
  stack instalado el único con `mmproj` es `bonsai-ternary-27b`.

**Probado contra el modelo local el 5/8/2026.** `bonsai-ternary-27b` lee un dibujo de
una escuadra en L y la describe bien —forma y número de agujeros— en unos 30 s con la
carga incluida. Con el prompt completo de Yunkil razona el plan entero y correcto,
pero **gasta muchísimos tokens razonando**: con 1500 de presupuesto no llegó a emitir
el JSON. La app da 4000 al proveedor local; conviene subirlo cuando haya imagen.
Falta una pasada completa imagen → plan aplicado → STL.

Y sigue sin verificarse que el shader de `BARRIDO` compile en Metal: el toolchain no
está instalado (`xcodebuild -downloadComponent MetalToolchain`). El código emitido es
el mismo patrón que `yk_perfil`, que sí compila en producción, y el buffer de uniforms
sí está cubierto por pruebas, pero eso no es lo mismo que haberlo compilado.

### Hecho el 4 de agosto de 2026

- **Analizador FDM** (`yunkil.fabricacion`): grosor de pared, detalle mínimo,
  voladizo, material sin apoyo, base y esbeltez, cada regla contra un perfil de
  impresora versionado. Cada aviso **nombra la pieza que lo causa** y trae
  correcciones ejecutables que entran en el historial.
- **Orientación óptima**: se malla una vez y se puntúan las candidatas girando las
  muestras, así que la búsqueda es instantánea.
- **Puente IA reescrito en el núcleo** (`yunkil.ia`): trece operaciones declarativas
  en vez de dos, contexto del documento con cotas reales en milímetros, intérprete
  tolerante a lo que escriben los modelos de verdad, y aplicación transaccional con
  un único punto de deshacer. El esquema que ve el modelo se **genera desde
  `TipoPieza`**, así que no puede divergir del validador.
- **Perfiles 2D** (`yunkil.kernel.Perfil2D`): contornos cerrados de líneas, arcos y
  bézier con SDF 2D, `EXTRUSION` y `REVOLUCION` como piezas paramétricas, y siete
  familias acotables —rectángulo, círculo, polígono, ranura, escuadra, estrella y
  contorno libre—. El shader los evalúa con un bucle de tope constante.
- **Bucle cerrado con el modelo** (`yunkil.ia.RevisionDePlan`, `RevisorDeGeometria`):
  el plan se ejecuta en un banco de pruebas aislado y **se mide lo que sale** antes
  de enseñárselo a nadie. Hasta ahora el modelo solo recibía respuesta cuando su
  JSON estaba mal, así que modelaba a ciegas: un plan impecable que dejaba la tapa
  flotando a 30 mm se aplicaba sin que nadie dijera nada.
  Se le devuelven tres cosas que antes no salían del núcleo: las operaciones que se
  cayeron al aplicar, los sólidos que no se tocan —el analizador FDM no puede verlo,
  porque con la base debajo eso es un voladizo del 90° y no una pieza suelta— y las
  diferencias cuyo sustraendo no llega a cortar. Si tras tres rondas sigue sin
  cuadrar, se aplica igual pero el diálogo enseña qué falla: bloquearlo dejaría al
  usuario sin nada y rompería los modelos de dos piezas hechos a propósito.
- **Primera operación de dominio: `taladro`** (`yunkil.fabricacion.Roscas`). El
  catálogo del kernel es geometría pura, así que «un agujero para un M3» obligaba al
  modelo a recordar que son 3,4 mm, calcular el largo para que atravesara y saber
  que una FDM cierra los agujeros. Fallaba en los tres sitios. Ahora dice `M3` y el
  núcleo pone el diámetro ISO 273, le suma la holgura del perfil activo y saca el
  cilindro 2 mm por cada cara. Por construcción no puede producir una resta que no
  corte, que era el fallo silencioso más caro del puente.
- **Bitácora de propuestas** (`yunkil.ia.Bitacora`): registro JSONL de qué propuso la
  IA y qué hizo la persona con ello —aplicada, descartada, o deshecha en los 45
  segundos siguientes, que es el «no» más rotundo—. No lo lee nadie todavía: es el
  único dato de calidad que no se puede fabricar sintéticamente ni reconstruir a
  posteriori, y sin él cualquier afinado futuro sería adivinar.
- **Segunda operación de dominio: `pared`**. El `VACIADO` del kernel es un cascarón
  centrado en la superficie, así que ahuecar una caja de 40 la dejaba en 41,6 sin que
  nadie se enterara. `pared` encoge la primitiva antes de ahuecarla y conserva las
  cotas; sin `grosor` pone el doble del mínimo del perfil, porque el mínimo *rellena*
  la pared pero no aguanta que le atornillen nada.
- **El taladro ya no anida**: cuatro agujeros daban cuatro `DIFERENCIA` encajadas y un
  nombre que crecía solo («Caja taladrada taladrada taladrada»). Ahora comparten una,
  que es el mismo sólido con un árbol que crece a lo ancho.
- **Prueba de la cadena completa** (`core/src/jvmTest`): plan de IA → revisión →
  aplicación → STL, comprobando el certificado. Una carcasa hueca con cuatro M3 sale
  estanca, con normales coherentes y 0,13 % de error de volumen.
- 161 pruebas del núcleo (eran 63).

### Fallo corregido el 4 de agosto de 2026

**Bug del checker `revisarTopologia` — se arregló el 4/8/2026.** El checker
saltaba los triángulos con área casi cero (slivers del contorneado dual en
esquinas vivas), y al saltarlos sus aristas perdían pareja: una malla topológicamente
cerrada salía como abierta. Se corrigió para que solo descarte triángulos con
índices inválidos/repetidos/vértices no finitos, no por área. La escuadra ELE
sin redondeo cierra correctamente y la prueba `CadenaCompletaTest` ya no lleva
`@Ignore`.

**Bug de «contorneado» con redondeo — se arregló el 5/8/2026, y no era del
contorneado.** El mallador estaba bien; el fallo estaba en `Extrusion`. El redondeo
de arista se **restaba** al perfil (`perfil.evaluar(...) - redondeo`) en vez de
sumarse, al revés que `Caja` y `Cilindro`. Dos consecuencias: la pieza salía dos
radios más ancha de lo pedido, y —lo que abría la malla— más ancha que sus propias
`cotas()`, que solo declaraban un radio de margen.

El mallador expande las cotas `2 · resolución` antes de muestrear, así que el error
cabía dentro del margen mientras la resolución era gruesa. En cuanto
`2 · resolución` bajaba del radio, la rejilla cortaba la pieza por la caja y salían
aristas abiertas: 1764 de 255816 en un rectángulo de 40×20×10 con `redondeo=1` a
0,15 mm. De ahí que solo se viera en el mallado fino del analizador FDM y que
pareciera un fallo del contorneado. Con razón descartar el salto de espacio libre no
cambió nada.

Arreglado en los tres sitios que tenían que moverse a la vez: `Sdf.kt` (el signo y
las cotas, que ahora no añaden margen porque el perfil va encogido), `MslGenerator.kt`
(el shader llevaba el mismo signo, así que la paridad CPU/GPU se mantiene) y el
retirado del apaño de resolución gruesa. Cubierto por `ExtrusionRedondeadaTest`
—cotas exteriores, la caja contiene el campo, y la malla fina cierra— y por una
escuadra con `redondeo=1.5` a 0,2 mm en `CadenaCompletaTest`, que sale estanca con
0,04 mm de desviación y 0,29 % de error de volumen.

Tres correcciones de fondo que salieron al hacerlo:

1. El grosor de pared se medía como «el campo por dos», y cerca de una arista el
   borde más cercano es la cara de al lado: un cubo macizo declaraba paredes de
   0,6 mm. Ahora se mide recorriendo el rayo normal.
2. El plato del viewport leía la cota mínima en **X** en vez de en **Y**, así que
   flotaba o cortaba cualquier pieza más ancha que alta.
3. El aplicador de planes leía como fallo el booleano de `anadir`, que en realidad
   significa «hay que recompilar el shader». Una operación vacía devuelve `false`
   siendo correcta, y eso hacía perder los alias y desmontar planes buenos.

### Decisiones de los perfiles

- **No hay solucionador de restricciones**, y se mantiene la decisión del DAFO: se
  acota con números y se colocan puntos con imanes. Un solucionador es un
  subsistema tan grande como el resto del programa.
- El contorno se evalúa como polígono. Las rectas salen exactas; arcos y bézier se
  teselan con una tolerancia de 0,01 mm y **la desviación se mide y se publica**
  (`desviacionDelPerfil`), en lugar de esconderse. Son 40 veces menos que una
  boquilla de 0,4.
- El signo sale de contar cruces, no del sentido de giro: un contorno escrito al
  revés no invierte el sólido.
- Los círculos compensan el radio (`r / cos(π/n)`) para que el error quede repartido
  a los dos lados. Un polígono inscrito sin compensar deja **todos** los agujeros
  pequeños y **todos** los cilindros finos, y ese sesgo se acumula en los encajes.

La prioridad y los gates competitivos se mantienen en
[docs/DAFO-COMPETITIVO.md](docs/DAFO-COMPETITIVO.md). Shapr3D es el estándar de
usabilidad; la ventaja buscada es conversación editable más fabricación verificada.

---

## Subproyecto 2 — analizador de fabricación y exportación

Es lo que convierte el juguete en herramienta. Sin esto, Yunkil es un visor
bonito.

### 2.1 Perfiles 2D *(primero, porque sin esto no se pueden hacer piezas reales)*

Polígonos, arcos y bézier tienen SDF 2D exacto; se extruyen o se revolucionan a
3D. Es la respuesta a la carencia de bocetos.

**Decidido**: acotación numérica directa sobre cada segmento más imanes de
ortogonalidad e igualdad. **Nada de solucionador de restricciones geométricas**:
es otro subsistema entero y las órdenes en lenguaje natural del subproyecto 3
cubren el caso que no llegue.

### 2.2 Mallado y exportación

- Contorneado dual sobre octree adaptativo, refinando cerca de la superficie y de
  las aristas vivas. Es el código más delicado que queda.
- Contorneado consciente de primitivas: los cilindros y planos exactos deben
  clavar sus vértices sobre la superficie analítica. **Los cilindros son el caso
  crítico**, porque son los agujeros de los encajes.
- **Verificación obligatoria antes de entregar el archivo**: cerrado y manifold,
  sin auto-intersecciones, normales coherentes, desviación máxima medida contra el
  campo exacto, y volumen de la malla contrastado con el volumen analítico. Si
  algo no cuadra, refinar y reintentar; si sigue fallando, decir qué y dónde. **No
  se entrega una malla que no pasa el examen.**
- El diálogo de exportar muestra la desviación: `desviación máx. 0,014 mm`.
  Convierte un riesgo invisible en un número visible.
- Formatos: STL binario y 3MF. **Los dos hechos.** El 3MF se escribe sin comprimir,
  así que ocupa unas tres veces lo que el STL equivalente; comprimirlo pide un deflate
  propio, porque el núcleo también corre en iPad y no puede depender de la JVM.

### 2.3 El analizador

Reglas sobre el campo, no sobre triángulos. Cada una cita su umbral y de qué
perfil sale.

| Regla | Cómo se calcula |
|---|---|
| Grosor de pared | Lectura directa: el campo por dos |
| Voladizos | Ángulo entre la normal (gradiente) y la dirección de impresión |
| Detalle mínimo | Comparación con el diámetro de boquilla |
| Base de apoyo | Área de contacto con el plato |
| Tolerancia de encaje | Sobre pares de superficies declaradas por el usuario |
| Estanqueidad | Garantizada por construcción: se informa, no se comprueba |

**Cada aviso lleva una corrección ejecutable.** «Voladizo 62° → [Achaflanar 5 mm]
[Rotar 40°]», y pulsarla aplica una operación real al documento. Como el
analizador ve el árbol paramétrico y no triángulos, sabe *qué cilindro* hizo ese
agujero y puede ajustarle el diámetro. Un laminador jamás podrá hacerlo. **Ahí
está el foso.**

Severidades honestas: `fallará` / `probable` / `mejorable`. Nunca gritar.
Silenciable por pieza, con memoria.

### 2.4 Perfiles y calibración

Perfiles de fabricación versionados y editables (boquilla, altura de capa,
material, impresora), con un conjunto verificado de partida.

**Modelo de calibración imprimible**: torre de voladizos y pasadores de
tolerancia que se imprimen, se miden y ajustan el perfil a la impresora real. Es
como ya trabaja la comunidad maker, y convierte los umbrales de «lo que dijo la
app» a «lo que mide tu máquina». Sin esto, un solo aviso falso destruye la
confianza para siempre.

### 2.5 Orientación óptima

Calcular la orientación de impresión que minimiza voladizos y maximiza la base, y
**dejarla aplicada en el 3MF exportado**, con una puntuación de imprimibilidad.
Es la función que la gente enseña a otros.

---

## Subproyecto 3 — puente LLM

El SDF se serializa a un DSL diminuto que un modelo puede escribir con fiabilidad,
cosa que no ocurre con B-rep ni con mallas. Y el analizador le da lo que ningún
text-to-CAD tiene: **un crítico con verdad de campo**.

```
usuario: "un soporte de móvil, 8 mm de grueso, inclinado 60°"
   → el modelo emite operaciones sobre el documento
   → el núcleo las valida y aplica
   → el analizador: "pared 0,9 mm < 1,2 mm mínimo; voladizo 71°"
   → los hallazgos vuelven al modelo automáticamente
   → corrige → converge (máximo 3 vueltas)
```

Reglas que no se negocian:

- **El modelo nunca ejecuta código.** Solo emite operaciones declarativas que el
  núcleo valida contra esquema antes de aplicar. La infraestructura ya está: son
  los mismos métodos de `Editor` que usa la interfaz.
- **Todo cambio entra en el historial**, así que deshacer siempre funciona.

Agnóstico de proveedor: interfaz `LlmClient` sobre Ktor en `commonMain`, con
adaptador compatible con OpenAI (cubre el stack local en `:9292`/`:9300`, Ollama,
LM Studio, vLLM) y adaptador Anthropic. **Por defecto el modelo local**: gratis,
privado y sin internet, que es un argumento de venta frente a todo lo que es nube
obligatoria.

Después, casi gratis: exponer las operaciones como servidor MCP para que
cualquier agente externo pueda modelar.

---

## Subproyecto 4 — puente Bambu Lab

- 3MF listo para producción con la orientación óptima aplicada y la placa montada.
- Envío por LAN a la impresora y seguimiento del estado en vivo dentro de Yunkil.
- En Mac, además: laminar invocando el CLI de Bambu Studio u Orca y mandar a
  imprimir sin salir de la app. En iPad, entrega del 3MF a Bambu Handy.

**Fuera de alcance, y quede dicho: no se escribe un laminador propio.** Es un
subsistema mayor que los otros cuatro juntos y hundiría el proyecto.

*Pendiente: confirmar los modelos exactos de impresora (¿P1S? ¿P1P? ¿X1C? ¿A1?).*

---

## iPad — lo que falta

El núcleo ya compila para `iosArm64` e `iosSimulatorArm64`. Lo que falta:

1. Objetivo de app iOS y XCFramework universal.
2. **Despacho por tiles.** Es el trabajo de verdad. Si un command buffer tarda
   demasiado, el vigilante de la GPU termina la aplicación. Sin trocear, la app se
   cae en iPad; no es una hipótesis.
3. Gestos táctiles: dos dedos para orbitar, pinza para acercar, tres para
   desplazar.
4. Interfaz adaptada: la piel táctil comparte el núcleo pero no el diseño de
   paneles. Objetivos de toque de 44 pt o más.
5. `xcodebuild -downloadPlatform iOS` para tener runtimes de simulador.

---

## Deudas de la aplicación de escritorio

Ordenadas por lo que más estorba al usar la app hoy:

- **Gizmos en el viewport.** Mover una pieza es teclear números; debería poder
  arrastrarse. Requiere selección por rayo contra el campo, que es fácil: se
  raymarchea el rayo del cursor.
- **Selección desde el viewport**: pulsar una pieza y que se marque en el árbol.
- **Reparentar arrastrando** en el árbol.
- **Duplicar pieza** (⌘D) y copiar/pegar.
- **Rejilla y plato de referencia** con escala en milímetros. Ahora no hay
  ninguna referencia de tamaño en pantalla, que para una herramienta de cotas es
  una carencia seria.
- Vistas predefinidas (planta, alzado, perfil) y proyección ortográfica.
- Renombrar desde el árbol con doble clic.
- Aviso al cerrar con cambios sin guardar.

## Deudas técnicas

- **Los identificadores de pieza usan un contador global** en `Pieza.companion`.
  Funciona con un documento a la vez y un solo hilo; hay que revisarlo antes de
  abrir varios documentos o de tocar el árbol desde otro hilo.
- `Editor` recompila el árbol entero en cada consulta de uniforms y de cotas. Con
  piezas grandes habrá que cachear por versión del documento.
- ~~El arnés de paridad se compila a mano con `swiftc`~~ — `scripts/comprobar.sh` lo
  lanza junto con el resto. Sigue sin ser un XCTest, pero ya no depende de que alguien
  recuerde el comando, que era lo que dejó el arnés roto sin que nadie lo viera.
- Falta una prueba automática de presupuesto de fotograma que falle si se supera
  el umbral, en Mac y en iPad.

---

## Lo que el banco midió, y lo que hay que hacer con ello

Medida real con `qwen3.6-35b-a3b` y el prompt nuevo, tras arreglar lo que el propio banco
destapó: **JSON válido 8/8, aplicado entero 8/8, geometría limpia 6/8, cumple lo pedido 6/8**,
con 12 de 24 rondas gastadas.

Y una advertencia sobre esos números antes de usarlos para nada: son **una tirada por caso**
de un modelo estocástico. A lo largo de tres tiradas la fila de geometría hizo 6 → 7 → 6, y
eso es ruido. Lo que se repite es el JSON 8/8 y, sobre todo, el **modo de fallo**. Para
comparar dos modelos hay que correr cada caso varias veces; ocho números sueltos sirven para
diagnosticar y no para decidir.

Lo que dice ese dato no es lo que se suponía:

- **El seguimiento de esquema no es el cuello de botella.** Ocho de ocho planes válidos a la
  primera contra un esquema largo. La hipótesis del documento de IA queda confirmada y deja
  de ser criterio.
- **El cuello de botella es el contacto entre piezas.** Los fallos de geometría son siempre
  el mismo: una parte colocada por coordenadas que se queda flotando o un sustraendo que no
  llega a cortar. El modelo acierta las cotas y falla el contacto, porque el contacto no
  está en los números que se le enseñan: está en el campo.
- **El aviso del revisor no bastaba, y ahora sí ayuda.** Tres rondas diciendo «usa "colocar"
  con la cara correspondiente» no arreglaban la pieza. Emitiendo la operación literal —con
  los nombres reales y la cara deducida de dónde está cada sólido— la geometría limpia subió
  de 6/8 a 7/8 en la misma tirada. Es la lección del catálogo de ejemplares otra vez.
- **Un aserto del banco estaba mal, no el modelo.** El primer caso pedía «con la tapa
  abierta» y medía el ancho del modelo entero; el modelo hizo la caja de 60 y una tapa aparte
  de 62, que es una respuesta legítima. Un aserto que castiga una solución correcta mide al
  aserto y no al modelo.
- **Y el banco no dejaba diagnosticar.** Volcaba 700 caracteres del plan, y con eso no se
  podía saber de dónde salían los 2 mm de más: reproducido a mano, el núcleo da 60,0
  exactos, así que el ancho lo añade una operación posterior que el volcado cortaba. Ahora
  vuelca el plan entero, las piezas que quedaron montadas y las cotas medidas.

### Y encontró un fallo del núcleo, que es para lo que sirve un banco

Las cotas de una resta con acuerdo estaban mal, y **no era cosmético**.

El modelo hizo una caja de 60 mm con el canto interior redondeado —`fusion` en la
DIFERENCIA, que es exactamente lo correcto— y el banco midió 62 × 27 × 42: uno de más por
cada lado. Reproducido a mano sin la fusión daba 60,0 exactos, así que el ancho no lo ponía
el modelo. Lo ponía `Diferencia.cotas()`, que expandía por la anchura de la mezcla con este
comentario: *«restar nunca añade material, así que las cotas de `a` bastan; la fusión sí
puede desbordarlas ligeramente»*.

La primera mitad era correcta y la segunda al revés. `smoothMax(a, −b, k)` es siempre
**mayor o igual** que `max(a, −b)`, y mayor significa *menos* material: una resta con
acuerdo solo puede quitar más, nunca añadir. Lo mismo en `Interseccion` y en el modo
DIFERENCIA e INTERSECCION de `AcuerdoLocal`.

El coste real: de esas cotas beben `acotar` para escalar el conjunto y el analizador para
muestrear. Con la caja declarando 2 mm más de los que mide, pedir «que tenga 60 de ancho»
dejaba la pieza en 58. Y nadie lo habría notado mirando la pantalla.

Cubierto por `CotasDeDiferenciaTest`, que además comprueba las dos direcciones: que las
cotas apretadas siguen conteniendo todo el material sobre 4.000 puntos y cuatro anchuras de
mezcla, y que **unir** con acuerdo sí las desborda y tiene que seguir declarándolo.

Siguiente con esto, en orden:

1. ~~Repetir cada caso N veces~~ — hecho el 6 de agosto por la noche: `:core:banco` acepta
   un tercer argumento con las vueltas y saca el desglose por caso.
2. ~~Cerrar el contacto~~ — hecho por la vía (a) y con la (b) dentro: la receta del revisor
   entra como operación ya interpretada (`coserPlan`) y el arreglo solapa el grosor de una
   pared en vez de dejar el contacto en cero. **Falta la medida**: correr el banco con el
   cosido puesto y ver si la geometría limpia sube de 6/8.
3. Correr el banco contra `qwen3.6-27b` denso, `bonsai-ternary-27b` y OpenCode Go, para que
   la recomendación de modelo de `docs/IA.md` sea dato en los cuatro casos y no en uno.
4. Enganchar la bitácora (`ia/Bitacora.kt`): hoy registra lo que hace la persona con cada
   propuesta y no lo lee nadie.
