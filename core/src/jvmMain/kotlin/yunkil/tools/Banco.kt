package yunkil.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.Vocabulario
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Banco de modelado: peticiones reales contra el modelo real, por la cadena real.
 *
 * Existe porque hasta ahora la elección de modelo era **criterio y no dato**. «Un MoE con
 * 3B activos sigue instrucciones bien» es una hipótesis razonable; «de 12 peticiones sacó
 * 10 planes válidos y 8 piezas estancas» es un número. Sin esto, afinar el prompt es
 * mirar una sola ejecución y decidir por la impresión que dejó.
 *
 * Lo que mide, en este orden, porque cada paso solo tiene sentido si pasó el anterior:
 *
 *  1. **Respondió** algo el modelo.
 *  2. El JSON **se interpreta** contra el esquema (aquí se cae lo que inventa campos).
 *  3. El plan **se aplica** sin operaciones omitidas.
 *  4. La **revisión geométrica** no encuentra sólidos flotando ni restas que no cortan.
 *  5. Los **asertos de la petición** se cumplen: número de agujeros, cotas, estanqueidad.
 *
 * Los asertos son la parte que hace que esto sea un banco y no una demo: cada petición
 * trae qué tiene que salir, comprobado sobre el documento aplicado y no sobre el texto que
 * escribió el modelo.
 *
 *     ./gradlew :core:banco                       # contra 127.0.0.1:9292
 *     ./gradlew :core:banco --args="URL MODELO"
 */

private const val ENDPOINT_POR_DEFECTO = "http://127.0.0.1:9292/v1/chat/completions"
private const val MODELO_POR_DEFECTO = "qwen3.6-35b-a3b"

/** Vueltas de corrección, las mismas que da el producto. */
private const val RONDAS = 3

/** Una petición del banco con lo que tiene que salir de ella. */
private data class Caso(
    val peticion: String,
    /** Qué tiene que cumplir el documento resultante. Devuelve el motivo del fallo o null. */
    val comprobar: (Editor) -> String?,
)

private val json = Json { ignoreUnknownKeys = true }

/** Cotas del modelo entero, en milímetros. */
private fun tamano(editor: Editor): Triple<Float, Float, Float> {
    val lo = editor.cotaMinima
    val hi = editor.cotaMaxima
    return Triple(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2])
}

private fun mide(editor: Editor, eje: Int, esperado: Float, tolerancia: Float = 1f): String? {
    val t = tamano(editor)
    val medido = when (eje) { 0 -> t.first; 1 -> t.second; else -> t.third }
    return if (abs(medido - esperado) <= tolerancia) null
    else "mide ${"%.1f".format(medido)} mm en el eje $eje y tenía que medir $esperado"
}

private fun cuentaDeTipo(editor: Editor, tipo: String): Int =
    editor.filas().count { it.tipo == tipo }

/**
 * Las peticiones. Son las que alguien escribe de verdad, no las que le vienen bien al
 * sistema, y por eso hay dos que exigen operaciones de dominio (`patron`, `taladro`) y
 * una que solo se puede hacer con un contorno.
 */
private fun casos(): List<Caso> = listOf(
    // El aserto pide «sin tapa» a propósito. La primera versión decía «con la tapa abierta»
    // y medía el ancho del modelo entero: el modelo hizo la caja de 60 y una tapa aparte de
    // 62, que es una respuesta legítima, y el banco lo contaba como fallo. Un aserto que
    // castiga una solución correcta no mide al modelo, mide al aserto.
    Caso("una caja abierta por arriba de 60 x 40 x 25 mm con las paredes de 2,4 mm, sin tapa") { e ->
        mide(e, 0, 60f) ?: mide(e, 2, 40f)
            ?: if (cuentaDeTipo(e, "VACIADO") > 0 || cuentaDeTipo(e, "DIFERENCIA") > 0) null
            else "no hay ni un hueco: la caja salió maciza"
    },

    Caso("una escuadra en L de 50 mm de lado, 6 mm de grosor, con dos agujeros para M4") { e ->
        if (cuentaDeTipo(e, "EXTRUSION") == 0) "una escuadra es un contorno extruido y salió a base de cajas"
        else if (cuentaDeTipo(e, "DIFERENCIA") == 0) "no hay ningún agujero"
        else null
    },

    Caso("unas orejas para montar algo en un rack de 19 pulgadas, 1U") { e ->
        val ancho = tamano(e).first
        if (ancho > 256f) "mide $ancho mm de ancho y no cabe en un plato de 256"
        else if (cuentaDeTipo(e, "DIFERENCIA") == 0) "una oreja de rack sin agujeros no sirve de nada"
        else null
    },

    Caso("un adaptador VESA 100 para colgar una caja de 80 mm") { e ->
        if (cuentaDeTipo(e, "DIFERENCIA") == 0) "sin agujeros no es un adaptador VESA" else null
    },

    Caso("un soporte de móvil inclinado 60 grados, que mida 80 mm de ancho") { e ->
        mide(e, 0, 80f, tolerancia = 2f)
    },

    Caso("un tubo doblado en U de 8 mm de grueso") { e ->
        if (cuentaDeTipo(e, "BARRIDO") == 0) "un tubo doblado es un BARRIDO, no una cadena de cilindros" else null
    },

    Caso("una base para una Raspberry Pi 5 con separadores") { e ->
        if (cuentaDeTipo(e, "DIFERENCIA") == 0 && cuentaDeTipo(e, "CILINDRO") == 0) {
            "una base de Pi necesita los cuatro puntos de anclaje"
        } else null
    },

    Caso("un pomo redondeado de 30 mm con un agujero M6 en el centro") { e ->
        if (cuentaDeTipo(e, "DIFERENCIA") == 0) "falta el agujero" else null
    },
)

fun main(args: Array<String>) {
    val endpoint = normalizarUrl(args.getOrNull(0) ?: ENDPOINT_POR_DEFECTO)
    val modelo = args.getOrNull(1) ?: MODELO_POR_DEFECTO
    // Un modelo es estocástico: a lo largo de tres tiradas la fila de geometría hizo
    // 6 → 7 → 6 con el mismo prompt y el mismo modelo. Ocho números de una sola
    // pasada sirven para diagnosticar un modo de fallo y no para comparar dos
    // modelos; para eso hace falta repetir cada caso y mirar la proporción.
    val repeticiones = (args.getOrNull(2)?.toIntOrNull() ?: 1).coerceAtLeast(1)

    println("Banco de modelado de Yunkil")
    println("  modelo:   $modelo")
    println("  endpoint: $endpoint")
    println("  prompt:   ${Vocabulario.instrucciones().length} caracteres")
    if (repeticiones > 1) println("  vueltas:  $repeticiones por caso")
    println()

    val cliente = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    var respondieron = 0
    var interpretados = 0
    var aplicados = 0
    var revisados = 0
    var correctos = 0
    var aLaPrimera = 0
    var cosidos = 0
    var rondasGastadas = 0

    val total = casos().size
    val aciertosPorCaso = IntArray(total)

    for (vuelta in 1..repeticiones) {
        if (repeticiones > 1) println("— vuelta $vuelta de $repeticiones —")
        for ((indice, caso) in casos().withIndex()) {
            print("${indice + 1}. ${caso.peticion.take(58)}… ")

            val editor = Editor(Documento.vacio())
            val conversacion = ArrayList<Pair<String, String>>()
            var respuesta: String? = null
            var plan: yunkil.ia.PlanDeModelado? = null
            var revision: yunkil.ia.RevisionDePlan? = null
            var ronda = 0

            // El producto no aplica el primer plan y calla: revisa, le devuelve al modelo lo
            // que midió y le da hasta tres vueltas. Medir solo el primer intento contaba como
            // fallo del modelo lo que el producto sí resuelve, y el gate del DAFO está escrito
            // en estos términos: «>=80 % correctos en tres rondas».
            while (ronda < RONDAS) {
                ronda++
                respuesta = pedir(cliente, endpoint, modelo, editor, caso.peticion, conversacion)
                if (respuesta == null) break

                val leido = editor.interpretarPlan(respuesta)
                plan = leido.plan
                if (plan == null) {
                    conversacion.add(respuesta to "El JSON no vale: ${leido.motivoDelRechazo}. Devuelve el plan completo corregido.")
                    continue
                }

                revision = editor.revisarPlan(plan, null)
                if (revision.aceptable) break

                // Antes de gastar una ronda de inferencia: si el revisor sabe qué
                // operación une lo que quedó suelto, se aplica y se vuelve a medir. Es
                // el mismo arreglo que se le iba a pedir al modelo, sin el paso de que
                // el modelo lo transcriba —que es donde se perdía—.
                val cosido = editor.coserPlan(plan, null)
                if (cosido != null) {
                    val despues = editor.revisarPlan(cosido, null)
                    if (despues.aceptable) {
                        plan = cosido
                        revision = despues
                        cosidos++
                        break
                    }
                }

                conversacion.add(respuesta to "El plan se aplica pero la geometría no se sostiene:\n${revision.informeParaModelo}\nCorrige y devuelve el plan completo.")
            }
            rondasGastadas += ronda

            if (respuesta == null) {
                println("SIN RESPUESTA — ${motivoDelUltimoFallo ?: "motivo desconocido"}")
                continue
            }
            respondieron++

            if (plan == null) {
                println("JSON RECHAZADO tras $ronda rondas")
                continue
            }
            interpretados++

            val aplicacion = editor.aplicarPlan(plan, null)
            if (!aplicacion.exito || aplicacion.omitidas.isNotEmpty()) {
                println("NO SE APLICÓ: ${aplicacion.omitidas.take(2)}")
                continue
            }
            aplicados++

            // Caza lo que el modelo no puede ver: piezas flotando y restas que no cortan.
            if (revision != null && !revision.aceptable) {
                println("GEOMETRÍA tras $ronda rondas: ${revision.motivos.take(1)}")
                continue
            }
            revisados++

            val fallo = caso.comprobar(editor)
            if (fallo != null) {
                println("NO CUMPLE: $fallo")
                // El plan **entero**, y solo cuando falla. Truncarlo a 700 caracteres dejó dos
                // casos sin diagnosticar: el núcleo daba la cota exacta y el ancho de más lo
                // añadía una operación posterior que el volcado cortaba. Un banco que dice que
                // algo falla y no deja ver por qué obliga a repetir la ejecución entera.
                println("   plan: ${respuesta.replace("\n", " ").replace(Regex(" +"), " ")}")
                // Lo mismo contado como lo verá el usuario en el panel de propuesta. Es
                // el sitio donde `explicar()` se enfrenta a planes de modelo real y no a
                // los que uno escribe a mano para probarla, que siempre salen bien.
                plan?.let { p ->
                    println("   se leería como:")
                    for (linea in yunkil.ia.Explicacion.de(p)) println("     · ${linea.texto}")
                }
                // Y qué quedó montado, que es lo que de verdad se midió.
                println("   piezas: ${editor.filas().joinToString(", ") { "${it.tipo} «${it.nombre}»" }}")
                val lo = editor.cotaMinima
                val hi = editor.cotaMaxima
                println(
                    "   cotas: ${"%.1f".format(hi[0] - lo[0])} x ${"%.1f".format(hi[1] - lo[1])} x " +
                        "${"%.1f".format(hi[2] - lo[2])} mm",
                )
                continue
            }
            correctos++
            aciertosPorCaso[indice]++
            if (ronda == 1) aLaPrimera++
            println(if (ronda == 1) "correcto" else "correcto en $ronda rondas")
        }
    }

    val intentos = total * repeticiones
    println()
    println("Respondió           $respondieron/$intentos")
    println("JSON válido         $interpretados/$intentos")
    println("Aplicado entero     $aplicados/$intentos")
    println("Geometría limpia    $revisados/$intentos")
    println("Cumple lo pedido    $correctos/$intentos")
    println("A la primera        $aLaPrimera/$intentos")
    // Cuántas piezas salieron enteras porque Yunkil las cosió, no porque el modelo
    // acertara el contacto. Va aparte a propósito: mezclarlo con el resto tapa
    // justo el número que dice cuánto trabajo está haciendo el producto por el modelo.
    println("Cosidos por Yunkil  $cosidos/$intentos")
    println("Rondas gastadas     $rondasGastadas (máximo posible ${intentos * RONDAS})")

    // El desglose por caso es lo que separa el ruido de un fallo de verdad: un caso
    // que sale 3/3 y otro que sale 0/3 dan la misma media que dos casos a 50 %, y no
    // dicen lo mismo en absoluto sobre qué hay que arreglar.
    if (repeticiones > 1) {
        println()
        println("Por caso:")
        for ((indice, caso) in casos().withIndex()) {
            println("  ${aciertosPorCaso[indice]}/$repeticiones  ${caso.peticion.take(64)}")
        }
    }

    if (respondieron == 0) {
        println()
        println("No respondió a nada: comprueba que el stack local está arriba en $endpoint.")
        exitProcess(2)
    }
    exitProcess(if (correctos == intentos) 0 else 1)
}

/** Una petición al modelo, con el prompt completo de Yunkil y su contexto. */
private fun pedir(
    cliente: HttpClient,
    endpoint: String,
    modelo: String,
    editor: Editor,
    peticion: String,
    /** Rondas anteriores: lo que respondió el modelo y lo que se midió de ello. */
    conversacion: List<Pair<String, String>> = emptyList(),
): String? = pedirAlModelo(
    cliente = cliente,
    endpoint = endpoint,
    modelo = modelo,
    sistema = editor.instruccionesParaModelo(null, peticion),
    usuario = peticion,
    conversacion = conversacion,
)
