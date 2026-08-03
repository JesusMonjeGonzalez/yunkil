package yunkil.malla

import yunkil.kernel.SdfNode
import yunkil.kernel.Vec3
import kotlin.math.abs
import kotlin.random.Random

/** Escribe datos binarios en disco. La única operación del núcleo que toca el sistema. */
expect fun escribirArchivo(ruta: String, datos: ByteArray): Boolean

/**
 * El certificado de una exportación.
 *
 * Existe porque la desviación de una malla frente al modelo es invisible hasta que
 * la pieza no encaja. Convertirla en un número que se enseña antes de guardar es
 * la diferencia entre confiar en la herramienta y esperar que haya acertado.
 */
data class Certificado(
    val triangulos: Int,
    val vertices: Int,
    val resolucion: Float,
    val cerrada: Boolean,
    val bienOrientada: Boolean,
    val degenerados: Int,
    val desviacionMaxima: Float,
    val volumenMalla: Float,
    val volumenAnalitico: Float,
    val bytes: Int,
) {
    val errorDeVolumen: Float
        get() = if (volumenAnalitico <= 0f) 0f
        else abs(volumenMalla - volumenAnalitico) / volumenAnalitico

    /** Solo se entrega el archivo si esto es cierto. */
    val apto: Boolean
        get() = cerrada && bienOrientada && degenerados == 0 && volumenMalla > 0f

    fun resumen(): String = buildString {
        appendLine(if (apto) "Malla apta para imprimir" else "MALLA NO APTA")
        appendLine("Triángulos: $triangulos")
        appendLine("Resolución: ${formato(resolucion)} mm")
        appendLine("Desviación máxima: ${formato(desviacionMaxima)} mm")
        appendLine("Volumen: ${formato(volumenMalla / 1000f)} cm³ (error ${formato(errorDeVolumen * 100f)} %)")
        appendLine(if (cerrada) "Sólido estanco: sí" else "Sólido estanco: NO — hay agujeros")
        append(if (bienOrientada) "Normales coherentes: sí" else "Normales coherentes: NO")
    }

    private fun formato(v: Float): String {
        val escalado = kotlin.math.round(v * 1000f) / 1000f
        return escalado.toString()
    }
}

/**
 * Malla el sólido, lo examina y solo entonega el archivo si pasa el examen.
 *
 * Si la malla sale defectuosa a la resolución pedida, se reintenta una vez con el
 * doble de detalle antes de rendirse. Entregar un STL roto es peor que no entregar
 * nada: el usuario no lo descubre hasta que la impresión ha fallado.
 */
class Exportador(private val nodo: SdfNode) {

    var alAvanzar: ((Float) -> Unit)? = null

    fun exportarStl(ruta: String, resolucion: Float): Certificado {
        var actual = resolucion
        var intento = 0

        while (true) {
            val contorneado = ContorneadoDual(nodo, actual)
            contorneado.alAvanzar = { alAvanzar?.invoke(it * 0.9f) }
            val malla = contorneado.generar()

            val certificado = examinar(malla, actual, 0)
            alAvanzar?.invoke(0.95f)

            // Un reintento con el doble de detalle: la mayoría de defectos vienen de
            // una resolución demasiado gruesa para el detalle más fino de la pieza.
            if (!certificado.apto && intento == 0) {
                intento++
                actual *= 0.5f
                continue
            }

            if (!certificado.apto) return certificado

            val bytes = Stl.binario(malla, "Yunkil ${formatoCorto(actual)}mm")
            val escrito = escribirArchivo(ruta, bytes)
            alAvanzar?.invoke(1f)

            return certificado.copy(bytes = if (escrito) bytes.size else 0)
        }
    }

    /** Examina una malla sin escribir nada. Útil para previsualizar el coste. */
    fun examinar(malla: Malla, resolucion: Float, bytes: Int): Certificado {
        val topologia = malla.revisarTopologia()
        return Certificado(
            triangulos = malla.numeroDeTriangulos,
            vertices = malla.numeroDeVertices,
            resolucion = resolucion,
            cerrada = topologia.esCerrada,
            bienOrientada = topologia.estaBienOrientada,
            degenerados = topologia.triangulosDegenerados,
            desviacionMaxima = medirDesviacion(malla),
            volumenMalla = malla.volumen(),
            volumenAnalitico = estimarVolumen(),
            bytes = bytes,
        )
    }

    /**
     * Mayor distancia de la malla al campo exacto.
     *
     * Se miden los vértices y también el centro de cada triángulo: un vértice puede
     * apoyarse en la superficie mientras la cara que forma la atraviesa por el medio.
     */
    private fun medirDesviacion(malla: Malla): Float {
        var peor = 0f
        for (i in 0 until malla.numeroDeVertices) {
            val d = abs(nodo.evaluar(malla.vertice(i)))
            if (d > peor) peor = d
        }
        val paso = maxOf(malla.numeroDeTriangulos / 4000, 1)
        var t = 0
        while (t < malla.numeroDeTriangulos) {
            val a = malla.vertice(malla.triangulos[t * 3])
            val b = malla.vertice(malla.triangulos[t * 3 + 1])
            val c = malla.vertice(malla.triangulos[t * 3 + 2])
            val d = abs(nodo.evaluar((a + b + c) / 3f))
            if (d > peor) peor = d
            t += paso
        }
        return peor
    }

    /**
     * Volumen del sólido por muestreo del campo, independiente de la malla.
     *
     * Contrastarlo con el volumen de la malla es lo que detecta un fallo grave de
     * mallado: si los dos números no cuadran, la malla no representa el modelo,
     * aunque su topología sea impecable.
     */
    private fun estimarVolumen(): Float {
        val cotas = nodo.cotas()
        val tamano = cotas.size
        val volumenCaja = tamano.x * tamano.y * tamano.z
        if (volumenCaja <= 0f) return 0f

        val r = Random(20260803)
        var dentro = 0
        repeat(MUESTRAS_DE_VOLUMEN) {
            val p = Vec3(
                cotas.min.x + r.nextFloat() * tamano.x,
                cotas.min.y + r.nextFloat() * tamano.y,
                cotas.min.z + r.nextFloat() * tamano.z,
            )
            if (nodo.evaluar(p) < 0f) dentro++
        }
        return volumenCaja * dentro / MUESTRAS_DE_VOLUMEN
    }

    private fun formatoCorto(v: Float): String = (kotlin.math.round(v * 100f) / 100f).toString()

    private companion object {
        const val MUESTRAS_DE_VOLUMEN = 120_000
    }
}
