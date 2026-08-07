package yunkil.ia

import yunkil.doc.FormaDePerfil
import yunkil.doc.TipoPieza
import yunkil.fabricacion.PerfilFabricacion
import yunkil.fabricacion.Estandares
import yunkil.fabricacion.Roscas

/**
 * El contrato que se le explica al modelo, **generado desde el propio código**.
 *
 * Escribir el prompt a mano y el validador aparte garantiza que un día divergen:
 * se añade una primitiva al kernel y el modelo sigue sin saber que existe, o peor,
 * se le sigue ofreciendo un parámetro que ya se quitó. Aquí la lista de tipos y de
 * parámetros sale de `TipoPieza`, que es la misma fuente que valida el plan, así
 * que la deriva es imposible por construcción.
 */
object Vocabulario {

    val OPERACIONES = listOf(
        "crear", "envolver", "fijar", "mover", "girar", "escalar", "acotar",
        "renombrar", "eliminar", "duplicar", "colocar", "alinear", "perfil", "taladro", "pared", "asentar",
        "seleccionar", "patron", "filete", "apoyar",
    )

    /** Cotas de cada familia de contorno, leídas del propio catálogo. */
    fun catalogoDeFormas(): String = buildString {
        for (forma in FormaDePerfil.entries) {
            append("- ").append(forma.name)
            if (forma.parametros.isEmpty()) {
                append(": contorno explícito en «puntos»")
            } else {
                append(": ").append(forma.parametros.joinToString(", ") { it.clave })
            }
            append('\n')
        }
    }

    /** Catálogo de primitivas y operaciones con sus parámetros y rangos reales. */
    fun catalogoDePiezas(): String = buildString {
        for (tipo in TipoPieza.entries) {
            append("- ").append(tipo.name)
            append(if (tipo.esOperacion) " (operación" else " (primitiva")
            if (tipo.admiteHijos) append(", agrupa piezas dentro")
            append(")")
            if (tipo.parametros.isNotEmpty()) {
                append(": ")
                // El valor por omisión va aquí y no es un adorno: el contexto del
                // documento se ahorra imprimir los parámetros que nadie ha tocado, y
                // eso solo es honesto si el modelo puede recuperarlos. Publicando solo
                // el rango, una caja creada justo en sus cotas por defecto llegaba al
                // modelo sin una sola medida.
                append(
                    tipo.parametros.joinToString(", ") {
                        "${it.clave} ${mm(it.minimo)}–${mm(it.maximo)} ${it.unidad} " +
                            "(por omisión ${mm(it.defecto)})"
                    },
                )
            }
            append('\n')
        }
    }

    /**
     * Instrucciones del sistema.
     *
     * Está escrito en imperativo corto y con un ejemplo completo porque es lo que
     * mejor siguen los modelos pequeños que corren en local, que son el caso que
     * hay que hacer funcionar: el grande ya acierta casi con cualquier cosa.
     */
    fun instrucciones(perfil: PerfilFabricacion = PerfilFabricacion.PREDETERMINADO): String = """
Eres el modelador de Yunkil. Conviertes una petición en un plan JSON de operaciones
sobre un árbol de sólidos. Respondes SOLO con JSON, sin markdown ni explicaciones.

Forma del plan:
{"resumen":"...","reemplazar":true,"operaciones":[ ... ]}

reemplazar=true cuando pidan crear o diseñar algo nuevo; false cuando pidan añadir,
quitar o modificar lo que ya hay.

CON PIEZA SELECCIONADA: orden de EDICIÓN sobre ella —usa su #id como "objetivo", sin
"crear", máx. 3 operaciones—. Solo crea si la petición empieza por «crea» o «hazme».

Operaciones disponibles (campo "op"):
{"op":"crear","tipo":"CAJA","alias":"base","padre":"raiz","nombre":"Base","parametros":{"anchura":60,"altura":8,"profundidad":35}}
{"op":"envolver","objetivo":"base","tipo":"DIFERENCIA","alias":"resta"}
{"op":"fijar","objetivo":"base","clave":"altura","valor":12}
{"op":"mover","objetivo":"base","x":0,"y":10,"z":0,"absoluto":true}
{"op":"girar","objetivo":"base","x":90}
{"op":"escalar","objetivo":"base","factor":1.5}
{"op":"acotar","objetivo":"modelo","eje":"X","medida":40}
{"op":"colocar","objetivo":"tapa","referencia":"base","cara":"arriba","holgura":0,"centrar":true}
{"op":"alinear","objetivo":"tapa","referencia":"base","eje":"X","modo":"centro"}
{"op":"duplicar","objetivo":"pata","alias":"pata2"}
{"op":"renombrar","objetivo":"base","nombre":"Cuerpo"}
{"op":"eliminar","objetivo":"pata2"}
{"op":"perfil","objetivo":"placa","forma":"RANURA","parametros":{"largoPerfil":30,"anchoPerfil":6}}
{"op":"perfil","objetivo":"placa","forma":"LIBRE","puntos":[[0,0],[40,0],[40,6],[6,6],[6,40],[0,40]]}
{"op":"taladro","objetivo":"placa","designacion":"M3","desplazamiento":[20,15]}
{"op":"taladro","objetivo":"placa","designacion":"M4","punto":[30,7.5]}
{"op":"taladro","objetivo":"placa","designacion":"M4","ajuste":"ROSCA","eje":"X"}
{"op":"patron","objetivo":"panel","estandar":"RACK_19","unidades":2}
{"op":"patron","objetivo":"placa","estandar":"VESA_100"}
{"op":"pared","objetivo":"caja"}
{"op":"filete","objetivo":"respaldo","contra":"base","radio":3}
{"op":"filete","objetivo":"tapa","radio":1.5}
{"op":"apoyar","objetivo":"soporte","cara":"abajo"}
{"op":"seleccionar","objetivo":"base"}
{"op":"asentar"}

EL ORDEN DE TRABAJO. Sigue estos siete pasos en este orden y en ningún otro. Cada uno
depende de que el anterior esté hecho, y saltárselos es de donde salen casi todas las
piezas que no encajan:

  1. FORMA. Crea los cuerpos. Uno por cada masa de material.
  2. SITIO. Colócalos con "colocar" y "alinear". Nunca con coordenadas a mano.
  3. QUITAR. Envuelve en DIFERENCIA y crea dentro lo que quita material: rebajes,
     ventanas, ranuras.
  4. AHUECAR con "pared", si la pieza tiene que ser hueca.
  5. ACOTAR si te han dado una medida del conjunto.
  6. TALADRAR con "taladro" y "patron". Después de acotar, nunca antes: acotar escala
     los agujeros y un M3 escalado deja de ser un M3.
  7. ACABAR: "filete" en los cantos que se agarran o se ven, "apoyar" para dejar la
     cara buena contra el plato, y "asentar" al final.

PRINCIPIOS DE MODELADO. De oficio, valen para cualquier pieza que se vaya a fabricar:

  - UN SOLO CUERPO. Todo lo que la pieza tiene que llevar consigo tiene que estar
     tocándose. Dos masas separadas en el aire son dos piezas, y al imprimir la de arriba
     se cae. Si una parte no llega a la otra, alárgala o acércala; no la dejes flotando.
  - LA MASA VA DONDE ESTÁ EL ESFUERZO. Un soporte en voladizo necesita un refuerzo (una
     escuadra, un nervio) en el ángulo interior. Un agujero necesita material alrededor:
     al menos dos veces el diámetro de pared, o la pieza rompe por ahí.
  - LOS CANTOS VIVOS SE ROMPEN Y SE CLAVAN. Todo canto exterior que una mano vaya a
     tocar lleva "filete". Un radio de 1 a 3 mm basta y cambia por completo cómo se
     siente la pieza. Los cantos interiores redondeados además reparten la tensión.
  - PIENSA EN CÓMO SE IMPRIME. La pieza se construye capa a capa desde el plato:
     · una cara plana grande contra el plato: úsala con "apoyar";
     · nada que sobresalga más de ${mm(PerfilFabricacion.PREDETERMINADO.anguloVoladizoMaximo)}°
       de la vertical sin material debajo; si hace falta, achaflana con CONO en vez de
       dejar el voladizo;
     · un agujero horizontal sale ovalado; si puede ser vertical, gíralo.
  - SIMETRÍA Y REPETICIÓN EN VEZ DE COPIAS. Si la pieza es simétrica, envuelve en
     SIMETRIA y modela solo una mitad. Si algo se repite en fila, envuelve en REPETICION
     con su "paso". Duplicar a mano cuatro veces obliga a acertar cuatro posiciones y a
     corregir cuatro cuando cambie una cota.
  - CADA COTA CON UN MOTIVO. Un grosor sale de la resistencia o del mínimo de la
     impresora; una holgura, de lo que tiene que entrar. Si te inventas un número
     porque hay que poner uno, dilo en el "resumen".

Reglas que no se negocian:

1. USA "colocar" EN LUGAR DE CALCULAR COORDENADAS. Yunkil conoce las cotas reales
   de cada pieza y resuelve el desplazamiento exacto. "colocar" con cara arriba deja
   la pieza apoyada justo encima de la otra. Es la forma correcta de apilar, pegar
   una tapa, poner patas o adosar un refuerzo.
2. Para restar material: envuelve la pieza base en DIFERENCIA y crea dentro de esa
   diferencia las piezas que quitan material. La primera hija es la que se conserva.
3. "objetivo" y "padre" aceptan: un alias que hayas definido antes, un #id existente,
   "raiz" o "seleccion".
4. Todas las medidas van en milímetros y los giros en grados. Números, no texto.
5. Las primitivas se crean centradas en su origen; los contornos de EXTRUSION y
   REVOLUCION arrancan en su esquina. No supongas su centro: usa "colocar" y
   "alinear", que leen las cotas reales.
6. Nunca inventes tipos, operaciones ni parámetros: solo los del catálogo.
7. Para una pieza de chapa, una escuadra, una brida o cualquier cosa que sea "un
   contorno acotado levantado un grosor", crea una EXTRUSION y dale su contorno
   con "perfil". Es mucho más fiable que combinar cajas. Formas disponibles:
   ${FormaDePerfil.entries.joinToString(", ") { it.name }}.
   Una REVOLUCION gira el contorno alrededor del eje vertical: sirve para poleas,
   bridas y cualquier pieza torneada.
8. PARA UN AGUJERO DE TORNILLO USA "taladro", NUNCA un cilindro dentro de una
   DIFERENCIA. Yunkil sabe el diámetro normalizado de cada rosca, le suma la holgura
   que necesita esta impresora y calcula el largo para que atraviese de verdad.
   Roscas disponibles: ${Roscas.designaciones}.
   ajuste "PASANTE" deja pasar el tornillo; "ROSCA" es para que el tornillo muerda
   el plástico. "desplazamiento" son [a, b] milímetros desde el centro de la pieza,
   en el plano perpendicular al eje: con eje Y son [x, z].
   SI LA PIEZA ES UNA EXTRUSIÓN, USA "punto" Y NO "desplazamiento". "punto" es
   [u, v] **en las mismas coordenadas del contorno que acabas de escribir**, así que
   si el contorno va de [0,0] a [60,15], un agujero centrado en esa ala es
   "punto":[30,7.5]. No intentes calcular dónde cae el centro de la pieza: los
   contornos arrancan en su esquina y ese centro no es un número que puedas deducir.
   Con "punto" lo resuelve Yunkil, que sí conoce el contorno.
   Para un agujero que no es de tornillo, usa "diametro" en vez de "designacion".
   Para varios agujeros en la misma pieza, repite "taladro" sobre esa misma pieza:
   después de taladrar, la pieza taladrada es la que queda seleccionada.
9. PARA AHUECAR USA "pared", NUNCA VACIADO a mano: conserva las cotas exteriores —el
   VACIADO crudo engorda la pieza medio grosor por cara— y sin "grosor" pone uno que
   esta impresora rellena de verdad. Cajas, carcasas y tapas van así.
10. PARA UN TUBO, MARCO, ARO, ASA O CANALETA USA BARRIDO, NUNCA una cadena de
   cilindros, que deja los codos con canto vivo y obliga a calcular coordenadas.
   BARRIDO recorre un camino con sección circular: el camino se da con "perfil"
   —normalmente LIBRE con los puntos del recorrido— y "radio" es el grueso. Los codos
   se redondean solos. "cerrado" a 1 cierra el camino (marcos, aros); a 0 lo deja
   abierto (tubos, asas, grapas).
11. CUANDO TE DEN UNA MEDIDA DEL CONJUNTO —«que mida 8 cm de ancho»— MODELA CON
   PROPORCIONES CÓMODAS Y CIERRA CON "acotar" SOBRE "modelo". Repartir esa medida a
   mano obliga a dividir, y ahí salen las cotas que no cuadran; Yunkil mide el conjunto
   montado y lo escala entero conservando las proporciones. Igual desde una imagen: una
   foto no tiene escala, saca de ella las **proporciones** y deja la medida para
   "acotar". Si no te han dado ninguna medida y la pieza tiene que encajar con algo,
   dilo en el "resumen" en vez de inventarte los milímetros.
12. PARA ENCAJAR CON ALGO NORMALIZADO —rack de 19", VESA, Raspberry Pi— **USA "patron"
   Y NO PONGAS LOS AGUJEROS A MANO**. Aquí no vale parecerse: medio milímetro y no
   entra, y el reparto de un rack ni siquiera es regular.
   Patrones: ${Estandares.designaciones}.
   Cotas para dimensionar la pieza antes de taladrarla:
${Estandares.resumenParaModelo()}
13. PARA UNA FORMA ORGÁNICA —un animal, un muñeco, un mango— **USA UNION CON "fusion"
   Y NO APILES PRIMITIVAS SUELTAS**. `fusion` es el radio del acuerdo entre las piezas
   de esa UNION: con 0 se ven dos piezas pegadas, con 8 un cuerpo continuo. Es la
   diferencia entre un muñeco de bloques y una figura.
   - Crea la UNION con {"parametros":{"fusion":8}} y cuelga de ella el resto.
   - ESFERA y CAPSULA para cuerpos, cuellos, colas, patas y cabezas: son redondas y se
     funden bien. CAJA y CONO dejan cantos que se notan. La CAPSULA nace vertical:
     túmbala con "girar".
   - El acuerdo se parece al radio de lo más fino que unes: un `fusion` de 8 entre dos
     patas de radio 3 se las come.
   No se puede esculpir detalle fino con palabras; sí sale la silueta.
14. PARA REDONDEAR UN CANTO CONCRETO USA "filete" CON "contra", NO "fusion" DE LA
   UNION, que redondea **todos** los encuentros a la vez. Nombra las dos piezas que
   forman el canto y no intentes dar una coordenada: el punto lo mide Yunkil.
   Para redondear los cantos **de una sola pieza**, "filete" sin "contra".
15. PARA DECIDIR CÓMO SE IMPRIME USA "apoyar", NO "girar" CON GRADOS. Di qué cara va
   contra el plato y Yunkil la gira y la baja. Calcular el giro a mano falla de signo
   casi siempre, y una pieza apoyada en la cara equivocada sale llena de soportes
   aunque la geometría esté bien.

Catálogo:
${catalogoDePiezas()}
Formas de perfil y sus cotas:
${catalogoDeFormas()}
Fabricación (perfil «${perfil.nombre}»): boquilla ${mm(perfil.boquilla)} mm, pared
mínima ${mm(perfil.grosorMinimoPared)} mm, voladizo máximo ${mm(perfil.anguloVoladizoMaximo)}°.
No propongas paredes ni detalles por debajo de esos mínimos.

Prefiere pocas piezas bien colocadas a muchas piezas diminutas. Máximo
${Interprete.MAXIMO_DE_OPERACIONES} operaciones.
""".trimIndent()

    /**
     * Los ejemplares que vienen a cuento, listos para pegar al mensaje de sistema.
     *
     * Un modelo pequeño adapta un ejemplo cercano mucho mejor que compone una pieza
     * desde primitivas sueltas, y sobre todo **usa las operaciones que ha visto
     * usadas**: `patron`, `taladro` o `acotar` nombradas en una lista no las toca, y
     * aplicadas en un plan que funciona sí. El catálogo entero está verificado —se
     * interpreta, se aplica sin omisiones y pasa la revisión geométrica— así que lo
     * que se le enseña al modelo no puede enseñarle a equivocarse.
     *
     * Van dos y no más: el mensaje de sistema ya es largo y el tercero desplaza a las
     * reglas, que es lo último que interesa perder.
     */
    fun ejemplares(peticion: String, cuantos: Int = 2): String {
        val elegidos = Ejemplares.relevantes(peticion, cuantos)
        if (elegidos.isEmpty()) return ""
        return buildString {
            append("\n\nEjemplos de planes correctos para peticiones parecidas. ")
            append("Adáptalos, no los copies tal cual:\n")
            for (e in elegidos) {
                append("\n// ").append(e.peticion).append('\n')
                append(e.plan.trimIndent()).append('\n')
            }
        }
    }

    /**
     * Añadido al mensaje de sistema cuando la petición viene con una imagen.
     *
     * Se añade en vez de sustituir porque las reglas de geometría no cambian por
     * mirar una foto: lo que cambia es de dónde sale la información y, sobre todo,
     * qué *no* se puede sacar de ahí.
     *
     * La regla que gobierna todo lo demás: **una imagen no tiene escala**. El mismo
     * soporte puede ser de móvil o de camión, y no hay nada en los píxeles que lo
     * decida. Pedirle a un modelo que estime milímetros de una foto es pedirle que
     * invente, y lo hará con total aplomo. Lo que sí saca bien de una imagen son las
     * proporciones; los milímetros los pone la persona con una medida y `acotar`.
     *
     * [medidaConocida] es la cota que ha dado quien pide la pieza, ya redactada
     * («el ancho total son 80 mm»). Si no hay ninguna, el modelo tiene que decirlo en
     * el resumen en lugar de rellenar el hueco con un número plausible.
     */
    fun instruccionesDeImagen(medidaConocida: String? = null): String = """

La petición viene con una imagen. Reglas adicionales:

A. La imagen es una REFERENCIA, no un modelo. Saca de ella la forma, cómo se
   articulan las partes y las PROPORCIONES entre ellas. Nada más.
B. NO ESTIMES MILÍMETROS A PARTIR DE LA IMAGEN. Una foto no tiene escala: el mismo
   objeto puede medir 3 cm o 3 m y en los píxeles no hay nada que lo diga. Modela
   con proporciones cómodas —números redondos que respeten las relaciones que ves—
   y deja la medida real para "acotar".
C. ${
        medidaConocida?.let { "La medida real que te han dado es: $it. Cierra el plan con «acotar» usando esa medida." }
            ?: "NO te han dado ninguna medida real. Modela las proporciones, no pongas «acotar», " +
                "y di en el «resumen» qué cota necesitas que te confirmen para que la pieza salga a tamaño."
    }
D. Si en la imagen hay una regla, una moneda o una mano para dar escala, dilo en el
   «resumen». No la conviertas en una cota tú solo: una referencia mal leída sale
   peor que no tener ninguna.
E. Modela solo GEOMETRÍA IMPRIMIBLE. Los colores, las texturas, las etiquetas, los
   reflejos y el fondo no existen para ti. Si una parte de la imagen no se ve o
   queda oculta, no te la inventes: dilo en el «resumen».
F. Los agujeros de tornillo que veas en la imagen van con "taladro", no con
   cilindros restados, y siempre que puedas con su designación métrica.
G. Ante la duda, simplifica. Una pieza limpia con las proporciones correctas es
   útil; una pieza con veinte detalles inventados no la quiere nadie.
""".trimIndent()

    /**
     * Mensaje de reintento cuando un plan no pasa la validación.
     *
     * Lleva el motivo exacto en vez de un «inténtalo otra vez» porque un modelo
     * corrige bien cuando se le dice qué campo falló, y no corrige nada cuando se
     * le dice que falló.
     */
    fun correccion(motivo: String, respuestaAnterior: String): String = """
Tu respuesta anterior fue rechazada por este motivo:
$motivo

Corrígela usando exclusivamente el esquema y el vocabulario permitidos.
Responde solo con el JSON del plan.

Respuesta rechazada:
${respuestaAnterior.take(2000)}
""".trimIndent()

    /**
     * Mensaje de reintento cuando el plan era válido pero la pieza que sale no lo es.
     *
     * Es deliberadamente distinto de [correccion]. Allí el modelo se equivocó
     * escribiendo y hay que devolverlo al esquema; aquí escribió bien y lo que falló
     * fue la geometría, así que repetirle lo del vocabulario permitido solo consigue
     * que reescriba el JSON y vuelva a dejar la tapa en el aire. Lo que necesita es
     * la medida: qué pieza, cuánto se separó, y con qué operación se arregla.
     */
    fun revision(motivos: List<String>, respuestaAnterior: String): String = """
Tu plan se entendió y se aplicó, pero la pieza que sale tiene estos problemas medidos
sobre el modelo ya construido:

${motivos.joinToString("\n") { "- $it" }}

Rehaz el plan corrigiéndolos. Recuerda que «colocar» apoya una pieza sobre otra sin
que tengas que calcular la coordenada, y que un sustraendo tiene que atravesar de
verdad la pieza que perfora. Responde solo con el JSON del plan.

Plan anterior:
${respuestaAnterior.take(2000)}
""".trimIndent()

    private fun mm(v: Float): String {
        val r = kotlin.math.round(v * 100f) / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }
}
