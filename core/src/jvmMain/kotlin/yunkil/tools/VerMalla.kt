package yunkil.tools

import yunkil.doc.ModelosDemo
import yunkil.imagen.Vistas
import yunkil.kernel.CampoDeMalla
import yunkil.malla.ContorneadoDual
import yunkil.malla.LectorStl
import java.io.File

/**
 * La cadena de la malla importada, de punta a punta y con una imagen al final.
 *
 * Exporta un demo a STL, lo lee de disco como lo leería un archivo descargado, lo
 * hornea a campo y dibuja el resultado. Sirve para lo que ninguna prueba numérica
 * puede: **ver** si lo que entra por el importador es la pieza o un ladrillo.
 *
 *     ./gradlew :core:verMalla
 */
fun main() {
    val nodo = ModelosDemo.soporte().compilar() ?: error("el demo no compila")

    val malla = ContorneadoDual(nodo, 0.5f).generar()
    val stl = File("build/soporte.stl")
    stl.parentFile.mkdirs()
    stl.writeBytes(yunkil.malla.Stl.binario(malla, "Yunkil verMalla"))
    println("STL escrito: ${stl.length()} bytes · ${malla.numeroDeTriangulos} triángulos")

    val leida = LectorStl.leer(stl.readBytes())
    val importada = (leida as? LectorStl.Resultado.Leida)?.malla
        ?: error("el lector rechazó el STL: $leida")
    println("Releído: ${importada.numeroDeTriangulos} triángulos, binario=${(leida).esBinario}")

    val campo = CampoDeMalla.hornear(importada.vertices, importada.triangulos, 0.6f, origen = stl.path)
    println("Horneado: ${campo.anchoEnCeldas}×${campo.altoEnCeldas}×${campo.fondoEnCeldas} celdas")

    File("build/malla-importada.png").writeBytes(Vistas.cuatroVistas(campo, lado = 240))
    File("build/malla-original.png").writeBytes(Vistas.cuatroVistas(nodo, lado = 240))
    println("Dibujadas build/malla-original.png y build/malla-importada.png")
}
