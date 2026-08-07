package yunkil.imagen

/**
 * Escritor de PNG en gris de 8 bits, sin dependencias de plataforma.
 *
 * Hace falta porque el núcleo corre también en iPad y no puede tirar de la JVM, y
 * porque quien va a leer estas imágenes es un modelo de visión: lo que importa es que
 * el archivo sea válido, no que sea pequeño.
 *
 * Por eso se escribe **sin comprimir**, con bloques deflate de tipo *stored*, igual
 * que ya hace `TresMf` con el ZIP. Un deflate propio es un subsistema y aquí no
 * compra nada: la imagen se manda por HTTP a `127.0.0.1` y se tira.
 *
 * Lo que sí se hace bien son las dos sumas de control. Un PNG con el CRC mal parece
 * perfecto desde dentro y lo rechaza el primer lector de verdad que lo abre, así que
 * hay una prueba que vuelve a decodificar lo escrito en vez de fiarse.
 */
object Png {

    private val FIRMA = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * Codifica una imagen en gris. [pixeles] va por filas, de arriba abajo.
     */
    fun gris(ancho: Int, alto: Int, pixeles: ByteArray): ByteArray {
        require(ancho > 0 && alto > 0) { "la imagen no puede tener lado cero" }
        require(pixeles.size == ancho * alto) {
            "se esperaban ${ancho * alto} píxeles y llegaron ${pixeles.size}"
        }

        val salida = Salida()
        salida.bytes(FIRMA)

        // IHDR: 8 bits, tipo de color 0 (gris), sin entrelazado.
        val ihdr = Salida()
        ihdr.entero(ancho)
        ihdr.entero(alto)
        ihdr.byte(8)
        ihdr.byte(0)
        ihdr.byte(0)
        ihdr.byte(0)
        ihdr.byte(0)
        salida.trozo("IHDR", ihdr.aBytes())

        salida.trozo("IDAT", zlibSinComprimir(conFiltros(ancho, alto, pixeles)))
        salida.trozo("IEND", ByteArray(0))
        return salida.aBytes()
    }

    /**
     * Cada fila lleva delante su byte de filtro. Cero: sin filtrar.
     *
     * Es obligatorio aunque no se filtre nada, y olvidarlo produce un archivo que
     * decodifica *casi* bien —la imagen sale desplazada un píxel por fila y se ve como
     * un barrido diagonal—, que es peor que un archivo que no abre.
     */
    private fun conFiltros(ancho: Int, alto: Int, pixeles: ByteArray): ByteArray {
        val salida = ByteArray(alto * (ancho + 1))
        for (y in 0 until alto) {
            salida[y * (ancho + 1)] = 0
            pixeles.copyInto(
                destination = salida,
                destinationOffset = y * (ancho + 1) + 1,
                startIndex = y * ancho,
                endIndex = y * ancho + ancho,
            )
        }
        return salida
    }

    /** Un flujo zlib de bloques *stored*: cabecera, los datos tal cual y su Adler-32. */
    private fun zlibSinComprimir(datos: ByteArray): ByteArray {
        val salida = Salida()
        // 0x78 0x01: ventana de 32 K, sin diccionario, nivel más rápido. El segundo byte
        // está elegido para que (0x78 << 8 | 0x01) sea múltiplo de 31, que es lo que
        // comprueba todo decodificador antes de mirar nada más.
        salida.byte(0x78)
        salida.byte(0x01)

        val MAXIMO = 65535
        var desde = 0
        while (desde < datos.size || datos.isEmpty()) {
            val cuantos = minOf(MAXIMO, datos.size - desde)
            val ultimo = desde + cuantos >= datos.size
            salida.byte(if (ultimo) 1 else 0)
            // Longitud y su complemento, los dos en little-endian. El complemento es la
            // única comprobación que tiene un bloque stored.
            salida.byte(cuantos and 0xFF)
            salida.byte((cuantos shr 8) and 0xFF)
            salida.byte(cuantos.inv() and 0xFF)
            salida.byte((cuantos.inv() shr 8) and 0xFF)
            salida.bytes(datos, desde, cuantos)
            desde += cuantos
            if (ultimo) break
        }

        salida.entero(adler32(datos))
        return salida.aBytes()
    }

    // ------------------------------------------------------------ sumas de control

    private val TABLA_CRC = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
        c
    }

    internal fun crc32(datos: ByteArray, desde: Int = 0, cuantos: Int = datos.size - desde): Int {
        var c = -1
        for (i in desde until desde + cuantos) {
            c = TABLA_CRC[(c xor datos[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        return c.inv()
    }

    internal fun adler32(datos: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in datos) {
            a = (a + (byte.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }

    // ------------------------------------------------------------------ ensamblado

    private class Salida {
        private var datos = ByteArray(1024)
        private var largo = 0

        fun byte(v: Int) {
            asegurar(1)
            datos[largo++] = (v and 0xFF).toByte()
        }

        fun entero(v: Int) {
            byte(v ushr 24)
            byte(v ushr 16)
            byte(v ushr 8)
            byte(v)
        }

        fun bytes(v: ByteArray, desde: Int = 0, cuantos: Int = v.size) {
            asegurar(cuantos)
            v.copyInto(datos, largo, desde, desde + cuantos)
            largo += cuantos
        }

        /** Un trozo PNG: longitud, tipo, datos y el CRC de tipo + datos. */
        fun trozo(tipo: String, contenido: ByteArray) {
            entero(contenido.size)
            val conTipo = ByteArray(4 + contenido.size)
            for (i in 0 until 4) conTipo[i] = tipo[i].code.toByte()
            contenido.copyInto(conTipo, 4)
            bytes(conTipo)
            entero(crc32(conTipo))
        }

        fun aBytes(): ByteArray = datos.copyOf(largo)

        private fun asegurar(cuantos: Int) {
            if (largo + cuantos <= datos.size) return
            var nuevo = datos.size * 2
            while (nuevo < largo + cuantos) nuevo *= 2
            datos = datos.copyOf(nuevo)
        }
    }
}
