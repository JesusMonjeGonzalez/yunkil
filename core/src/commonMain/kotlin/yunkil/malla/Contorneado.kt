package yunkil.malla

import yunkil.kernel.SdfNode
import yunkil.kernel.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Convierte el campo de distancias en una malla triangular por contorneado dual.
 *
 * Se usa contorneado dual y no marching cubes porque el primero coloca un vértice
 * por celda resolviendo la intersección de los planos tangentes, y eso **conserva
 * las aristas vivas**. Marching cubes redondearía cada esquina de la pieza, que
 * para algo destinado a imprimirse es inaceptable.
 *
 * Dos decisiones de implementación gobiernan el consumo de recursos:
 *
 * 1. **Se recorre en láminas.** Solo hay dos planos de muestras vivos a la vez, así
 *    que la memoria crece con el cuadrado de la resolución y no con el cubo. A 0,1 mm
 *    una pieza de 100 mm necesitaría 4 GB con una rejilla completa; en láminas son
 *    unos pocos megas.
 * 2. **Se salta el vacío.** El campo *es* una distancia, así que al recorrer una
 *    fila se sabe cuántas celdas se pueden ignorar sin volver a evaluar. La inmensa
 *    mayoría del volumen está lejos de la superficie y sale gratis.
 */
class ContorneadoDual(
    private val nodo: SdfNode,
    private val resolucion: Float,
) {

    init {
        require(resolucion.isFinite() && resolucion > 0f) { "La resolución debe ser finita y positiva" }
    }

    /** Aviso de progreso entre 0 y 1. Se llama pocas veces, no en el bucle interno. */
    var alAvanzar: ((Float) -> Unit)? = null

    fun generar(): Malla {
        val cotas = nodo.cotas().expanded(resolucion * 2f)
        val origen = cotas.min
        val tamano = cotas.size

        val nx = max(ceil(tamano.x / resolucion).toInt(), 1)
        val ny = max(ceil(tamano.y / resolucion).toInt(), 1)
        val nz = max(ceil(tamano.z / resolucion).toInt(), 1)

        val anchoPlano = (nx + 1) * (ny + 1)
        var muestrasZ = FloatArray(anchoPlano)
        var muestrasZ1 = FloatArray(anchoPlano)

        val vertices = ArrayList<Float>(4096)
        val triangulos = ArrayList<Int>(8192)

        // Índice del vértice de cada celda, o −1. Dos planos: el anterior y el actual.
        var celdasPrevias = IntArray(nx * ny) { -1 }
        var celdasActuales = IntArray(nx * ny) { -1 }

        muestrear(muestrasZ, origen, nx, ny, 0)

        for (z in 0 until nz) {
            muestrear(muestrasZ1, origen, nx, ny, z + 1)

            celdasActuales.fill(-1)

            // --- vértices de las celdas de este plano
            for (y in 0 until ny) {
                for (x in 0 until nx) {
                    val esquinas = leerEsquinas(muestrasZ, muestrasZ1, nx, x, y)
                    if (!hayCambioDeSigno(esquinas)) continue

                    val base = Vec3(
                        origen.x + x * resolucion,
                        origen.y + y * resolucion,
                        origen.z + z * resolucion,
                    )
                    val v = resolverVertice(esquinas, base)
                    celdasActuales[y * nx + x] = vertices.size / 3
                    vertices.add(v.x); vertices.add(v.y); vertices.add(v.z)
                }
            }

            // --- caras
            emitirCaras(
                muestrasZ, muestrasZ1, celdasPrevias, celdasActuales,
                nx, ny, z, triangulos,
            )

            // Rotar planos: lo que era z+1 pasa a ser z, y el buffer viejo se reutiliza.
            val intercambio = muestrasZ
            muestrasZ = muestrasZ1
            muestrasZ1 = intercambio

            val intercambioCeldas = celdasPrevias
            celdasPrevias = celdasActuales
            celdasActuales = intercambioCeldas

            if (z % 16 == 0) alAvanzar?.invoke(z.toFloat() / nz)
        }

        alAvanzar?.invoke(1f)
        return Malla(vertices.toFloatArray(), triangulos.toIntArray())
    }

    // ------------------------------------------------------------------ muestreo

    /**
     * Rellena un plano de muestras aprovechando que el campo es una distancia:
     * si el valor en un punto es grande, las celdas siguientes de la fila no pueden
     * contener superficie y se rellenan sin volver a evaluar.
     */
    private fun muestrear(destino: FloatArray, origen: Vec3, nx: Int, ny: Int, z: Int) {
        val zz = origen.z + z * resolucion
        val umbral = resolucion * 1.75f

        for (y in 0..ny) {
            val yy = origen.y + y * resolucion
            val fila = y * (nx + 1)
            var x = 0
            while (x <= nx) {
                val d = nodo.evaluar(Vec3(origen.x + x * resolucion, yy, zz))
                destino[fila + x] = d
                x++

                val margen = abs(d) - umbral
                if (margen > 0f) {
                    // Cuántas celdas caben con seguridad dentro de la distancia libre.
                    val saltos = (margen / resolucion).toInt()
                    if (saltos > 0) {
                        val signo = if (d < 0f) -1f else 1f
                        var k = 1
                        while (k <= saltos && x <= nx) {
                            // Cota conservadora: mantiene el signo, que es lo único
                            // que importa donde no hay superficie.
                            destino[fila + x] = signo * (abs(d) - k * resolucion)
                            x++
                            k++
                        }
                    }
                }
            }
        }
    }

    private fun leerEsquinas(
        planoZ: FloatArray,
        planoZ1: FloatArray,
        nx: Int,
        x: Int,
        y: Int,
    ): FloatArray {
        val f0 = y * (nx + 1) + x
        val f1 = (y + 1) * (nx + 1) + x
        return floatArrayOf(
            planoZ[f0], planoZ[f0 + 1], planoZ[f1 + 1], planoZ[f1],
            planoZ1[f0], planoZ1[f0 + 1], planoZ1[f1 + 1], planoZ1[f1],
        )
    }

    private fun hayCambioDeSigno(esquinas: FloatArray): Boolean {
        val primero = esquinas[0] < 0f
        for (i in 1 until 8) if ((esquinas[i] < 0f) != primero) return true
        return false
    }

    // ------------------------------------------------------------------ vértice

    /**
     * Sitúa el vértice de la celda donde mejor se ajustan los planos tangentes de
     * los cortes. Se resuelve moviendo una partícula contra el error de cada plano
     * en lugar de descomponer la matriz: converge en unas pocas iteraciones, no
     * necesita álgebra densa, y no se degrada cuando los planos son casi paralelos.
     */
    private fun resolverVertice(esquinas: FloatArray, base: Vec3): Vec3 {
        val puntos = ArrayList<Vec3>(12)
        val normales = ArrayList<Vec3>(12)

        for ((a, b) in ARISTAS) {
            val da = esquinas[a]
            val db = esquinas[b]
            if ((da < 0f) == (db < 0f)) continue

            val t = (da / (da - db)).coerceIn(0f, 1f)
            val pa = base + ESQUINAS[a] * resolucion
            val pb = base + ESQUINAS[b] * resolucion
            val corte = pa + (pb - pa) * t

            puntos.add(corte)
            normales.add(gradiente(corte))
        }

        if (puntos.isEmpty()) return base + Vec3.splat(resolucion * 0.5f)

        var x = Vec3.ZERO
        for (p in puntos) x += p
        x /= puntos.size.toFloat()

        repeat(ITERACIONES) {
            var fuerza = Vec3.ZERO
            for (i in puntos.indices) {
                val n = normales[i]
                val error = (puntos[i] - x).let { n.x * it.x + n.y * it.y + n.z * it.z }
                fuerza += n * error
            }
            x += fuerza * (RELAJACION / puntos.size)
        }

        // El vértice no puede escaparse de su celda: si lo hiciera, las caras que
        // lo comparten se cruzarían y la malla dejaría de ser válida.
        return Vec3(
            x.x.coerceIn(base.x, base.x + resolucion),
            x.y.coerceIn(base.y, base.y + resolucion),
            x.z.coerceIn(base.z, base.z + resolucion),
        )
    }

    private fun gradiente(p: Vec3): Vec3 {
        val e = resolucion * 0.05f
        val g = Vec3(
            nodo.evaluar(Vec3(p.x + e, p.y, p.z)) - nodo.evaluar(Vec3(p.x - e, p.y, p.z)),
            nodo.evaluar(Vec3(p.x, p.y + e, p.z)) - nodo.evaluar(Vec3(p.x, p.y - e, p.z)),
            nodo.evaluar(Vec3(p.x, p.y, p.z + e)) - nodo.evaluar(Vec3(p.x, p.y, p.z - e)),
        )
        val len = g.length()
        return if (len < 1e-12f) Vec3(0f, 1f, 0f) else g / len
    }

    // ------------------------------------------------------------------ caras

    /**
     * Emite un cuadrilátero por cada arista de la rejilla que cruza la superficie,
     * uniendo los vértices de las cuatro celdas que la comparten. El orden de giro
     * lo decide el signo: la normal siempre sale del material hacia fuera.
     */
    private fun emitirCaras(
        planoZ: FloatArray,
        planoZ1: FloatArray,
        celdasPrevias: IntArray,
        celdasActuales: IntArray,
        nx: Int,
        ny: Int,
        z: Int,
        salida: ArrayList<Int>,
    ) {
        fun celda(plano: IntArray, x: Int, y: Int): Int =
            if (x in 0 until nx && y in 0 until ny) plano[y * nx + x] else -1

        // Aristas en X e Y: sus cuatro celdas se reparten entre este plano y el anterior.
        if (z >= 1) {
            for (y in 1 until ny) {
                for (x in 0 until nx) {
                    val d0 = planoZ[y * (nx + 1) + x]
                    val d1 = planoZ[y * (nx + 1) + x + 1]
                    if ((d0 < 0f) == (d1 < 0f)) continue
                    // Orden antihorario visto desde +X: (y−1,z−1) → (y,z−1) →
                    // (y,z) → (y−1,z). Recorrerlo al revés invierte la normal.
                    val a = celda(celdasPrevias, x, y - 1)
                    val b = celda(celdasPrevias, x, y)
                    val c = celda(celdasActuales, x, y)
                    val d = celda(celdasActuales, x, y - 1)
                    anadirCuadrilatero(salida, a, b, c, d, d0 < 0f)
                }
            }
            for (y in 0 until ny) {
                for (x in 1 until nx) {
                    val d0 = planoZ[y * (nx + 1) + x]
                    val d1 = planoZ[(y + 1) * (nx + 1) + x]
                    if ((d0 < 0f) == (d1 < 0f)) continue
                    val a = celda(celdasPrevias, x - 1, y)
                    val b = celda(celdasActuales, x - 1, y)
                    val c = celda(celdasActuales, x, y)
                    val d = celda(celdasPrevias, x, y)
                    anadirCuadrilatero(salida, a, b, c, d, d0 < 0f)
                }
            }
        }

        // Aristas en Z: sus cuatro celdas viven todas en este plano.
        for (y in 1 until ny) {
            for (x in 1 until nx) {
                val d0 = planoZ[y * (nx + 1) + x]
                val d1 = planoZ1[y * (nx + 1) + x]
                if ((d0 < 0f) == (d1 < 0f)) continue
                val a = celda(celdasActuales, x - 1, y - 1)
                val b = celda(celdasActuales, x, y - 1)
                val c = celda(celdasActuales, x, y)
                val d = celda(celdasActuales, x - 1, y)
                anadirCuadrilatero(salida, a, b, c, d, d0 < 0f)
            }
        }
    }

    private fun anadirCuadrilatero(
        salida: ArrayList<Int>,
        a: Int, b: Int, c: Int, d: Int,
        haciaDelante: Boolean,
    ) {
        if (a < 0 || b < 0 || c < 0 || d < 0) return
        if (haciaDelante) {
            salida.add(a); salida.add(b); salida.add(c)
            salida.add(a); salida.add(c); salida.add(d)
        } else {
            salida.add(a); salida.add(c); salida.add(b)
            salida.add(a); salida.add(d); salida.add(c)
        }
    }

    private companion object {
        const val ITERACIONES = 24
        const val RELAJACION = 0.65f

        /** Las ocho esquinas del cubo unidad, en el orden que usa `leerEsquinas`. */
        val ESQUINAS = arrayOf(
            Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(1f, 1f, 0f), Vec3(0f, 1f, 0f),
            Vec3(0f, 0f, 1f), Vec3(1f, 0f, 1f), Vec3(1f, 1f, 1f), Vec3(0f, 1f, 1f),
        )

        /** Las doce aristas del cubo como pares de esquinas. */
        val ARISTAS = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7,
        )
    }
}

/** Estimación del coste antes de mallar, para poder avisar en la interfaz. */
fun estimarCeldas(nodo: SdfNode, resolucion: Float): Long {
    val t = nodo.cotas().expanded(resolucion * 2f).size
    val nx = max(ceil(t.x / resolucion).toLong(), 1L)
    val ny = max(ceil(t.y / resolucion).toLong(), 1L)
    val nz = max(ceil(t.z / resolucion).toLong(), 1L)
    return nx * ny * nz
}

/** Resolución sugerida: fina, pero acotada para que el mallado no se dispare. */
fun resolucionSugerida(nodo: SdfNode): Float {
    val radio = max(nodo.cotas().radius, 1f)
    return min(max(radio / 140f, 0.05f), 1f)
}
