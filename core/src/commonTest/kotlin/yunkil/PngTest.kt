package yunkil

import yunkil.imagen.Png
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Un PNG con el CRC mal parece perfecto desde dentro y lo rechaza el primer lector de
 * verdad que lo abre. Así que aquí se **decodifica lo escrito** en vez de comprobar
 * que la función devolvió bytes: se recorren los trozos, se verifica cada CRC contra
 * el declarado y se recuperan los píxeles del flujo zlib.
 *
 * El decodificador vive en la prueba y no en el núcleo a propósito: comprobar con el
 * mismo código que escribe no comprueba nada. En `jvmTest` hay además una pasada con
 * el decodificador del sistema, que es el juez de verdad.
 */
class PngTest {

    private val FIRMA = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `la firma y el orden de los trozos son los que manda el formato`() {
        val png = Png.gris(4, 3, ByteArray(12))
        assertTrue(png.copyOfRange(0, 8).contentEquals(FIRMA), "firma incorrecta")

        val tipos = trozosDe(png).map { it.tipo }
        assertEquals(listOf("IHDR", "IDAT", "IEND"), tipos)
    }

    @Test
    fun `la cabecera declara el tamano real`() {
        val png = Png.gris(7, 5, ByteArray(35))
        val ihdr = trozosDe(png).first { it.tipo == "IHDR" }.datos
        assertEquals(7, entero(ihdr, 0))
        assertEquals(5, entero(ihdr, 4))
        assertEquals(8, ihdr[8].toInt(), "profundidad de bits")
        assertEquals(0, ihdr[9].toInt(), "tipo de color: gris")
        assertEquals(0, ihdr[12].toInt(), "sin entrelazado")
    }

    @Test
    fun `los CRC declarados coinciden con los datos`() {
        // `trozosDe` falla si alguno no cuadra; esto lo hace explícito.
        val png = Png.gris(16, 16, ByteArray(256) { (it * 7).toByte() })
        assertEquals(3, trozosDe(png).size)
    }

    @Test
    fun `los pixeles se recuperan intactos`() {
        val ancho = 9
        val alto = 6
        val original = ByteArray(ancho * alto) { ((it * 37) % 256).toByte() }
        val recuperado = pixelesDe(Png.gris(ancho, alto, original), ancho, alto)
        assertTrue(original.contentEquals(recuperado), "los píxeles no sobrevivieron")
    }

    @Test
    fun `una imagen mas grande que un bloque stored tambien vuelve entera`() {
        // Los bloques stored topan en 65.535 bytes. Con el byte de filtro por fila, una
        // imagen de 300×300 son 90.300 y obliga a partir en dos, que es donde se cuela
        // el fallo de poner el bit de «último bloque» en el sitio equivocado.
        val lado = 300
        val original = ByteArray(lado * lado) { ((it / 3) % 256).toByte() }
        val recuperado = pixelesDe(Png.gris(lado, lado, original), lado, lado)
        assertTrue(original.contentEquals(recuperado), "se perdió el segundo bloque")
    }

    @Test
    fun `el Adler-32 del flujo coincide`() {
        val ancho = 5
        val alto = 4
        val png = Png.gris(ancho, alto, ByteArray(20) { it.toByte() })
        val idat = trozosDe(png).first { it.tipo == "IDAT" }.datos
        val declarado = entero(idat, idat.size - 4)
        val contenido = crudoDelZlib(idat)
        assertEquals(Png.adler32(contenido), declarado)
    }

    // ------------------------------------------------------------- decodificador

    private class Trozo(val tipo: String, val datos: ByteArray)

    private fun entero(b: ByteArray, en: Int): Int =
        ((b[en].toInt() and 0xFF) shl 24) or ((b[en + 1].toInt() and 0xFF) shl 16) or
            ((b[en + 2].toInt() and 0xFF) shl 8) or (b[en + 3].toInt() and 0xFF)

    private fun trozosDe(png: ByteArray): List<Trozo> {
        val salida = ArrayList<Trozo>()
        var i = 8
        while (i < png.size) {
            val largo = entero(png, i)
            val tipo = png.copyOfRange(i + 4, i + 8).decodeToString()
            val datos = png.copyOfRange(i + 8, i + 8 + largo)
            val crcDeclarado = entero(png, i + 8 + largo)
            val crcReal = Png.crc32(png.copyOfRange(i + 4, i + 8 + largo))
            check(crcDeclarado == crcReal) { "CRC mal en el trozo $tipo" }
            salida.add(Trozo(tipo, datos))
            i += 12 + largo
        }
        return salida
    }

    /** Deshace el flujo zlib de bloques stored y devuelve los bytes filtrados. */
    private fun crudoDelZlib(idat: ByteArray): ByteArray {
        check((idat[0].toInt() and 0xFF) == 0x78) { "no parece un flujo zlib" }
        val cabecera = ((idat[0].toInt() and 0xFF) shl 8) or (idat[1].toInt() and 0xFF)
        check(cabecera % 31 == 0) { "la cabecera zlib no pasa la comprobación del 31" }

        var i = 2
        val salida = ArrayList<Byte>()
        while (true) {
            val ultimo = (idat[i].toInt() and 1) == 1
            val tipo = (idat[i].toInt() shr 1) and 3
            check(tipo == 0) { "se esperaba un bloque stored y llegó el tipo $tipo" }
            i++
            val largo = (idat[i].toInt() and 0xFF) or ((idat[i + 1].toInt() and 0xFF) shl 8)
            val complemento = (idat[i + 2].toInt() and 0xFF) or ((idat[i + 3].toInt() and 0xFF) shl 8)
            check(largo == (complemento.inv() and 0xFFFF)) { "el complemento del bloque no cuadra" }
            i += 4
            for (k in 0 until largo) salida.add(idat[i + k])
            i += largo
            if (ultimo) break
        }
        return salida.toByteArray()
    }

    private fun pixelesDe(png: ByteArray, ancho: Int, alto: Int): ByteArray {
        val filtrado = crudoDelZlib(trozosDe(png).first { it.tipo == "IDAT" }.datos)
        val salida = ByteArray(ancho * alto)
        for (y in 0 until alto) {
            check(filtrado[y * (ancho + 1)].toInt() == 0) { "filtro distinto de cero en la fila $y" }
            filtrado.copyInto(
                destination = salida,
                destinationOffset = y * ancho,
                startIndex = y * (ancho + 1) + 1,
                endIndex = y * (ancho + 1) + 1 + ancho,
            )
        }
        return salida
    }
}
