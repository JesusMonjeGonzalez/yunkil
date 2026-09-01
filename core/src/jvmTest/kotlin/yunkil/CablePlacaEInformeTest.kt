package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.kernel.Vec3
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El cable paramétrico y las dos salidas nuevas del núcleo: la placa 3MF multiobjeto
 * y el informe de fabricación en Markdown.
 */
class CablePlacaEInformeTest {

    // ------------------------------------------------------------------- el cable

    private fun contratoDeCable(): Pair<List<Vec3>, List<Float>> = listOf(
        Vec3(0f, 4f, 0f),
        Vec3(10f, 4f, 4f),
        Vec3(20f, 6f, 0f),
        Vec3(30f, 10f, -6f),
    ) to listOf(3f, 2.5f, 2f, 0.5f)

    @Test
    fun `un cable es una pieza que se compila, se acota y se taladra`() {
        val editor = Editor(Documento.vacio())
        val (puntos, radios) = contratoDeCable()
        assertTrue(editor.anadirCable(puntos, radios, "Latiguillo"), editor.ultimoError)
        val cable = assertNotNull(editor.seleccionado)
        assertTrue(editor.esEsculturaNoEsCable(cable).not())

        // La polilínea llega al campo: las cotas cubren los extremos con su radio.
        val cotas = assertNotNull(editor.cotasDe(cable))
        assertTrue(cotas.min.x < -2f && cotas.max.x > 30f, "cotas $cotas")

        // Se reescribe punto a punto, como una edición deshacible.
        val nuevos = listOf(Vec3(0f, 4f, 0f), Vec3(20f, 4f, 0f))
        val radiosNuevos = listOf(3f, 3f)
        assertTrue(editor.fijarCable(cable, nuevos, radiosNuevos), editor.ultimoError)
        val cotasNuevas = assertNotNull(editor.cotasDe(cable))
        assertTrue(cotasNuevas.max.x < cotas.max.x, "acortar el cable acorta sus cotas")
        assertTrue(editor.deshacer())
    }

    @Test
    fun `un cable roto se rechaza con su motivo`() {
        val editor = Editor(Documento.vacio())
        assertFalse(editor.anadirCable(listOf(Vec3.ZERO), listOf(1f)))
        assertFalse(
            editor.anadirCable(listOf(Vec3.ZERO, Vec3(5f, 0f, 0f)), listOf(1f)),
            "un radio por punto, no uno menos",
        )
        assertFalse(
            editor.anadirCable(listOf(Vec3.ZERO, Vec3(5f, 0f, 0f)), listOf(0f, 1f)),
            "un radio intermedio a 0 parte el cable en dos",
        )
        assertFalse(
            editor.anadirCable(
                List(65) { Vec3(it.toFloat(), 0f, 0f) },
                List(65) { 1f },
            ),
            "por encima del máximo del cordón no se acepta",
        )
        assertNull(editor.documento().compilar())
    }

    @Test
    fun `el cable llega certificado al archivo`() {
        val editor = Editor(Documento.vacio())
        val (puntos, radios) = contratoDeCable()
        assertTrue(editor.anadirCable(puntos, radios, "Latiguillo"), editor.ultimoError)
        val ruta = Files.createTempFile("yunkil-cable-", ".stl").toString()
        val certificado = assertNotNull(editor.exportarStl(ruta, 1.2f))
        assertTrue(certificado.apto, certificado.resumen())
        assertTrue(Files.size(java.nio.file.Path.of(ruta)) > 84)
    }

    // ----------------------------------------------------------------- la placa 3MF

    @Test
    fun `la placa junta dos cuerpos aptos en un 3MF multiobjeto`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val ids = editor.filas().filter { it.tipo == "CAJA" }.map { it.id }

        val ruta = Files.createTempFile("yunkil-placa-", ".3mf").toString()
        val certificados = assertNotNull(editor.exportarPlacaTresMf(ruta, ids, 1.2f))
        assertEquals(2, certificados.size)
        assertTrue(certificados.all { it.apto }, certificados.joinToString { it.resumen() })
        assertTrue(Files.size(java.nio.file.Path.of(ruta)) > 0)
    }

    @Test
    fun `la placa no se escribe si un cuerpo no se puede exportar`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val b = assertNotNull(editor.seleccionado)
        // Oculta no aporta material: la placa no se escribe con un cuerpo vacío.
        assertTrue(editor.fijarVisible(b, false))
        val ids = editor.filas().filter { it.tipo == "CAJA" }.map { it.id }
        assertEquals(2, ids.size)

        val ruta = Files.createTempFile("yunkil-placa-rota-", ".3mf").toString()
        assertNull(editor.exportarPlacaTresMf(ruta, ids, 1.2f))
        assertTrue(
            editor.ultimoError?.contains("no aporta material") == true,
            "el motivo nombra a la pieza: ${editor.ultimoError}",
        )
        // El temporizador de la prueba creó el archivo; que no lo haya escrito es lo
        // que importa: sigue vacío.
        assertEquals(0, Files.size(java.nio.file.Path.of(ruta)))
    }

    // ---------------------------------------------------------- el informe Markdown

    @Test
    fun `el informe nombra el perfil, la estimacion y las medidas a ojo`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CILINDRO", null), editor.ultimoError)
        val pasador = assertNotNull(editor.seleccionado)
        val medida = assertNotNull(editor.declararMedida("agujero del tubo", 20f))
        assertTrue(editor.declararEncaje(pasador, medida), editor.ultimoError)

        val ruta = Files.createTempFile("yunkil-informe-", ".md").toString()
        assertTrue(editor.exportarInformeMarkdown(ruta), editor.ultimoError)
        val texto = Files.readString(java.nio.file.Path.of(ruta))

        assertTrue(texto.contains("# Informe de fabricación"))
        assertTrue(texto.contains("Bambu P1S · PLA · 0,4"))
        assertTrue(texto.contains("Estimación"), "el peso y el coste van al informe")
        // La medida se dio de alta a ojo, y el informe lo dice en vez de callarlo.
        assertTrue(
            texto.contains("a ojo"),
            "una medida sin procedencia es la menos fiable del proyecto y se avisa",
        )
        // El encaje va a la lista cumpla o no, con lo declarado y lo medido.
        assertTrue(texto.contains("Cilindro"))
    }
}

private fun Editor.esEsculturaNoEsCable(id: String): Boolean = pieza(id)?.tipo?.name == "ESCULTURA"
