package yunkil.tools

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.Explicacion
import yunkil.ia.PlanDeModelado
import java.net.http.HttpClient
import java.time.Duration
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Banco del segundo turno: ¿sirve de algo el hilo de conversación?
 *
 * El banco de modelado mide una petición aislada. Este mide **la corrección que viene
 * después**, que es donde vive el argumento entero del producto: si Yunkil promete
 * conversación editable, «hazlo más grueso» tiene que funcionar sin redescribir la
 * pieza.
 *
 * Y lo mide de la única forma que significa algo: **cada corrección se corre dos
 * veces**, una con el hilo y otra sin él, contra el mismo modelo y sobre el mismo
 * documento de partida. La diferencia entre las dos columnas es el valor del hilo. Sin
 * la columna de control, «7 de 8 con hilo» no dice nada: puede que el modelo acertara
 * igual leyendo solo el documento.
 *
 * El documento de partida **no lo escribe el modelo**: se construye con un plan fijo,
 * porque si la primera pieza saliera de una inferencia, las dos columnas partirían de
 * geometrías distintas y la comparación no sería tal.
 *
 *     ./gradlew :core:bancoDeHilo
 *     ./gradlew :core:bancoDeHilo --args="URL MODELO"
 */

/** Una corrección de segundo turno, con la pieza de partida y lo que tiene que salir. */
private data class CasoDeHilo(
    /** Lo que se pidió en el primer turno. Va al hilo, no se ejecuta. */
    val peticionInicial: String,
    /** La pieza que quedó de ese primer turno, fija para que las dos columnas empaten. */
    val plantilla: PlanDeModelado,
    /** Lo que se aplicó, contado como lo cuenta el panel. Es el turno de Yunkil. */
    val loQueSeAplico: List<String>,
    /** La corrección, escrita como la escribe alguien que ya tiene la pieza delante. */
    val correccion: String,
    /** Qué tiene que cumplir el documento después de la corrección. */
    val comprobar: (Editor) -> String?,
)

private fun tamanoDe(editor: Editor): Triple<Float, Float, Float> {
    val lo = editor.cotaMinima
    val hi = editor.cotaMaxima
    return Triple(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2])
}

private fun midePor(editor: Editor, eje: Int, esperado: Float, tolerancia: Float): String? {
    val t = tamanoDe(editor)
    val medido = when (eje) { 0 -> t.first; 1 -> t.second; else -> t.third }
    return if (abs(medido - esperado) <= tolerancia) null
    else "mide ${"%.1f".format(medido)} y tenía que medir $esperado"
}

/**
 * Las correcciones. Todas son **elípticas a propósito**: ninguna nombra la pieza ni
 * repite lo que se pidió antes, porque una corrección que se explica sola no mide el
 * hilo, mide el contexto del documento, que ya funcionaba.
 */
private fun casos(): List<CasoDeHilo> = listOf(
    CasoDeHilo(
        peticionInicial = "una caja de 60 x 40 x 25 mm para guardar tornillos",
        plantilla = PlanDeModelado(
            operaciones = listOf(
                yunkil.ia.Crear(
                    tipo = "CAJA", alias = "b", nombre = "Caja",
                    parametros = mapOf("anchura" to 60f, "altura" to 25f, "profundidad" to 40f),
                ),
            ),
        ),
        loQueSeAplico = listOf("Crea Caja de 60 × 25 × 40 mm"),
        correccion = "hazla más ancha, que llegue a 100",
        comprobar = { e -> midePor(e, 0, 100f, tolerancia = 3f) },
    ),

    CasoDeHilo(
        peticionInicial = "una caja de 60 x 40 x 25 mm para guardar tornillos",
        plantilla = PlanDeModelado(
            operaciones = listOf(
                yunkil.ia.Crear(
                    tipo = "CAJA", alias = "b", nombre = "Caja",
                    parametros = mapOf("anchura" to 60f, "altura" to 25f, "profundidad" to 40f),
                ),
            ),
        ),
        loQueSeAplico = listOf("Crea Caja de 60 × 25 × 40 mm"),
        correccion = "ahuécala, que tenga 2 mm de pared",
        comprobar = { e ->
            val huecos = e.filas().count { it.tipo == "VACIADO" || it.tipo == "DIFERENCIA" }
            if (huecos == 0) "sigue maciza" else null
        },
    ),

    CasoDeHilo(
        peticionInicial = "un soporte de móvil de 80 mm de ancho",
        plantilla = PlanDeModelado(
            operaciones = listOf(
                yunkil.ia.Crear(
                    tipo = "CAJA", alias = "b", nombre = "Base",
                    parametros = mapOf("anchura" to 80f, "altura" to 8f, "profundidad" to 60f),
                ),
            ),
        ),
        loQueSeAplico = listOf("Crea Base de 80 × 8 × 60 mm"),
        correccion = "ponle cuatro agujeros para M3",
        comprobar = { e ->
            if (e.filas().count { it.tipo == "DIFERENCIA" } == 0) "no hay ningún agujero" else null
        },
    ),

    CasoDeHilo(
        peticionInicial = "una tapa redonda de 40 mm de diámetro",
        plantilla = PlanDeModelado(
            operaciones = listOf(
                yunkil.ia.Crear(
                    tipo = "CILINDRO", alias = "c", nombre = "Tapa",
                    parametros = mapOf("radio" to 20f, "altura" to 6f),
                ),
            ),
        ),
        loQueSeAplico = listOf("Crea Tapa de radio 20, altura 6"),
        correccion = "redondéale los cantos, 1,5 mm",
        comprobar = { e ->
            val r = e.parametrosDe(e.filas().first { it.tipo == "CILINDRO" }.id)
                .firstOrNull { it.clave == "redondeo" }?.valor ?: 0f
            if (r > 0.4f) null else "el redondeo sigue en $r"
        },
    ),

    CasoDeHilo(
        peticionInicial = "un separador cilíndrico de 10 mm de alto",
        plantilla = PlanDeModelado(
            operaciones = listOf(
                yunkil.ia.Crear(
                    tipo = "CILINDRO", alias = "c", nombre = "Separador",
                    parametros = mapOf("radio" to 6f, "altura" to 10f),
                ),
            ),
        ),
        loQueSeAplico = listOf("Crea Separador de radio 6, altura 10"),
        correccion = "el doble de alto",
        comprobar = { e -> midePor(e, 1, 20f, tolerancia = 2f) },
    ),

    // Los dos siguientes son los que de verdad ponen a prueba el hilo. Los de arriba se
    // pueden resolver mirando solo el documento —«la caja» es la única pieza que hay—,
    // así que miden el contexto, que ya funcionaba. Estos dos hablan de algo que **no
    // está en la geometría**: lo que se pidió en su día y no llegó a hacerse. Sin hilo,
    // «la tapa que te dije» no se refiere a nada.
    CasoDeHilo(
        peticionInicial = "una caja de 60 x 40 x 25 mm con su tapa",
        plantilla = PlanDeModelado(
            operaciones = listOf(
                yunkil.ia.Crear(
                    tipo = "CAJA", alias = "b", nombre = "Caja",
                    parametros = mapOf("anchura" to 60f, "altura" to 25f, "profundidad" to 40f),
                ),
            ),
        ),
        loQueSeAplico = listOf("Crea Caja de 60 × 25 × 40 mm"),
        correccion = "y ahora ponle la tapa que te dije",
        comprobar = { e ->
            val piezas = e.filas().filter { it.profundidad > 0 && !it.esOperacion }
            if (piezas.size < 2) "no ha añadido ninguna pieza"
            else {
                // Una tapa de esa caja mide 60 × 40 en planta. Con 6 mm de tolerancia,
                // que es lo que da de sí una tapa con reborde.
                val t = tamanoDe(e)
                if (abs(t.first - 60f) <= 6f && abs(t.third - 40f) <= 6f) null
                else "la tapa no cuadra con la caja: el conjunto mide ${"%.0f".format(t.first)} × ${"%.0f".format(t.third)}"
            }
        },
    ),

    CasoDeHilo(
        peticionInicial = "una placa de 50 x 50 mm con cuatro agujeros M3 en las esquinas",
        plantilla = PlanDeModelado(
            operaciones = listOf(
                yunkil.ia.Crear(
                    tipo = "CAJA", alias = "p", nombre = "Placa",
                    parametros = mapOf("anchura" to 50f, "altura" to 4f, "profundidad" to 50f),
                ),
            ),
        ),
        loQueSeAplico = listOf("Crea Placa de 50 × 4 × 50 mm"),
        correccion = "faltan los agujeros",
        comprobar = { e ->
            // Un M3 de paso son 3,4 mm de diámetro: radio 1,7. Es la cota que solo puede
            // salir de recordar que se dijo «M3», porque en la placa no está escrita.
            val radios = e.filas().filter { it.tipo == "CILINDRO" }
                .mapNotNull { f -> e.parametrosDe(f.id).firstOrNull { it.clave == "radio" }?.valor }
            if (radios.isEmpty()) "no hay ningún agujero"
            else if (radios.none { abs(it - 1.7f) <= 0.5f }) {
                "los agujeros no son M3: radios ${radios.joinToString { "%.1f".format(it) }}"
            } else null
        },
    ),

    CasoDeHilo(
        peticionInicial = "una placa de 50 x 50 mm con un agujero M4 en el centro",
        plantilla = PlanDeModelado(
            operaciones = listOf(
                yunkil.ia.Crear(
                    tipo = "CAJA", alias = "p", nombre = "Placa",
                    parametros = mapOf("anchura" to 50f, "altura" to 5f, "profundidad" to 50f),
                ),
                yunkil.ia.Taladro(objetivo = "p", designacion = "M4"),
            ),
        ),
        loQueSeAplico = listOf(
            "Crea Placa de 50 × 5 × 50 mm",
            "Taladra Placa: agujero M4 de paso en el centro",
        ),
        correccion = "el agujero era M6, no M4",
        comprobar = { e ->
            // Un M6 de paso son 6,6 mm de diámetro, o sea 3,3 de radio; el M4, 2,2.
            val radios = e.filas().filter { it.tipo == "CILINDRO" }
                .mapNotNull { f -> e.parametrosDe(f.id).firstOrNull { it.clave == "radio" }?.valor }
            if (radios.any { it > 2.8f }) null
            else "los agujeros siguen a radio ${radios.joinToString()}"
        },
    ),
)

fun main(args: Array<String>) {
    val endpoint = normalizarUrl(args.getOrNull(0) ?: "http://127.0.0.1:9292/v1/chat/completions")
    val modelo = args.getOrNull(1) ?: "qwen3.6-35b-a3b"
    val cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    println("Banco de hilo de Yunkil — ¿sirve de algo la memoria de la conversación?")
    println("  modelo:   $modelo")
    println("  endpoint: $endpoint")
    println()

    var aciertaConHilo = 0
    var aciertaSinHilo = 0
    var respondieron = 0
    val total = casos().size * 2

    for ((indice, caso) in casos().withIndex()) {
        println("${indice + 1}. «${caso.correccion}»  (venía de: ${caso.peticionInicial.take(46)}…)")

        for (conHilo in listOf(true, false)) {
            val etiqueta = if (conHilo) "con hilo" else "sin hilo"
            val editor = prepararPieza(caso, conHilo)

            val sistema = editor.instruccionesParaModelo(null, caso.correccion)
            val contexto = editor.contextoConHilo(presupuestoDelHilo = 1200)
            val mensaje = "Documento actual:\n$contexto\n\nPetición:\n${caso.correccion}"

            val respuesta = pedirAlModelo(cliente, endpoint, modelo, sistema, mensaje)
            if (respuesta == null) {
                println("   $etiqueta: SIN RESPUESTA — ${motivoDelUltimoFallo ?: "desconocido"}")
                continue
            }
            respondieron++

            val leido = editor.interpretarPlan(respuesta, edicion = false)
            val plan = leido.plan
            if (plan == null) {
                println("   $etiqueta: JSON RECHAZADO — ${leido.motivoDelRechazo}")
                continue
            }

            val resultado = editor.aplicarPlan(plan, null)
            if (!resultado.exito) {
                println("   $etiqueta: NO SE APLICA — ${resultado.error}")
                continue
            }

            val fallo = caso.comprobar(editor)
            if (fallo == null) {
                if (conHilo) aciertaConHilo++ else aciertaSinHilo++
                println("   $etiqueta: correcto")
            } else {
                println("   $etiqueta: NO CUMPLE — $fallo")
                // La explicación del plan, que es exactamente lo que el usuario habría
                // leído en el panel antes de aceptarlo. Diagnostica mucho mejor que el
                // JSON crudo si lo que falló fue la intención o la aritmética.
                for (l in Explicacion.de(plan)) println("      · ${l.texto}")
            }
        }
        println()
    }

    val n = casos().size
    println("Respondió              $respondieron/$total")
    println("Acierta CON el hilo    $aciertaConHilo/$n")
    println("Acierta SIN el hilo    $aciertaSinHilo/$n")
    println()
    when {
        respondieron == 0 -> println("El stack no respondió: comprueba que está arriba en $endpoint.")
        aciertaConHilo > aciertaSinHilo ->
            println("El hilo aporta ${aciertaConHilo - aciertaSinHilo} de $n. Sirve.")
        aciertaConHilo == aciertaSinHilo ->
            println("El hilo no cambia nada en estos casos. El contexto del documento ya bastaba.")
        else ->
            println("El hilo EMPEORA el resultado. Hay que mirar qué le está distrayendo.")
    }
    exitProcess(0)
}

/**
 * Deja el documento en el estado del segundo turno.
 *
 * La pieza es la misma en las dos columnas y sale de un plan fijo, no de una
 * inferencia: si la escribiera el modelo, las dos ramas partirían de geometrías
 * distintas y lo que se compararía sería el azar de dos generaciones.
 */
private fun prepararPieza(caso: CasoDeHilo, conHilo: Boolean): Editor {
    val editor = Editor(Documento.vacio())
    editor.aplicarPlan(caso.plantilla, null)
    if (conHilo) {
        editor.anotarPeticion(caso.peticionInicial)
        editor.anotarRespuesta("APLICADO", caso.loQueSeAplico, emptyList())
    }
    return editor
}
