package yunkil

import yunkil.kernel.Caja
import yunkil.kernel.Vec3
import yunkil.malla.ContorneadoDual
import yunkil.malla.Malla
import yunkil.malla.TresMf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El 3MF: la malla contada como la entiende una impresora.
 *
 * Lo que se comprueba aquí no es que el XML esté bien formado —eso lo miraría
 * cualquiera— sino las dos cosas que se pueden estropear sin que se note hasta que
 * la pieza sale mal: que las unidades estén declaradas y que el cambio de Y arriba
 * a Z arriba sea un giro y no un espejo.
 */
class TresMfTest {

    /** Una caja de 40 (x) × 10 (y, altura) × 20 (z, fondo) en el mundo de Yunkil. */
    private fun cajaMallada(): Malla =
        ContorneadoDual(Caja(Vec3(20f, 5f, 10f), 0f), 1f).generar()

    private fun atributos(xml: String, etiqueta: String): List<Triple<Float, Float, Float>> =
        Regex("""<$etiqueta x="([-\d.]+)" y="([-\d.]+)" z="([-\d.]+)"/>""")
            .findAll(xml)
            .map { Triple(it.groupValues[1].toFloat(), it.groupValues[2].toFloat(), it.groupValues[3].toFloat()) }
            .toList()

    @Test
    fun `el modelo declara milimetros`() {
        val xml = TresMf.modelo(cajaMallada())

        // El STL no dice en qué unidades está y por eso existe el clásico modelo que
        // entra en el laminador a 1/25 de su tamaño. Que esto falte no rompe nada
        // visiblemente: imprime una pieza del tamaño equivocado.
        assertTrue("unit=\"millimeter\"" in xml, "el 3MF no declara unidades")
        assertTrue("<build>" in xml && "objectid=\"1\"" in xml, "el paquete no tiene nada que construir")
    }

    @Test
    fun `la pieza llega con la Z arriba y apoyada en el plato`() {
        val malla = cajaMallada()
        val xml = TresMf.modelo(malla)
        val vertices = atributos(xml, "vertex")
        assertTrue(vertices.size >= 8, "salieron ${vertices.size} vértices")

        val anchura = vertices.maxOf { it.first } - vertices.minOf { it.first }
        val fondo = vertices.maxOf { it.second } - vertices.minOf { it.second }
        val altura = vertices.maxOf { it.third } - vertices.minOf { it.third }

        // Los 10 mm que en Yunkil son altura en Y tienen que ser altura en Z: si la
        // pieza llega tumbada, el voladizo que midió el analizador no tiene ninguna
        // relación con lo que la máquina va a imprimir.
        assertEquals(40f, anchura, 1.5f, "la anchura no sobrevivió al giro")
        assertEquals(20f, fondo, 1.5f, "el fondo no sobrevivió al giro")
        assertEquals(10f, altura, 1.5f, "la altura no acabó en Z")

        assertTrue(vertices.minOf { it.third } > -0.01f, "la pieza queda hundida bajo el plato")
        assertTrue(vertices.minOf { it.third } < 0.01f, "la pieza queda flotando sobre el plato")
        assertTrue(vertices.minOf { it.first } > -0.01f, "hay geometría en X negativa")
        assertTrue(vertices.minOf { it.second } > -0.01f, "hay geometría en Y negativa")
    }

    @Test
    fun `el giro conserva el lado de fuera`() {
        val malla = cajaMallada()
        val xml = TresMf.modelo(malla)
        val vertices = atributos(xml, "vertex")
        val indices = Regex("""<triangle v1="(\d+)" v2="(\d+)" v3="(\d+)"/>""")
            .findAll(xml)
            .map { Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            .toList()
        assertEquals(malla.numeroDeTriangulos, indices.size, "se perdieron triángulos por el camino")

        // Volumen con signo sobre las coordenadas ya convertidas. Un cambio de ejes
        // con determinante −1 —un espejo en vez de un giro— da exactamente la misma
        // caja y las normales del revés, que es un fallo que muchos laminadores
        // aceptan callando y luego imprimen hacia dentro.
        var seis = 0.0
        for ((a, b, c) in indices) {
            val p = vertices[a]
            val q = vertices[b]
            val r = vertices[c]
            seis += p.first.toDouble() * (q.second * r.third - r.second * q.third) -
                p.second.toDouble() * (q.first * r.third - r.first * q.third) +
                p.third.toDouble() * (q.first * r.second - r.first * q.second)
        }
        val volumen = seis / 6.0
        assertTrue(volumen > 0.0, "las normales salieron invertidas: volumen $volumen")
        assertEquals(40.0 * 10.0 * 20.0, volumen, 1500.0, "el volumen no cuadra con la caja")
    }

    @Test
    fun `el paquete es un zip con las tres piezas del formato`() {
        val bytes = TresMf.paquete(cajaMallada())

        assertEquals(0x50, bytes[0].toInt(), "no empieza por la firma de un ZIP")
        assertEquals(0x4B, bytes[1].toInt())
        assertEquals(0x03, bytes[2].toInt())
        assertEquals(0x04, bytes[3].toInt())

        val texto = bytes.decodeToString()
        for (parte in listOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model")) {
            assertTrue(parte in texto, "al paquete le falta «$parte»")
        }
    }
}
