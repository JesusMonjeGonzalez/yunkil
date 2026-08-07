package yunkil.malla

/**
 * Lee un STL, binario o de texto, y devuelve una malla indexada.
 *
 * Trabaja sobre los bytes y no sobre un archivo a propósito: así vive en el código
 * común y se puede verificar sin tocar disco ni depender de la plataforma. Quien lee
 * el archivo es la aplicación, que es la que tiene permisos y diálogo de apertura.
 *
 * Un STL no tiene índices: repite cada vértice en cada triángulo que lo toca, así que
 * un cubo llega con 36 vértices en vez de 8. Aquí se sueldan los que coinciden, y no
 * es cosmético: sin soldar, cada arista se queda sin pareja y el comprobador de
 * topología declara abierta una malla que está perfectamente cerrada.
 */
object LectorStl {

    /** Rejilla de soldadura, en milímetros. Por debajo de esto dos vértices son uno. */
    private const val TOLERANCIA = 1e-4f

    sealed interface Resultado {
        data class Leida(val malla: Malla, val esBinario: Boolean) : Resultado
        data class Fallo(val motivo: String) : Resultado
    }

    fun leer(bytes: ByteArray): Resultado {
        if (bytes.size < 15) return Resultado.Fallo("el archivo está vacío o es demasiado corto")
        return if (pareceBinario(bytes)) binario(bytes) else texto(bytes)
    }

    /**
     * Un STL binario también puede empezar por «solid», así que mirar las primeras
     * letras no basta —es el fallo con el que se atragantan la mitad de los lectores—.
     * Lo que no miente es el tamaño: la cabecera declara cuántos triángulos hay, y en
     * binario el archivo mide exactamente 84 + 50 por triángulo.
     */
    private fun pareceBinario(bytes: ByteArray): Boolean {
        if (bytes.size < 84) return false
        val cuenta = leerEntero(bytes, 80)
        if (cuenta < 0) return false
        return bytes.size.toLong() == 84L + 50L * cuenta
    }

    private fun binario(bytes: ByteArray): Resultado {
        val cuenta = leerEntero(bytes, 80)
        if (cuenta == 0) return Resultado.Fallo("el STL no contiene ningún triángulo")

        val soldador = Soldador(cuenta * 3)
        val triangulos = IntArray(cuenta * 3)

        var p = 84
        for (t in 0 until cuenta) {
            p += 12 // la normal declarada se ignora: se recalcula donde haga falta
            for (v in 0 until 3) {
                val x = leerFlotante(bytes, p)
                val y = leerFlotante(bytes, p + 4)
                val z = leerFlotante(bytes, p + 8)
                p += 12
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
                    return Resultado.Fallo("el triángulo ${t + 1} tiene coordenadas no finitas")
                }
                triangulos[t * 3 + v] = soldador.indiceDe(x, y, z)
            }
            p += 2 // atributo, que casi nadie usa
        }
        return Resultado.Leida(Malla(soldador.vertices(), triangulos), esBinario = true)
    }

    private fun texto(bytes: ByteArray): Resultado {
        val texto = bytes.decodeToString()
        val soldador = Soldador(1024)
        val triangulos = ArrayList<Int>(1024)

        for (linea in texto.lineSequence()) {
            val limpia = linea.trim()
            if (!limpia.startsWith("vertex", ignoreCase = true)) continue
            val partes = limpia.split(' ', '\t').filter { it.isNotEmpty() }
            if (partes.size < 4) return Resultado.Fallo("un «vertex» no trae tres coordenadas")
            val x = partes[1].toFloatOrNull()
            val y = partes[2].toFloatOrNull()
            val z = partes[3].toFloatOrNull()
            if (x == null || y == null || z == null) {
                return Resultado.Fallo("un «vertex» trae algo que no es un número: $limpia")
            }
            triangulos.add(soldador.indiceDe(x, y, z))
        }

        if (triangulos.size < 3) return Resultado.Fallo("el STL no contiene ningún triángulo")
        if (triangulos.size % 3 != 0) {
            return Resultado.Fallo("hay ${triangulos.size} vértices, que no forman triángulos enteros")
        }
        return Resultado.Leida(Malla(soldador.vertices(), triangulos.toIntArray()), esBinario = false)
    }

    // ------------------------------------------------------------------ soldadura

    private class Soldador(capacidad: Int) {
        private val indices = HashMap<Long, Int>(capacidad)
        private val salida = ArrayList<Float>(capacidad * 3)

        fun indiceDe(x: Float, y: Float, z: Float): Int {
            val clave = clave(x, y, z)
            indices[clave]?.let { return it }
            val nuevo = salida.size / 3
            salida.add(x); salida.add(y); salida.add(z)
            indices[clave] = nuevo
            return nuevo
        }

        fun vertices(): FloatArray = FloatArray(salida.size) { salida[it] }

        /**
         * Clave por cuantización. Se usan 21 bits por eje, que a esta tolerancia
         * cubren ±100 metros: de sobra para cualquier cosa que quepa en un plato.
         */
        private fun clave(x: Float, y: Float, z: Float): Long {
            fun q(v: Float): Long = ((v / TOLERANCIA).toLong()) and 0x1FFFFF
            return (q(x) shl 42) or (q(y) shl 21) or q(z)
        }
    }

    // ------------------------------------------------------------------ bytes

    /** Entero de 32 bits, little endian, que es lo que fija el formato. */
    private fun leerEntero(bytes: ByteArray, en: Int): Int =
        (bytes[en].toInt() and 0xFF) or
            ((bytes[en + 1].toInt() and 0xFF) shl 8) or
            ((bytes[en + 2].toInt() and 0xFF) shl 16) or
            ((bytes[en + 3].toInt() and 0xFF) shl 24)

    private fun leerFlotante(bytes: ByteArray, en: Int): Float =
        Float.fromBits(leerEntero(bytes, en))
}
