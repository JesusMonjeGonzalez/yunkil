package yunkil.ia

import yunkil.doc.FormaDePerfil
import yunkil.doc.TipoPieza

/**
 * Una operación del plan contada en español, con el índice que la señala.
 *
 * El índice es lo que permite marcar y desmarcar operaciones sueltas: la interfaz
 * enseña estas líneas y devuelve los índices que el usuario aceptó, sin volver a
 * mirar el JSON del modelo. [depende] son los índices de las operaciones sin las
 * cuales esta no tiene sentido, para que desmarcar una arrastre a las que la usan.
 */
data class LineaExplicada(
    val indice: Int,
    val texto: String,
    val depende: List<Int> = emptyList(),
)

/**
 * Cuenta un plan en español antes de aplicarlo.
 *
 * Existe porque hasta ahora el plan se aplicaba y el usuario descubría lo que había
 * pedido mirando el viewport. El resumen que escribe el modelo no sirve para eso: es
 * una frase suya sobre lo que cree que hizo, y justo el caso que hay que cazar —el
 * plan que dice una cosa y hace otra— es el que ese resumen no distingue. Esto lee
 * las operaciones.
 *
 * Es una **función pura sobre el plan**: no toca el documento, no necesita modelo y
 * no puede fallar. Por eso se prueba entera sin arrancar nada. La contrapartida, y
 * queda dicha: las cotas que salen aquí son **las que pide el plan**, no las que
 * medirá el núcleo después. Un `acotar` posterior las cambia y esto no lo sabe; para
 * lo medido está el analizador.
 */
object Explicacion {

    fun de(plan: PlanDeModelado): List<LineaExplicada> {
        val nombres = Registro()
        return plan.operaciones.mapIndexed { i, op ->
            LineaExplicada(i, contar(op, nombres), nombres.dependenciasDe(op))
        }
    }

    /** El plan entero en un bloque de texto, una línea por operación. */
    fun texto(plan: PlanDeModelado): String =
        de(plan).joinToString("\n") { it.texto }

    /**
     * Quita de lo marcado todo lo que se quedaría hablando de una pieza que no existe.
     *
     * Es lo que hace del aceptar parcial una función y no una trampa. Si el usuario
     * descarta el `crear` de la tapa y deja marcado el `colocar` que la apoya sobre la
     * base, el aplicador no revienta: **omite** esa operación y sigue, que es el modo
     * de fallo silencioso que todo el bucle de revisión existe para evitar. Aquí se
     * decide antes, y se puede contar por qué.
     *
     * Es transitiva: cae el `crear`, cae el `colocar` que lo nombra, y cae el `filete`
     * que redondea el canto que el `colocar` producía.
     */
    fun podar(plan: PlanDeModelado, marcadas: Set<Int>): Set<Int> {
        val lineas = de(plan)
        var vivas = marcadas.filter { it in lineas.indices }.toMutableSet()
        while (true) {
            val caen = vivas.filter { i -> lineas[i].depende.any { it !in vivas } }
            if (caen.isEmpty()) return vivas
            vivas = (vivas - caen.toSet()).toMutableSet()
        }
    }

    /**
     * Añade a lo marcado todo lo que hace falta para que se sostenga.
     *
     * La otra mitad de [podar], y la que usa la interfaz al **marcar**: pedir el
     * taladro de una pieza que aún no se crea es pedir también su creación. Sin esto,
     * marcar una casilla haría desaparecer la marca por obra de [podar] y parecería
     * que la casilla está rota.
     */
    fun completar(plan: PlanDeModelado, marcadas: Set<Int>): Set<Int> {
        val lineas = de(plan)
        val vivas = marcadas.filter { it in lineas.indices }.toMutableSet()
        while (true) {
            val faltan = vivas.flatMap { lineas[it].depende }.filter { it !in vivas }
            if (faltan.isEmpty()) return vivas
            vivas += faltan
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Quién es cada alias y en qué operación nació.
     *
     * Sin esto, un plan real se lee como jeroglífico: el modelo llama `b1` a la base
     * y a partir de ahí todas las operaciones hablan de `b1`. Guardando el nombre
     * legible se cuenta «Redondea el canto entre Base y Tapa» en vez de «entre b1 y
     * t». Y de paso sale gratis el grafo de dependencias, que es el mismo dato: quien
     * nombra un alias depende de quien lo creó.
     */
    private class Registro {
        private val nombre = mutableMapOf<String, String>()
        private val origen = mutableMapOf<String, Int>()
        private var cuantas = 0

        fun registrar(token: String?, comoSeLlama: String) {
            if (token.isNullOrBlank()) return
            nombre[token] = comoSeLlama
            origen[token] = cuantas
        }

        /** Se llama una vez por operación, después de contarla. */
        fun avanzar() {
            cuantas++
        }

        fun visible(token: String?): String = when {
            token.isNullOrBlank() -> "la pieza"
            token == "raiz" || token == "modelo" -> "el modelo"
            token == "seleccion" -> "la selección"
            else -> nombre[token] ?: "«$token»"
        }

        fun dependenciasDe(op: Operacion): List<Int> =
            objetivosDe(op).mapNotNull { origen[it] }.distinct().sorted()

        fun renombrar(token: String, nuevo: String) {
            if (nombre.containsKey(token)) nombre[token] = nuevo
        }
    }

    /** Los tokens a los que apunta una operación. Es de dónde salen las dependencias. */
    private fun objetivosDe(op: Operacion): List<String> = when (op) {
        is Crear -> listOfNotNull(op.padre)
        is Envolver -> listOf(op.objetivo)
        is Fijar -> listOf(op.objetivo)
        is Mover -> listOf(op.objetivo)
        is Girar -> listOf(op.objetivo)
        is Escalar -> listOf(op.objetivo)
        is Acotar -> listOf(op.objetivo)
        is Renombrar -> listOf(op.objetivo)
        is Eliminar -> listOf(op.objetivo)
        is Duplicar -> listOf(op.objetivo)
        is Colocar -> listOf(op.objetivo, op.referencia)
        is Alinear -> listOf(op.objetivo, op.referencia)
        is DefinirPerfil -> listOf(op.objetivo)
        is Taladro -> listOf(op.objetivo)
        is Patron -> listOf(op.objetivo)
        is Pared -> listOf(op.objetivo)
        is Filete -> listOfNotNull(op.objetivo, op.contra)
        is Apoyar -> listOf(op.objetivo)
        is Seleccionar -> listOf(op.objetivo)
        is Asentar -> emptyList()
    }

    private fun contar(op: Operacion, r: Registro): String {
        val linea = when (op) {
            is Crear -> {
                val tipo = TipoPieza.entries.firstOrNull { it.name.equals(op.tipo, true) }
                val comoSeLlama = op.nombre ?: tipo?.etiqueta ?: op.tipo
                val cotas = cotasDe(tipo, op.parametros)
                val sitio = if (op.padre != null) " dentro de ${r.visible(op.padre)}" else ""
                val donde = puntoDe(op.posicion)
                r.registrar(op.alias, comoSeLlama)
                r.registrar(op.nombre, comoSeLlama)
                "Crea $comoSeLlama$cotas$sitio$donde"
            }

            is Envolver -> {
                val tipo = TipoPieza.entries.firstOrNull { it.name.equals(op.tipo, true) }
                val comoSeLlama = op.nombre ?: tipo?.etiqueta ?: op.tipo
                val verbo = when (tipo) {
                    TipoPieza.DIFERENCIA -> "Resta de"
                    TipoPieza.UNION -> "Une"
                    TipoPieza.INTERSECCION -> "Interseca"
                    TipoPieza.VACIADO -> "Ahueca"
                    TipoPieza.SIMETRIA -> "Refleja"
                    TipoPieza.REPETICION -> "Repite"
                    else -> "Envuelve"
                }
                r.registrar(op.alias, comoSeLlama)
                "$verbo ${r.visible(op.objetivo)}${cotasDe(tipo, op.parametros)}"
            }

            is Fijar -> "Pone ${op.clave} de ${r.visible(op.objetivo)} en ${medida(op.valor)} mm"

            is Mover -> {
                val d = "(${medida(op.x)}, ${medida(op.y)}, ${medida(op.z)}) mm"
                if (op.absoluto) "Lleva ${r.visible(op.objetivo)} a $d"
                else "Desplaza ${r.visible(op.objetivo)} $d"
            }

            is Girar -> {
                val g = listOf("X" to op.x, "Y" to op.y, "Z" to op.z)
                    .filter { it.second != 0f }
                    .joinToString(", ") { "${medida(it.second)}° en ${it.first}" }
                "Gira ${r.visible(op.objetivo)} ${g.ifEmpty { "0°" }}"
            }

            is Escalar -> "Escala ${r.visible(op.objetivo)} × ${medida(op.factor)}"

            is Acotar ->
                "Ajusta ${r.visible(op.objetivo)} para que mida ${medida(op.medida)} mm en ${op.eje.name}"

            is Renombrar -> {
                val antes = r.visible(op.objetivo)
                r.renombrar(op.objetivo, op.nombre)
                "Renombra $antes a «${op.nombre}»"
            }

            is Eliminar -> "Borra ${r.visible(op.objetivo)}"

            is Duplicar -> {
                val copia = "copia de ${r.visible(op.objetivo)}"
                r.registrar(op.alias, copia)
                "Duplica ${r.visible(op.objetivo)}"
            }

            is Colocar -> {
                val holgura = when {
                    op.holgura > 0f -> ", separada ${medida(op.holgura)} mm"
                    op.holgura < 0f -> ", solapando ${medida(-op.holgura)} mm"
                    else -> ""
                }
                val centrado = if (op.centrar) "" else ", sin centrar"
                "Apoya ${r.visible(op.objetivo)} ${op.cara.etiqueta} de " +
                    "${r.visible(op.referencia)}$holgura$centrado"
            }

            is Alinear -> {
                val modo = when (op.modo) {
                    ModoDeAlineacion.CENTRO -> "por el centro"
                    ModoDeAlineacion.MINIMO -> "por su cara menor"
                    ModoDeAlineacion.MAXIMO -> "por su cara mayor"
                }
                "Alinea ${r.visible(op.objetivo)} con ${r.visible(op.referencia)} " +
                    "en ${op.eje.name} $modo"
            }

            is DefinirPerfil -> {
                val forma = FormaDePerfil.entries.firstOrNull { it.name.equals(op.forma, true) }
                val detalle = when {
                    forma == FormaDePerfil.LIBRE || forma == null ->
                        " de ${op.puntos.size} puntos"
                    else -> cotasDePerfil(forma, op.parametros)
                }
                "Da a ${r.visible(op.objetivo)} un contorno " +
                    "${(forma?.etiqueta ?: op.forma).lowercase()}$detalle"
            }

            is Taladro -> {
                val cual = op.designacion?.uppercase()
                    ?: "de ${medida(op.diametro)} mm"
                val sitio = when {
                    op.punto.size >= 2 ->
                        " en el punto (${medida(op.punto[0])}, ${medida(op.punto[1])}) del contorno"
                    op.desplazamiento.size >= 2 ->
                        " a (${medida(op.desplazamiento[0])}, ${medida(op.desplazamiento[1])}) mm del centro"
                    else -> " en el centro"
                }
                r.registrar(op.alias, "${r.visible(op.objetivo)} taladrada")
                "Taladra ${r.visible(op.objetivo)}: agujero $cual ${op.ajuste.etiqueta}$sitio"
            }

            is Patron -> {
                val u = if (op.estandar.startsWith("RACK")) ", ${op.unidades}U" else ""
                "Abre en ${r.visible(op.objetivo)} los agujeros del patrón " +
                    "${op.estandar}$u (${op.ajuste.etiqueta})"
            }

            is Pared -> {
                val g = if (op.grosor > 0f) "de ${medida(op.grosor)} mm"
                else "del grosor que aguanta el perfil activo"
                r.registrar(op.alias, "${r.visible(op.objetivo)} hueca")
                "Ahueca ${r.visible(op.objetivo)} dejando pared $g"
            }

            is Asentar -> "Baja el modelo hasta apoyarlo en el plato"

            is Filete -> {
                val canto = if (op.contra != null)
                    "el canto entre ${r.visible(op.objetivo)} y ${r.visible(op.contra)}"
                else "todos los cantos de ${r.visible(op.objetivo)}"
                "Redondea $canto con radio ${medida(op.radio)} mm"
            }

            is Apoyar ->
                "Tumba ${r.visible(op.objetivo)} con su cara ${op.cara.etiqueta} contra el plato"

            is Seleccionar -> "Señala ${r.visible(op.objetivo)}"
        }
        r.avanzar()
        return linea + porQue(op)
    }

    /**
     * La nota del modelo va detrás y entre guiones, no en línea aparte.
     *
     * Es lo único de esta explicación que escribe el modelo, así que se marca como
     * lo que es —su motivo— y no se mezcla con lo que hace la operación, que es un
     * hecho leído del plan.
     */
    private fun porQue(op: Operacion): String {
        val n = op.nota?.trim().orEmpty()
        return if (n.isEmpty()) "" else " — $n"
    }

    private fun cotasDe(tipo: TipoPieza?, parametros: Map<String, Float>): String {
        if (tipo == null || parametros.isEmpty()) return ""
        // En el orden del inspector, que es el orden en el que se leen las cotas de
        // una pieza: anchura, altura, profundidad. Un mapa no tiene orden y el del
        // modelo llega como le apetece.
        val enOrden = tipo.parametros.mapNotNull { def ->
            parametros[def.clave]?.let { def to it }
        }
        if (enOrden.isEmpty()) return ""
        val principales = enOrden.filter { it.first.clave != "redondeo" }
        val cuerpo = if (principales.size >= 2 && principales.all { it.first.unidad == "mm" })
            principales.joinToString(" × ") { medida(it.second) } + " mm"
        else
            enOrden.joinToString(", ") { "${it.first.etiqueta.lowercase()} ${medida(it.second)}" }
        val redondeo = enOrden.firstOrNull { it.first.clave == "redondeo" && it.second > 0f }
        val extra = redondeo?.let { ", cantos de ${medida(it.second)} mm" }.orEmpty()
        return " de $cuerpo$extra"
    }

    private fun cotasDePerfil(forma: FormaDePerfil, parametros: Map<String, Float>): String {
        val enOrden = forma.parametros.mapNotNull { def -> parametros[def.clave]?.let { def to it } }
        if (enOrden.isEmpty()) return ""
        return " de " + enOrden.joinToString(", ") {
            "${it.first.etiqueta.lowercase()} ${medida(it.second)}"
        }
    }

    private fun puntoDe(posicion: List<Float>?): String {
        if (posicion == null || posicion.size < 3) return ""
        if (posicion.all { it == 0f }) return ""
        return " en (${medida(posicion[0])}, ${medida(posicion[1])}, ${medida(posicion[2])}) mm"
    }

    /**
     * Milímetros como los escribe una persona en español: coma decimal y sin ceros
     * de relleno. `mm` del contexto usa punto porque su lector es el modelo; el de
     * aquí es quien va a decidir si acepta el plan.
     */
    internal fun medida(v: Float): String {
        if (!v.isFinite()) return "0"
        val r = kotlin.math.round(v * 100f) / 100f
        if (r == r.toInt().toFloat()) return r.toInt().toString()
        return r.toString().replace('.', ',')
    }
}

/** El plan contado en español, una línea por operación. */
fun PlanDeModelado.explicar(): List<LineaExplicada> = Explicacion.de(this)
