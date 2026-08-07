package yunkil.malla

import yunkil.kernel.Aabb
import yunkil.kernel.Vec3
import yunkil.kernel.maxOf
import yunkil.kernel.minOf

/**
 * Malla triangular indexada.
 *
 * Es un resultado intermedio, no el modelo: Yunkil solo la produce al exportar,
 * a partir del campo de distancias, que sigue siendo la única fuente de verdad.
 */
class Malla(
    val vertices: FloatArray,
    val triangulos: IntArray,
) {
    val numeroDeVertices: Int get() = vertices.size / 3
    val numeroDeTriangulos: Int get() = triangulos.size / 3

    fun vertice(i: Int) = Vec3(vertices[i * 3], vertices[i * 3 + 1], vertices[i * 3 + 2])

    fun cotas(): Aabb {
        if (numeroDeVertices == 0) return Aabb(Vec3.ZERO, Vec3.ZERO)
        var lo = vertice(0)
        var hi = lo
        for (i in 1 until numeroDeVertices) {
            val v = vertice(i)
            lo = minOf(lo, v)
            hi = maxOf(hi, v)
        }
        return Aabb(lo, hi)
    }

    /**
     * Volumen con signo por el teorema de la divergencia.
     *
     * Sale negativo si las normales apuntan hacia dentro, lo que delata una malla
     * con la orientación invertida — un fallo clásico que muchos laminadores
     * aceptan en silencio y luego imprimen del revés.
     */
    fun volumen(): Float {
        var seis = 0.0
        for (t in 0 until numeroDeTriangulos) {
            val a = vertice(triangulos[t * 3])
            val b = vertice(triangulos[t * 3 + 1])
            val c = vertice(triangulos[t * 3 + 2])
            seis += (
                a.x.toDouble() * (b.y.toDouble() * c.z - c.y.toDouble() * b.z) -
                    a.y.toDouble() * (b.x.toDouble() * c.z - c.x.toDouble() * b.z) +
                    a.z.toDouble() * (b.x.toDouble() * c.y - c.x.toDouble() * b.y)
                )
        }
        return (seis / 6.0).toFloat()
    }

    fun area(): Float {
        var total = 0.0
        for (t in 0 until numeroDeTriangulos) {
            val a = vertice(triangulos[t * 3])
            val b = vertice(triangulos[t * 3 + 1])
            val c = vertice(triangulos[t * 3 + 2])
            val u = b - a
            val v = c - a
            val cruz = Vec3(
                u.y * v.z - u.z * v.y,
                u.z * v.x - u.x * v.z,
                u.x * v.y - u.y * v.x,
            )
            total += cruz.length() * 0.5
        }
        return total.toFloat()
    }

    /**
     * Cuántas parejas de triángulos se atraviesan, hasta [maximo].
     *
     * Es la comprobación que faltaba para poder decir que una malla está bien. Una
     * superficie puede ser cerrada, estar bien orientada, no tener degenerados y aun
     * así **cruzarse consigo misma**: pasa en las paredes más finas que una celda del
     * contorneado, donde las dos caras caen en celdas vecinas. El laminador entonces
     * no sabe qué es dentro y qué es fuera —el conteo de cruces del relleno deja de
     * cuadrar— y o rellena de más o descarta la pieza.
     *
     * Se para al llegar a [maximo] porque el número exacto no sirve para nada: la
     * decisión es entregar el archivo o no, y para eso basta la primera pareja. Lo
     * que sí importa es no tardar minutos contando las 40.000 de una malla rota.
     *
     * Dos límites dichos por delante. Los triángulos que **comparten un vértice** no
     * se comparan: en una malla cerrada cada uno toca a varios vecinos y contarlos
     * daría cientos de falsos positivos en cualquier pieza correcta. Y se busca el
     * cruce de una arista con la cara del otro, así que dos triángulos exactamente
     * coplanarios y superpuestos no se ven; ese caso no lo produce el contorneado
     * dual, que pone un vértice por celda.
     */
    fun autoIntersecciones(maximo: Int = 8): Int {
        val n = numeroDeTriangulos
        if (n < 2) return 0

        val cotas = cotas()
        val tamano = cotas.size
        val lado = maxOf(tamano.x, maxOf(tamano.y, tamano.z))
        if (!lado.isFinite() || lado <= 0f) return 0

        // Una rejilla con tantas celdas como triángulos deja del orden de un
        // triángulo por celda, que es lo que convierte la comparación de todos contra
        // todos —imposible con 100.000 triángulos— en la de cada uno con sus vecinos.
        val divisiones = cbrtEntero(n).coerceIn(1, 128)
        val paso = lado / divisiones
        val cubos = HashMap<Int, MutableList<Int>>(n)

        fun celdaDe(v: Float, origen: Float) = ((v - origen) / paso).toInt().coerceIn(0, divisiones)
        fun clave(x: Int, y: Int, z: Int) = (z * (divisiones + 1) + y) * (divisiones + 1) + x

        for (t in 0 until n) {
            val a = vertice(triangulos[t * 3])
            val b = vertice(triangulos[t * 3 + 1])
            val c = vertice(triangulos[t * 3 + 2])
            val lo = minOf(a, minOf(b, c))
            val hi = maxOf(a, maxOf(b, c))
            for (z in celdaDe(lo.z, cotas.min.z)..celdaDe(hi.z, cotas.min.z)) {
                for (y in celdaDe(lo.y, cotas.min.y)..celdaDe(hi.y, cotas.min.y)) {
                    for (x in celdaDe(lo.x, cotas.min.x)..celdaDe(hi.x, cotas.min.x)) {
                        cubos.getOrPut(clave(x, y, z)) { ArrayList(4) }.add(t)
                    }
                }
            }
        }

        val encontradas = HashSet<Long>()
        for ((_, dentro) in cubos) {
            for (i in dentro.indices) {
                for (j in i + 1 until dentro.size) {
                    val t = dentro[i]
                    val u = dentro[j]
                    if (comparten(t, u)) continue
                    val pareja = t.toLong() * n + u
                    if (pareja in encontradas) continue
                    if (seCruzan(t, u)) {
                        encontradas.add(pareja)
                        if (encontradas.size >= maximo) return encontradas.size
                    }
                }
            }
        }
        return encontradas.size
    }

    /** `true` si los dos triángulos comparten al menos un vértice de la malla. */
    private fun comparten(t: Int, u: Int): Boolean {
        for (a in 0..2) {
            val va = triangulos[t * 3 + a]
            for (b in 0..2) if (va == triangulos[u * 3 + b]) return true
        }
        return false
    }

    /** Cruce real: alguna arista de uno atraviesa la cara del otro. */
    private fun seCruzan(t: Int, u: Int): Boolean {
        val a0 = vertice(triangulos[t * 3])
        val a1 = vertice(triangulos[t * 3 + 1])
        val a2 = vertice(triangulos[t * 3 + 2])
        val b0 = vertice(triangulos[u * 3])
        val b1 = vertice(triangulos[u * 3 + 1])
        val b2 = vertice(triangulos[u * 3 + 2])

        return atraviesa(a0, a1, b0, b1, b2) || atraviesa(a1, a2, b0, b1, b2) ||
            atraviesa(a2, a0, b0, b1, b2) || atraviesa(b0, b1, a0, a1, a2) ||
            atraviesa(b1, b2, a0, a1, a2) || atraviesa(b2, b0, a0, a1, a2)
    }

    /**
     * Segmento contra triángulo, por Möller–Trumbore.
     *
     * El epsilon deja fuera los toques por el borde: dos triángulos que se rozan por
     * una arista compartida entre vértices distintos no son un cruce, son una junta,
     * y denunciarla llenaría el informe de ruido.
     */
    private fun atraviesa(p: Vec3, q: Vec3, a: Vec3, b: Vec3, c: Vec3): Boolean {
        val eps = 1e-6f
        val direccion = q - p
        val ab = b - a
        val ac = c - a
        val h = Vec3(
            direccion.y * ac.z - direccion.z * ac.y,
            direccion.z * ac.x - direccion.x * ac.z,
            direccion.x * ac.y - direccion.y * ac.x,
        )
        val det = ab.x * h.x + ab.y * h.y + ab.z * h.z
        if (det > -eps && det < eps) return false // paralelo al plano
        val inverso = 1f / det
        val s = p - a
        val u = inverso * (s.x * h.x + s.y * h.y + s.z * h.z)
        if (u < eps || u > 1f - eps) return false
        val cruz = Vec3(
            s.y * ab.z - s.z * ab.y,
            s.z * ab.x - s.x * ab.z,
            s.x * ab.y - s.y * ab.x,
        )
        val v = inverso * (direccion.x * cruz.x + direccion.y * cruz.y + direccion.z * cruz.z)
        if (v < eps || u + v > 1f - eps) return false
        val distancia = inverso * (ac.x * cruz.x + ac.y * cruz.y + ac.z * cruz.z)
        return distancia > eps && distancia < 1f - eps
    }

    /** Raíz cúbica entera, sin depender de que la plataforma traiga `cbrt`. */
    private fun cbrtEntero(n: Int): Int {
        var r = 1
        while ((r + 1) * (r + 1) * (r + 1) <= n) r++
        return r
    }

    /**
     * Comprueba que la superficie es cerrada y coherente.
     *
     * Una malla es apta para imprimir si cada arista la comparten exactamente dos
     * triángulos, y si esos dos la recorren en sentidos opuestos. Lo segundo es lo
     * que garantiza que no hay caras del revés, y casi nadie lo comprueba.
     *
     * Un triángulo solo se descarta si sus **índices** no forman un triángulo de
     * verdad: repetidos, fuera de rango o con vértices no finitos. Un sliver —área
     * casi cero porque dos vértices casi coinciden, típico de las esquinas vivas del
     * contorneado dual— sigue siendo parte de la malla: comparte sus aristas con los
     * vecinos y no aporta volumen. Si se salta, esas aristas pierden su pareja y una
     * malla que en realidad está cerrada sale como abierta. Ese era exactamente el
     * fallo que hacía fallar la escuadra en L.
     */
    fun revisarTopologia(): TopologiaDeMalla {
        data class Arista(val a: Int, val b: Int)
        data class Incidencias(var cantidad: Int = 0, var balance: Int = 0)
        val vistas = HashMap<Arista, Incidencias>(numeroDeTriangulos * 2)
        var degenerados = 0

        for (t in 0 until numeroDeTriangulos) {
            val i = triangulos[t * 3]
            val j = triangulos[t * 3 + 1]
            val k = triangulos[t * 3 + 2]
            if (i !in 0 until numeroDeVertices || j !in 0 until numeroDeVertices || k !in 0 until numeroDeVertices || i == j || j == k || i == k) {
                degenerados++
                continue
            }
            val va = vertice(i); val vb = vertice(j); val vc = vertice(k)
            val finitos = listOf(va.x, va.y, va.z, vb.x, vb.y, vb.z, vc.x, vc.y, vc.z).all { it.isFinite() }
            if (!finitos) {
                degenerados++
                continue
            }
            for ((a, b) in listOf(i to j, j to k, k to i)) {
                // Se guarda la arista sin orientar como clave y se cuenta el sentido:
                // +1 si se recorre de menor a mayor índice, −1 al revés.
                val clave = if (a < b) Arista(a, b) else Arista(b, a)
                val sentido = if (a < b) 1 else -1
                val incidencias = vistas.getOrPut(clave) { Incidencias() }
                incidencias.cantidad++
                incidencias.balance += sentido
            }
        }

        var abiertas = 0
        var invertidas = 0
        for ((_, incidencias) in vistas) {
            // Cerrada y bien orientada: +1 y −1 se cancelan.
            if (incidencias.cantidad != 2) abiertas++
            else if (incidencias.balance != 0) invertidas++
        }

        return TopologiaDeMalla(
            aristas = vistas.size,
            aristasAbiertas = abiertas,
            aristasInvertidas = invertidas,
            triangulosDegenerados = degenerados,
        )
    }

}

@kotlinx.serialization.Serializable
data class TopologiaDeMalla(
    val aristas: Int,
    val aristasAbiertas: Int,
    val aristasInvertidas: Int,
    val triangulosDegenerados: Int,
) {
    val esCerrada: Boolean get() = aristasAbiertas == 0
    val estaBienOrientada: Boolean get() = aristasInvertidas == 0
    val esImprimible: Boolean get() = esCerrada && estaBienOrientada && triangulosDegenerados == 0
}

// ------------------------------------------------------------------ exportación

object Stl {

    /**
     * STL binario. Cada triángulo lleva su normal calculada, no ceros: hay
     * laminadores que se fían de ella y una normal nula los descoloca.
     */
    fun binario(malla: Malla, cabecera: String = "Yunkil"): ByteArray {
        val n = malla.numeroDeTriangulos
        val salida = ByteArray(84 + n * 50)
        var p = 0

        val textoCabecera = cabecera.encodeToByteArray()
        for (i in 0 until 80) {
            salida[i] = if (i < textoCabecera.size) textoCabecera[i] else 0
        }
        p = 80
        p = escribirEntero(salida, p, n)

        for (t in 0 until n) {
            val a = malla.vertice(malla.triangulos[t * 3])
            val b = malla.vertice(malla.triangulos[t * 3 + 1])
            val c = malla.vertice(malla.triangulos[t * 3 + 2])

            val u = b - a
            val v = c - a
            var normal = Vec3(
                u.y * v.z - u.z * v.y,
                u.z * v.x - u.x * v.z,
                u.x * v.y - u.y * v.x,
            )
            val longitud = normal.length()
            normal = if (longitud > 0f) normal / longitud else Vec3(0f, 0f, 1f)

            p = escribirVector(salida, p, normal)
            p = escribirVector(salida, p, a)
            p = escribirVector(salida, p, b)
            p = escribirVector(salida, p, c)
            // Atributo de 16 bits, sin uso.
            salida[p++] = 0
            salida[p++] = 0
        }
        return salida
    }

    private fun escribirVector(destino: ByteArray, posicion: Int, v: Vec3): Int {
        var p = posicion
        p = escribirFlotante(destino, p, v.x)
        p = escribirFlotante(destino, p, v.y)
        p = escribirFlotante(destino, p, v.z)
        return p
    }

    private fun escribirFlotante(destino: ByteArray, posicion: Int, valor: Float): Int =
        escribirEntero(destino, posicion, valor.toRawBits())

    /** Entero de 32 bits en little-endian, que es lo que exige el formato. */
    private fun escribirEntero(destino: ByteArray, posicion: Int, valor: Int): Int {
        destino[posicion] = (valor and 0xFF).toByte()
        destino[posicion + 1] = ((valor ushr 8) and 0xFF).toByte()
        destino[posicion + 2] = ((valor ushr 16) and 0xFF).toByte()
        destino[posicion + 3] = ((valor ushr 24) and 0xFF).toByte()
        return posicion + 4
    }
}
