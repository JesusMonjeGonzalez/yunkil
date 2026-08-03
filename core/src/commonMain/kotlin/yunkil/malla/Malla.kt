package yunkil.malla

import yunkil.kernel.Aabb
import yunkil.kernel.Vec3
import yunkil.kernel.maxOf
import yunkil.kernel.minOf
import kotlin.math.abs

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
     * Comprueba que la superficie es cerrada y coherente.
     *
     * Una malla es apta para imprimir si cada arista la comparten exactamente dos
     * triángulos, y si esos dos la recorren en sentidos opuestos. Lo segundo es lo
     * que garantiza que no hay caras del revés, y casi nadie lo comprueba.
     */
    fun revisarTopologia(): TopologiaDeMalla {
        val vistas = HashMap<Long, Int>(numeroDeTriangulos * 2)
        var degenerados = 0

        for (t in 0 until numeroDeTriangulos) {
            val i = triangulos[t * 3]
            val j = triangulos[t * 3 + 1]
            val k = triangulos[t * 3 + 2]
            if (i == j || j == k || i == k) {
                degenerados++
                continue
            }
            for ((a, b) in listOf(i to j, j to k, k to i)) {
                // Se guarda la arista sin orientar como clave y se cuenta el sentido:
                // +1 si se recorre de menor a mayor índice, −1 al revés.
                val clave = if (a < b) a.toLong() * PRIMO + b else b.toLong() * PRIMO + a
                val sentido = if (a < b) 1 else -1
                vistas[clave] = (vistas[clave] ?: 0) + sentido
            }
        }

        var abiertas = 0
        var invertidas = 0
        for ((_, balance) in vistas) {
            // Cerrada y bien orientada: +1 y −1 se cancelan.
            if (balance != 0) {
                if (abs(balance) == 1) abiertas++ else invertidas++
            }
        }

        return TopologiaDeMalla(
            aristas = vistas.size,
            aristasAbiertas = abiertas,
            aristasInvertidas = invertidas,
            triangulosDegenerados = degenerados,
        )
    }

    private companion object {
        const val PRIMO = 2_654_435_761L
    }
}

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
