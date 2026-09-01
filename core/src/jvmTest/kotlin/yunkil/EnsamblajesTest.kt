package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.FormatoYunkil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Los ensamblajes declarados y sus interferencias.
 *
 * La decisión de diseño: una interferencia solo existe **dentro de un ensamblaje
 * declarado**, porque en este documento no hay cuerpos sueltos —la raíz es una unión
 * y solapar piezas es como se construye una pieza—. Nadie declara un ensamblaje para
 * cuerpos que no tengan que ir separados, así que un solape medido ahí es siempre un
 * problema.
 */
class EnsamblajesTest {

    private fun editorConDosCajas(separacion: Float): Editor {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val b = assertNotNull(editor.seleccionado)
        assertTrue(editor.desplazarParaProbar(b, separacion), editor.ultimoError)
        return editor
    }

    /** Coloca la pieza en x = [separacion], que es lo que el test necesita y nada más. */
    private fun Editor.desplazarParaProbar(id: String, separacion: Float): Boolean {
        if (separacion == 0f) return true
        return fijarTransform(id, separacion, 0f, 0f, 0f, 0f, 0f, 1f)
    }

    /** El fallo real de las ediciones de `Editor` vive en `ultimoError`, no en el booleano. */
    private fun éxito(motivo: String?): Boolean = motivo == null

    @Test
    fun `dos cajas que se meten una en otra interfieren`() {
        val editor = editorConDosCajas(separacion = 5f)
        val a = editor.filas().first { it.tipo == "CAJA" }.id
        val b = editor.filas().last { it.tipo == "CAJA" }.id
        editor.declararEnsamblaje("Bisagra", listOf(a, b))
        assertTrue(éxito(editor.ultimoError), editor.ultimoError)

        val interferencias = editor.verificarEnsamblajes(paso = 1f)
        assertEquals(1, interferencias.size)
        val medida = assertNotNull(interferencias.first())
        assertTrue(medida.medible, "dos cajas en el mismo sitio se pueden medir")
        assertTrue(medida.interfieren, "cajas de 40 mm a 5 mm de distancia se solapan")
        assertTrue((medida.solape ?: 0f) > 0f)
    }

    @Test
    fun `dos cajas separadas no interfieren y la holgura se queda corta`() {
        // Caja de 40 mm en x=0 y otra en x=60: 20 mm de aire entre las dos.
        val editor = editorConDosCajas(separacion = 60f)
        val a = editor.filas().first { it.tipo == "CAJA" }.id
        val b = editor.filas().last { it.tipo == "CAJA" }.id
        editor.declararEnsamblaje("Caja y tapa", listOf(a, b))
        assertTrue(éxito(editor.ultimoError), editor.ultimoError)

        val medida = editor.verificarEnsamblajes(paso = 1f).single()
        assertFalse(medida.interfieren)
        val holgura = assertNotNull(medida.holguraMinima)
        assertTrue(
            holgura in 15f..20.5f,
            "la holgura muestral debe rondar los 20 mm y quedarse corta: $holgura",
        )
    }

    @Test
    fun `declarar y quitar es una edicion deshacible`() {
        val editor = editorConDosCajas(60f)
        val a = editor.filas().first { it.tipo == "CAJA" }.id
        val b = editor.filas().last { it.tipo == "CAJA" }.id

        editor.declararEnsamblaje("Bisagra", listOf(a, b, a))
        assertTrue(éxito(editor.ultimoError), editor.ultimoError)

        // Un id repetido no declara un par consigo mismo.
        val ensamblaje = editor.ensamblajes().single()
        assertEquals(2, ensamblaje.piezas.size)

        editor.quitarEnsamblaje(ensamblaje.id)
        assertTrue(éxito(editor.ultimoError), editor.ultimoError)
        assertTrue(editor.ensamblajes().isEmpty())

        // Y todo el ciclo es un punto de deshacer por cada edición.
        assertTrue(editor.deshacer())
        assertEquals(1, editor.ensamblajes().size, "quitar el ensamblaje se deshace")
        assertTrue(editor.deshacer())
        assertTrue(editor.ensamblajes().isEmpty(), "declararlo también")
    }

    @Test
    fun `un ensamblaje con referencias muertas se sana al abrir`() {
        val editor = editorConDosCajas(60f)
        val a = editor.filas().first { it.tipo == "CAJA" }.id
        val b = editor.filas().last { it.tipo == "CAJA" }.id
        editor.declararEnsamblaje("Bisagra", listOf(a, b))
        assertTrue(éxito(editor.ultimoError), editor.ultimoError)
        editor.eliminar(b)
        assertTrue(éxito(editor.ultimoError), editor.ultimoError)

        // Guardar y reabrir: el ensamblaje pierde la referencia muerta. Como le queda
        // un solo cuerpo, deja de ser un ensamblaje y no estorba al verificador.
        val reabierto = FormatoYunkil.decodificar(FormatoYunkil.codificar(editor.documento()))
        assertTrue(reabierto.ensamblajes.isEmpty())
        assertNull(FormatoYunkil.validar(reabierto))
    }

    @Test
    fun `un ensamblaje vivo sobrevive al archivo`() {
        val editor = editorConDosCajas(60f)
        val a = editor.filas().first { it.tipo == "CAJA" }.id
        val b = editor.filas().last { it.tipo == "CAJA" }.id
        editor.declararEnsamblaje("Caja y tapa", listOf(a, b))
        assertTrue(éxito(editor.ultimoError), editor.ultimoError)

        val reabierto = FormatoYunkil.decodificar(FormatoYunkil.codificar(editor.documento()))
        assertEquals(1, reabierto.ensamblajes.size)
        assertEquals("Caja y tapa", reabierto.ensamblajes.single().nombre)
        assertEquals(setOf(a, b), reabierto.ensamblajes.single().piezas.toSet())
    }
}
