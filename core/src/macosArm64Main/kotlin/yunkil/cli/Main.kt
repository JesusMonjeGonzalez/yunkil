package yunkil.cli

import kotlinx.serialization.json.Json
import yunkil.doc.Editor
import yunkil.fabricacion.AnalizadorFdm
import yunkil.fabricacion.InformeDeFabricacion
import yunkil.fabricacion.PerfilFabricacion
import yunkil.malla.leerArchivo
import kotlin.system.exitProcess

/**
 * Yunkil sin ventana.
 *
 * El núcleo ya sabía examinar una pieza contra una impresora concreta y certificar una
 * malla antes de escribirla, pero todo eso vivía detrás de la aplicación: para saber si un
 * STL descargado tenía paredes por debajo de la boquilla había que abrirlo en un editor
 * gráfico. Aquí es una orden de terminal, y por tanto también un paso de CI y algo que se
 * puede meter en un script antes de laminar.
 *
 * Los códigos de salida están pensados para eso:
 *
 *   - `0` la pieza pasa,
 *   - `1` la pieza no pasa —hay algún «fallará», o la malla no se certifica—,
 *   - `2` no se ha podido ni mirar: falta el archivo, la orden no existe, no compila.
 *
 * Es un ejecutable nativo: no hay JVM que instalar en la máquina que lo corra.
 */
fun main(args: Array<String>) {
    val orden = args.firstOrNull() ?: ""
    val resto = args.drop(1)
    when (orden) {
        "examinar" -> exitProcess(examinar(resto))
        "exportar" -> exitProcess(exportar(resto))
        "perfiles" -> exitProcess(perfiles())
        "ayuda", "--help", "-h", "" -> { println(AYUDA); exitProcess(if (orden.isEmpty()) 2 else 0) }
        else -> {
            println("No conozco la orden «$orden».\n")
            println(AYUDA)
            exitProcess(2)
        }
    }
}

private val AYUDA = """
    Yunkil — modelado por campos para impresión 3D, desde la terminal.

      yunkil examinar <pieza.stl|pieza.yunkil> [--perfil <nombre>] [--json]
          Mide la pieza contra una impresora y un material concretos: grosor de pared,
          voladizos, material sin apoyo, base, esbeltez y estado de la malla. Sale con 1
          si algo va a fallar, así que sirve de guardia antes de laminar.

      yunkil exportar <pieza.yunkil|pieza.stl> <salida.stl|salida.3mf> [--detalle <mm>]
          Malla y escribe el archivo **solo** si pasa el certificado: cerrado, orientado,
          sin caras que se crucen y con el volumen que dice el campo. Con un STL de
          entrada es la función que da nombre al producto —entra una malla cualquiera,
          sale una comprobada—. Sin --detalle usa la resolución que el modelo sugiere.

      yunkil perfiles
          Los perfiles de fabricación verificados, con sus números.
""".trimIndent()

/** El informe entero, para quien vaya a leerlo con un script y no con los ojos. */
private val JSON = Json { prettyPrint = true }

// ---------------------------------------------------------------------------- examinar

private fun examinar(args: List<String>): Int {
    val ruta = args.firstOrNull { !it.startsWith("--") } ?: return falta("qué pieza examinar")
    val perfil = opcion(args, "--perfil")
    val comoJson = args.contains("--json")

    val editor = Editor()
    if (!cargar(editor, ruta)) return 2

    val informe = editor.analizarFabricacion(perfil)
        ?: return fallo(editor.ultimoError ?: "No se pudo examinar $ruta")

    if (comoJson) {
        println(JSON.encodeToString(InformeDeFabricacion.serializer(), informe))
        return if (informe.aptoParaImprimir) 0 else 1
    }

    imprimirInforme(ruta, informe)
    return if (informe.aptoParaImprimir) 0 else 1
}

private fun imprimirInforme(ruta: String, informe: InformeDeFabricacion) {
    val m = informe.metricas
    println("Yunkil · examen de $ruta")
    println("Perfil: ${informe.perfil.nombre} (${informe.perfil.origen.etiqueta})")
    println()
    println("Medidas")
    fila("Volumen", "${dec(m.volumen / 1000f, 2)} cm³")
    fila("Altura", "${dec(m.alturaTotal, 1)} mm")
    fila("Huella", "${dec(m.huella.x, 1)} × ${dec(m.huella.z, 1)} mm")
    fila("Espesor mínimo", "${dec(m.espesorMinimo, 2)} mm")
    fila("En voladizo", "${dec(m.fraccionEnVoladizo * 100f, 1)} % de la superficie")
    fila("Base de apoyo", "${dec(m.areaDeContacto, 1)} mm²")
    fila("Medido a", "${dec(m.resolucionDeAnalisis, 2)} mm · ${m.muestras} muestras")

    informe.topologia?.let { t ->
        println()
        println("Malla")
        fila("Estanca", si(t.esCerrada, "sí", "NO — ${t.aristasAbiertas} aristas abiertas"))
        fila("Normales", si(t.estaBienOrientada, "coherentes", "NO — ${t.aristasInvertidas} aristas del revés"))
        fila("Degenerados", t.triangulosDegenerados.toString())
    }

    println()
    if (informe.hallazgos.isEmpty()) {
        println("Sin avisos. La pieza pasa el examen de ${informe.perfil.nombre}.")
        return
    }

    println("Avisos: ${informe.resumen}")
    // De lo que va a fallar a lo que solo se puede mejorar, y dentro de cada grupo por
    // superficie afectada: un aviso que toca 400 mm² importa más que uno que toca 3.
    val orden = informe.hallazgos.sortedWith(
        compareByDescending<yunkil.fabricacion.Hallazgo> { it.severidad.peso }
            .thenByDescending { it.areaAfectada }
    )
    for (h in orden) {
        println()
        println("  ${h.severidad.etiqueta.uppercase()}  ${h.regla.etiqueta}${enPieza(h.piezaNombre)}")
        println("    ${dec(h.medido, 2)} ${h.unidad} medidos contra ${dec(h.umbral, 2)} ${h.unidad} del perfil" +
            (if (h.areaAfectada > 0f) " · ${dec(h.areaAfectada, 0)} mm² afectados" else ""))
        println("    ${h.detalle}")
        for (correccion in h.correcciones) println("    → ${correccion.etiqueta}")
    }
}

// ---------------------------------------------------------------------------- exportar

private fun exportar(args: List<String>): Int {
    val sueltos = args.filter { !it.startsWith("--") }
    val entrada = sueltos.getOrNull(0) ?: return falta("qué documento exportar")
    val salida = sueltos.getOrNull(1) ?: return falta("dónde escribir la pieza")
    val detalle = opcion(args, "--detalle")?.toFloatOrNull()

    val editor = Editor()
    if (!cargar(editor, entrada)) return 2

    val resolucion = detalle ?: editor.resolucionSugerida()
    println("Mallando a ${dec(resolucion, 2)} mm…")

    val certificado = editor.exportarPieza(salida, resolucion)
        ?: return fallo(editor.ultimoError ?: "No se pudo exportar $entrada")

    println()
    println(certificado.resumen())
    if (!certificado.apto) {
        println()
        println("No se ha escrito nada: una malla que no se certifica no se entrega.")
        return 1
    }
    println()
    println("Escrito: $salida (${certificado.bytes} bytes)")
    return 0
}

// ---------------------------------------------------------------------------- perfiles

private fun perfiles(): Int {
    println("Perfiles de fabricación verificados:")
    for (p in PerfilFabricacion.VERIFICADOS) {
        println()
        println("  ${p.nombre}")
        fila("Boquilla", "${dec(p.boquilla, 2)} mm · capa ${dec(p.alturaCapa, 2)} mm", 4)
        fila("Voladizo máximo", "${dec(p.anguloVoladizoMaximo, 0)}°", 4)
        fila("Holgura de encaje", "${dec(p.holguraEncaje, 2)} mm", 4)
        fila("Origen", p.origen.etiqueta, 4)
    }
    return 0
}

// ------------------------------------------------------------------------------ apoyo

/**
 * Carga la pieza mirando la extensión.
 *
 * Un `.yunkil` es el documento paramétrico entero; cualquier otra cosa se trata como malla
 * y entra por el mismo camino que arrastrar un STL a la ventana: se hornea a campo de
 * distancias, que es lo que sabe medir el analizador.
 */
private fun cargar(editor: Editor, ruta: String): Boolean {
    if (ruta.lowercase().endsWith(".yunkil")) {
        val texto = leerArchivo(ruta)?.decodeToString() ?: run {
            fallo("No se pudo leer $ruta"); return false
        }
        if (!editor.desdeJson(texto)) {
            fallo(editor.ultimoError ?: "$ruta no es un documento de Yunkil"); return false
        }
        return true
    }
    if (!editor.importarMalla(ruta)) {
        fallo(editor.ultimoError ?: "No se pudo importar $ruta"); return false
    }
    return true
}

private fun opcion(args: List<String>, nombre: String): String? {
    val i = args.indexOf(nombre)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun fila(etiqueta: String, valor: String, sangria: Int = 2) {
    val hueco = " ".repeat(sangria)
    println("$hueco${etiqueta.padEnd(22 - sangria)}$valor")
}

private fun si(condicion: Boolean, siSi: String, siNo: String) = if (condicion) siSi else siNo

private fun enPieza(nombre: String?) = if (nombre.isNullOrBlank()) "" else " · en $nombre"

private fun dec(v: Float, decimales: Int) = AnalizadorFdm.redondear(v, decimales)

private fun falta(que: String): Int {
    println("Falta decir $que.\n")
    println(AYUDA)
    return 2
}

/** No se llama `error` para no tapar el `error` de la biblioteca estándar. */
private fun fallo(motivo: String): Int {
    println("Error: $motivo")
    return 2
}
