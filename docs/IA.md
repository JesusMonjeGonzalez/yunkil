# IA en Yunkil

## El banco *(6 de agosto de 2026)*

`./gradlew :core:banco` corre ocho peticiones reales contra el modelo local por la cadena
real —interpretar, revisar en un banco aislado, hasta tres rondas de corrección, aplicar— y
mide **asertos sobre el documento resultante**, no sobre el texto que escribió el modelo:
que la escuadra salga de un contorno y no de cajas apiladas, que la oreja de rack quepa en
un plato de 256 mm, que el tubo sea un `BARRIDO`, que el ancho acotado mida lo pedido.

Existe porque hasta ahora la elección de modelo era **criterio y no dato**. «Un MoE con 3B
activos sigue instrucciones bien» es una hipótesis razonable; «de 8 peticiones sacó 8 planes
válidos y 5 piezas correctas» es un número con el que se puede trabajar.

### Medida real: `qwen3.6-35b-a3b`

Tres tiradas, con una corrección del sistema entre cada una:

| | primera | con receta literal | con las cotas arregladas |
|---|---|---|---|
| Respondió | 8/8 | 8/8 | 8/8 |
| JSON válido contra el esquema | **8/8** | **8/8** | **8/8** |
| Se aplicó entero, sin omisiones | 8/8 | 8/8 | 8/8 |
| Geometría limpia | 6/8 | 7/8 | 6/8 |
| Cumple lo pedido | 5/8 | 5/8 | **6/8** |
| A la primera, sin corregir | 5/8 | 5/8 | **6/8** |

**Cuidado con leer estas columnas como una medición controlada: son una tirada por caso de
un modelo estocástico.** La fila de geometría hace 6 → 7 → 6, y eso es ruido, no una
regresión: el mismo plan no sale dos veces igual ni a temperatura 0,2. Lo que sí es señal es
lo que se repite en las tres tiradas —el JSON válido 8/8— y **el modo de fallo**, que es
siempre el mismo. Para que las cifras signifiquen algo hay que correr cada caso varias veces
y quedarse con la proporción; ocho números sueltos sirven para diagnosticar, no para comparar
dos modelos entre sí.

Las peticiones que fallan lo hacen por lo mismo, y ese es el hallazgo: **el contacto entre
piezas**, no las cotas. En el soporte de móvil la separación bajó de 22 mm a 0,9 mm a lo
largo de las rondas —el modelo converge, pero no cierra— y en la base de Raspberry Pi un
separador duplicado se queda a 50 mm de lo que tenía que perforar.

Lo que dice este dato, y no lo que se suponía:

- **El seguimiento de esquema no es el problema.** 8 de 8 planes válidos a la primera contra
  un esquema largo. La hipótesis de que un MoE pequeño acertaría el JSON queda confirmada.
- **El problema es el contacto entre piezas.** Los fallos de geometría son siempre de esa
  familia: una parte colocada por coordenadas que se queda flotando, o un sustraendo que no
  llega a lo que tenía que perforar. El modelo acierta las cotas y falla el contacto, porque
  el contacto no está en los números que se le enseñan: está en el campo. Es exactamente lo
  que `RevisorDeGeometria` existe para cazar, y por eso el bucle de corrección no es un
  adorno.
- **El aviso describía en vez de dictar.** Tres rondas diciéndole «usa "colocar" con la cara
  correspondiente» y la pieza seguía suelta. Ahora emite la operación **literal**, con los
  nombres reales y la cara deducida de dónde está cada sólido:
  `{"op":"colocar","objetivo":"Base","referencia":"Respaldo","cara":"arriba"}`. Es la misma
  lección que ya costó una vez con el catálogo de ejemplares: una operación descrita no se
  usa; una operación vista escrita, sí. Que la tirada siguiente no subiera el contador no
  desmiente la regla —una tirada no mide nada—, pero tampoco la confirma: falta correr los
  casos varias veces.
- **Un aserto del banco estaba mal, no el modelo.** El primer caso pedía una caja «con la
  tapa abierta» y medía el ancho del modelo entero; el modelo hizo la caja de 60 y una tapa
  aparte, que es una respuesta legítima, y el banco lo contaba como fallo. Un aserto que
  castiga una solución correcta no mide al modelo, mide al aserto. Corregido a «sin tapa».
- **Y luego apareció un fallo del núcleo, que es para lo que sirve un banco.** Con el aserto
  arreglado la caja seguía midiendo 62: `Diferencia.cotas()` expandía por la anchura del
  acuerdo, y una resta con acuerdo **solo puede quitar material, nunca añadir**. De esas
  cotas beben `acotar` y el analizador, así que pedir «60 de ancho» dejaba la pieza en 58.
  Arreglado en `Diferencia`, `Interseccion` y los modos correspondientes de `AcuerdoLocal`,
  con `CotasDeDiferenciaTest` cubriendo las dos direcciones. Ninguna prueba anterior lo
  cazaba porque todas comprobaban que las cotas **contienen** el campo, y unas cotas de más
  lo contienen igual de bien.

Queda pendiente medir el resto del stack con el mismo banco —`qwen3.6-27b` denso,
`bonsai-ternary-27b` y OpenCode Go— para que la elección de modelo del documento de abajo
deje de ser criterio en todos los casos y no solo en este.

## Selección

El proveedor y modelo se eligen en la tarjeta `Crea describiendo`.

- Local rápido: `Qwen3.5 9B`.
- Go económico: `DeepSeek V4 Flash` o `MiMo V2.5`.
- Go equilibrado: `DeepSeek V4 Pro`.
- Go razonamiento: `GLM-5.2`.
- Go máxima capacidad: `Kimi K3` o `Qwen3.8 Max`.

## Imágenes *(en curso)*

La imagen se usa como **referencia para modelar**, no para reconstruir una malla.
El modelo de visión lee la foto o el boceto y emite operaciones, así que sale una
pieza con cotas, editable y pasada por el crítico y el analizador FDM. Reconstruir
malla desde imagen daría un blob sin cotas ni estanqueidad que Yunkil no podría ni
verificar ni editar, y eso es exactamente lo que Yunkil no es.

La regla que gobierna el flujo: **una foto no tiene escala**. El modelo saca de la
imagen las proporciones; los milímetros los pone la persona con una medida conocida,
y `acotar` escala el conjunto entero conservando esas proporciones. Si nadie da una
medida, el modelo tiene que decir qué cota necesita en vez de inventarla.
El mensaje de sistema correspondiente es `Vocabulario.instruccionesDeImagen`.

Modelos con visión disponibles:

- Local: **`Ternary-Bonsai-27B`**, el único del stack con `mmproj`. 16K de contexto,
  visión y herramientas, ~12 GB entre modelo, draft y proyector. El resto de los GGUF
  instalados son solo texto.
- OpenCode Go: los modelos multimodales de la cuenta conectada.

Falta el envío de la imagen desde `AIService.swift`, que hoy solo manda texto.

## Privacidad

El modo local no sale del Mac. OpenCode Go envía el texto de la petición y un
resumen estructural del documento. No envía STL, capturas ni archivos del usuario.
OpenCode Go consume la cuota de la cuenta conectada.

Si falta la credencial, ejecutar `/connect` en OpenCode y seleccionar
`OpenCode Go`. Yunkil solo lee la clave en el momento de la petición y nunca la
muestra en UI o logs.

## Bitácora de propuestas

Cada propuesta de la IA se apunta en `~/Library/Application Support/Yunkil/propuestas.jsonl`
con lo que se pidió, el plan que emitió el modelo, cuántas rondas de corrección hizo
falta y qué se hizo con ella: aplicada, descartada, o deshecha en los 45 segundos
siguientes. Es un archivo de texto local, una línea por propuesta, que no sale del
equipo y se puede borrar en cualquier momento sin que nada deje de funcionar.

Está ahí porque un plan válido no es un plan bueno, y lo único que distingue las dos
cosas es si la persona se quedó con la pieza. Ese dato solo existe mientras ocurre.

## Solución de problemas

- `Hearthia no responde`: iniciar el gateway local en el puerto 9292.
- `Falta conectar OpenCode Go`: completar `/connect` en OpenCode.
- `Plan no aplicable`: el mensaje muestra la operación o tipo rechazado; Yunkil ya
  habrá solicitado una corrección automática.
- `Comprobando la pieza…`: el plan ya es válido y Yunkil lo está ejecutando en un
  banco de pruebas aislado para medir el resultado. Si algo no cuadra —piezas que no
  se tocan, una resta que no corta, operaciones que se cayeron— se le devuelve al
  modelo con la medida concreta y se reintenta, hasta tres rondas.
- `⚠︎ La pieza propuesta tiene problemas sin resolver`: el modelo agotó las rondas
  sin arreglarlo. La propuesta se puede aplicar igual —hay modelos de varias piezas
  sueltas que son correctos a propósito— pero conviene mirar lo que lista el aviso.
- Los objetos compuestos usan un presupuesto mayor en OpenCode Go. La petición
  puede tardar hasta tres minutos mientras el modelo razona y emite el JSON final.
- `Documento cambió`: repetir la petición sobre el estado actual.
- Una propuesta nunca debe aplicarse sin el diálogo de confirmación.
