package yunkil.tools

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.CriticoVisual
import java.io.File
import java.net.http.HttpClient
import java.time.Duration

/**
 * ¿El crítico visual ve algo?
 *
 * Nace de una medida incómoda: en una tanda entera del banco el crítico aprobó las doce
 * piezas y no objetó ni una vez. Con cero objeciones no pudo cambiar nada, así que el
 * resultado de esa tanda no era suyo — y un revisor que nunca protesta y un revisor
 * roto se leen exactamente igual desde fuera.
 *
 * Esto lo separa. Se le enseñan pares de piezas contra la misma petición: una que la
 * cumple y otra que **no la cumple de forma evidente**, del tipo que cualquiera vería en
 * medio segundo. Lo que se mide no es si acierta, es si **distingue**:
 *
 *  - si dice NO CUMPLE a la mala y CUMPLE a la buena, el crítico sirve;
 *  - si dice CUMPLE a las dos, es un sello de goma y el bucle visual no vale nada;
 *  - si dice NO CUMPLE a las dos, protesta de más y convertiría el bucle en un
 *    generador de reintentos infinitos.
 *
 * Las tres respuestas son accionables y las tres son distintas, que es lo que le faltaba
 * al banco para poder hablar de esto.
 *
 *     ./gradlew :core:pruebaDeVista --args="URL MODELO_DE_VISION"
 */

/** Una pieza que se le enseña al crítico, con lo que debería contestar. */
private data class Careo(
    val peticion: String,
    val comoDeberiaSalir: String,
    val planBueno: String,
    val comoSaleMal: String,
    val planMalo: String,
)

private val careos = listOf(
    Careo(
        peticion = "un gancho para colgar de una puerta de 4 cm de grosor",
        comoDeberiaSalir = "una U que abraza la puerta",
        planBueno = """
            {"resumen":"Gancho","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"lomo","nombre":"Lomo","parametros":{"anchura":30,"altura":80,"profundidad":6}},
              {"op":"crear","tipo":"CAJA","alias":"puente","nombre":"Puente","parametros":{"anchura":30,"altura":6,"profundidad":52}},
              {"op":"colocar","objetivo":"puente","referencia":"lomo","cara":"arriba"},
              {"op":"crear","tipo":"CAJA","alias":"labio","nombre":"Labio","parametros":{"anchura":30,"altura":40,"profundidad":6}},
              {"op":"colocar","objetivo":"labio","referencia":"puente","cara":"delante"}
            ]}
        """,
        comoSaleMal = "una chapa plana, sin nada que abrace",
        planMalo = """
            {"resumen":"Chapa","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"chapa","nombre":"Chapa","parametros":{"anchura":40,"altura":4,"profundidad":80}}
            ]}
        """,
    ),
    // Este careo sustituye a uno mal planteado: se comparaba una caja ahuecada con
    // `pared` contra un bloque macizo, y **desde fuera son idénticas** —una cáscara
    // cerrada no enseña su hueco en ninguna vista exterior—. El crítico las aprobó las
    // dos y tenía razón. Un careo cuya respuesta correcta es «no se puede saber» no
    // mide al crítico, mide al careo.
    Careo(
        peticion = "una escuadra en L para atornillar dos tableros en ángulo recto",
        comoDeberiaSalir = "un perfil en L",
        planBueno = """
            {"resumen":"Escuadra","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"EXTRUSION","alias":"esc","nombre":"Escuadra","parametros":{"altura":40}},
              {"op":"perfil","objetivo":"esc","forma":"LIBRE","puntos":[[0,0],[50,0],[50,8],[8,8],[8,50],[0,50]]}
            ]}
        """,
        comoSaleMal = "una tabla plana, sin ángulo ninguno",
        planMalo = """
            {"resumen":"Tabla","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"t","nombre":"Tabla","parametros":{"anchura":50,"altura":8,"profundidad":40}}
            ]}
        """,
    ),
    Careo(
        peticion = "un pomo redondeado con un agujero pasante en el centro",
        comoDeberiaSalir = "un cilindro con su agujero",
        planBueno = """
            {"resumen":"Pomo","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CILINDRO","alias":"p","nombre":"Pomo","parametros":{"radio":15,"altura":20,"redondeo":4}},
              {"op":"taladro","objetivo":"p","designacion":"M6"}
            ]}
        """,
        comoSaleMal = "un cilindro macizo, sin agujero",
        planMalo = """
            {"resumen":"Pomo","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CILINDRO","alias":"p","nombre":"Pomo","parametros":{"radio":15,"altura":20,"redondeo":4}}
            ]}
        """,
    ),
)

private fun veredictoDe(
    cliente: HttpClient,
    endpoint: String,
    visor: String,
    peticion: String,
    plan: String,
    guardarComo: String,
): Pair<Boolean, String> {
    val editor = Editor(Documento.vacio())
    val leido = editor.interpretarPlan(plan.trimIndent())
    val interpretado = leido.plan ?: return true to "no se interpretó el plan: ${leido.motivoDelRechazo}"
    val png = editor.vistasDelPlan(interpretado) ?: return true to "no se pudo dibujar la pieza"

    // La imagen se guarda siempre. Cuando el veredicto sorprenda, lo primero que hay que
    // poder hacer es mirar lo mismo que miró el modelo: si la pieza salió irreconocible,
    // el fallo es del dibujo y no del crítico.
    File(guardarComo).writeBytes(png)

    val respuesta = pedirAlModeloConImagen(
        cliente, endpoint, visor, CriticoVisual.instrucciones(peticion), png,
    ) ?: return true to "SIN RESPUESTA — ${motivoDelUltimoFallo ?: "motivo desconocido"}"

    val veredicto = CriticoVisual.leer(respuesta)
    val resumen = if (veredicto.cumple) respuesta.replace("\n", " ").take(110)
    else veredicto.reparos.joinToString("; ")
    return veredicto.cumple to resumen
}

fun main(args: Array<String>) {
    val endpoint = normalizarUrl(args.getOrNull(0) ?: "http://127.0.0.1:9292")
    val visor = args.getOrNull(1) ?: "qwen3-vl-8b"
    // Repetir no es un lujo: dos pasadas seguidas del mismo careo con el mismo modelo
    // dieron 2/3 y 1/3. Un careo medido una vez dice tan poco como un banco corrido una
    // vez, y esa lección ya costó una tarde hoy.
    val vueltas = (args.getOrNull(2)?.toIntOrNull() ?: 3).coerceAtLeast(1)
    val carpeta = File("core/build/vista").apply { mkdirs() }

    println("¿El crítico visual distingue una pieza buena de una mala?")
    println("  visor:    $visor")
    println("  endpoint: $endpoint")
    println("  imágenes: ${carpeta.path}")
    println("  vueltas:  $vueltas por careo")
    println()

    val cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    var distingue = 0
    var selloDeGoma = 0
    var proteston = 0

    for ((indice, careo) in careos.withIndex()) {
      println("${indice + 1}. «${careo.peticion}»")
      for (vuelta in 1..vueltas) {

        val (buenaCumple, buenaTexto) = veredictoDe(
            cliente, endpoint, visor, careo.peticion, careo.planBueno,
            File(carpeta, "careo${indice + 1}-buena.png").path,
        )
        println("   buena  (${careo.comoDeberiaSalir}): ${if (buenaCumple) "CUMPLE" else "NO CUMPLE"} · $buenaTexto")

        val (malaCumple, malaTexto) = veredictoDe(
            cliente, endpoint, visor, careo.peticion, careo.planMalo,
            File(carpeta, "careo${indice + 1}-mala.png").path,
        )
        println("   mala   (${careo.comoSaleMal}): ${if (malaCumple) "CUMPLE" else "NO CUMPLE"} · $malaTexto")

        when {
            buenaCumple && !malaCumple -> { distingue++; println("   → DISTINGUE") }
            buenaCumple && malaCumple -> { selloDeGoma++; println("   → SELLO DE GOMA: aprueba lo que no sirve") }
            else -> { proteston++; println("   → PROTESTA DE MÁS: tumba la pieza buena") }
        }
        println()
      }
    }

    val total = careos.size * vueltas
    println("Distingue        $distingue/$total")
    println("Sello de goma    $selloDeGoma/$total")
    println("Protesta de más  $proteston/$total")
}
