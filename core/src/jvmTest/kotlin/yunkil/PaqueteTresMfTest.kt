package yunkil

import yunkil.kernel.Caja
import yunkil.kernel.Vec3
import yunkil.malla.ContorneadoDual
import yunkil.malla.TresMf
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * El paquete 3MF abierto por un lector que no es el mío.
 *
 * `TresMfTest` comprueba el contenido; esto comprueba el envase, y hace falta que
 * sea otro código el que lo abra. Un ZIP escrito a mano puede tener un CRC mal, un
 * tamaño que no cuadra o un directorio central que apunta al sitio equivocado y
 * seguir pareciendo perfecto desde dentro: mis propias pruebas leerían mis propios
 * bytes con mis propias suposiciones. `java.util.zip` no comparte ninguna.
 *
 * Va en `jvmTest` porque el núcleo también compila para iOS y allí no hay ni ZIP ni
 * parser de XML de serie; lo que se está comprobando es el formato, y el formato no
 * cambia según la plataforma que lo escriba.
 */
class PaqueteTresMfTest {

    private fun paquete(): ByteArray =
        TresMf.paquete(ContorneadoDual(Caja(Vec3(20f, 5f, 10f), 0f), 1f).generar(), "Pieza de prueba")

    private fun partes(bytes: ByteArray): Map<String, ByteArray> {
        val salida = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entrada = zip.nextEntry ?: break
                salida[entrada.name] = zip.readBytes()
                // Cierra la entrada y, con ella, valida el CRC contra lo leído: un
                // CRC mal calculado revienta aquí y en ningún sitio antes.
                zip.closeEntry()
            }
        }
        return salida
    }

    @Test
    fun `el zip lo abre java y trae las tres partes con su crc bueno`() {
        val partes = partes(paquete())

        assertEquals(
            listOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model"),
            partes.keys.toList(),
        )
        assertTrue(partes.values.all { it.isNotEmpty() }, "alguna parte llegó vacía")
    }

    @Test
    fun `el modelo es xml valido y el numero de vertices cuadra con la malla`() {
        val malla = ContorneadoDual(Caja(Vec3(20f, 5f, 10f), 0f), 1f).generar()
        val partes = partes(TresMf.paquete(malla))
        val documento = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(partes.getValue("3D/3dmodel.model")))

        val modelo = documento.documentElement
        assertEquals("model", modelo.localName)
        assertEquals("millimeter", modelo.getAttribute("unit"))

        val vertices = documento.getElementsByTagName("vertex")
        val triangulos = documento.getElementsByTagName("triangle")
        assertEquals(malla.numeroDeVertices, vertices.length, "faltan vértices en el paquete")
        assertEquals(malla.numeroDeTriangulos, triangulos.length, "faltan triángulos en el paquete")

        // Ningún índice puede señalar fuera de la lista de vértices: un 3MF con un
        // índice suelto lo rechaza el laminador entero, no solo ese triángulo.
        for (i in 0 until triangulos.length) {
            val t = triangulos.item(i)
            for (atributo in listOf("v1", "v2", "v3")) {
                val valor = assertNotNull(t.attributes.getNamedItem(atributo)).nodeValue.toInt()
                assertTrue(valor in 0 until vertices.length, "índice $valor fuera de rango")
            }
        }
    }

    @Test
    fun `el titulo viaja en los metadatos`() {
        val partes = partes(paquete())
        val xml = partes.getValue("3D/3dmodel.model").decodeToString()
        assertTrue("Pieza de prueba" in xml, "el nombre de la pieza no llegó al paquete")
    }
}
