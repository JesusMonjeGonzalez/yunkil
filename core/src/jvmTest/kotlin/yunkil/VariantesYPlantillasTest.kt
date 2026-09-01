package yunkil

import yunkil.doc.BibliotecaDePlantillas
import yunkil.doc.Documento
import yunkil.doc.Editor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Variantes paramétricas y plantillas: las dos formas de no modelar dos veces.
 *
 * Las variantes atienden el caso de uso del encaje («el tapón para 19, 20 y 21, y
 * pruebo»); las plantillas, el de la pieza que ya funciona y no hay que volver a
 * calar. Las dos operaciones son una sola transacción deshacible.
 */
class VariantesYPlantillasTest {

    @Test
    fun `una pieza genera una fila de variantes en un solo deshacer`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CILINDRO", null), editor.ultimoError)
        val original = assertNotNull(editor.seleccionado)

        assertTrue(
            editor.generarVariantes(original, "radio", listOf(5f, 7f, 9f)),
            editor.ultimoError,
        )
        val variantes = editor.filas().filter { it.tipo == "CILINDRO" && it.id != original }
        assertEquals(3, variantes.size)
        assertTrue(
            variantes.all { it.nombre.endsWith("mm") },
            "el valor va en el nombre, para no confundir cuál es cuál al probar: ${variantes.map { it.nombre }}",
        )
        // La primera variante queda seleccionada, no la original.
        assertTrue(editor.seleccionado != null && editor.seleccionado != original)

        // Los radios llegaron a la geometría de cada variante.
        val radios = variantes.map { editor.parametro(it.id, "radio") }
        assertEquals(listOf(5f, 7f, 9f), radios)

        // Y el ciclo entero es un punto de deshacer.
        assertTrue(editor.deshacer())
        assertEquals(1, editor.filas().count { it.tipo == "CILINDRO" })
    }

    @Test
    fun `las variantes salen en fila sin caer una encima de otra`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CILINDRO", null), editor.ultimoError)
        val original = assertNotNull(editor.seleccionado)
        assertTrue(editor.generarVariantes(original, "radio", listOf(4f, 4f, 4f)), editor.ultimoError)

        // Original más tres variantes: cuatro cuerpos, ninguno tocando al anterior.
        val cilindros = editor.filas().filter { it.tipo == "CILINDRO" }
        assertEquals(4, cilindros.size)
        val cotas = cilindros.mapNotNull { editor.cotasDe(it.id) }
            .sortedBy { it.min.x }
        assertEquals(4, cotas.size)
        for (i in 1 until cotas.size) {
            assertTrue(cotas[i].min.x > cotas[i - 1].max.x, "la pieza $i se solapa con la ${i - 1}")
        }
    }

    @Test
    fun `una clave desconocida o un valor fuera de rango se rechazan`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CILINDRO", null), editor.ultimoError)
        val id = assertNotNull(editor.seleccionado)

        assertFalse(editor.generarVariantes(id, "peso", listOf(1f)))
        assertFalse(editor.generarVariantes(id, "radio", listOf(-3f)))
        assertFalse(editor.generarVariantes(id, "radio", listOf(9999f)))
        assertFalse(editor.generarVariantes(id, "radio", emptyList()))
        assertEquals(1, editor.filas().count { it.tipo == "CILINDRO" }, "el documento queda como estaba")
    }

    @Test
    fun `una plantilla se guarda y se inserta con identificadores frescos`() {
        val ruta = Files.createTempFile("yunkil-plantillas-", ".json").toString()
        val autor = Editor(Documento.vacio())
        assertTrue(autor.anadir("CAJA", null), autor.ultimoError)
        val caja = assertNotNull(autor.seleccionado)
        assertTrue(autor.guardarComoPlantilla(caja, "Soporte", ruta), autor.ultimoError)

        val otro = Editor(Documento.vacio())
        assertTrue(otro.insertarPlantilla("Soporte", ruta), otro.ultimoError)
        val insertada = otro.filas().first { it.tipo == "CAJA" }
        // Regenerar ids no es un detalle: una colisión haría que `mapear` moviera la
        // pieza equivocada, sin error en ningún sitio.
        assertNotEquals(caja, insertada.id)
        assertEquals(1, otro.filas().count { it.tipo == "CAJA" })

        assertTrue(otro.deshacer())
        assertEquals(0, otro.filas().count { it.tipo == "CAJA" })
        assertTrue(otro.rehacer())
        assertEquals(1, otro.filas().count { it.tipo == "CAJA" })
    }

    @Test
    fun `el encaje viaja con su medida del mundo`() {
        val ruta = Files.createTempFile("yunkil-plantillas-encaje-", ".json").toString()
        val autor = Editor(Documento.vacio())
        assertTrue(autor.anadir("CILINDRO", null), autor.ultimoError)
        val pasador = assertNotNull(autor.seleccionado)
        val medida = assertNotNull(autor.declararMedida("agujero del tubo", 20f))
        assertTrue(
            autor.declararEncaje(pasador, medida),
            autor.ultimoError,
        )
        assertTrue(autor.guardarComoPlantilla(pasador, "Pasador", ruta), autor.ultimoError)

        val otro = Editor(Documento.vacio())
        assertTrue(otro.insertarPlantilla("Pasador", ruta), otro.ultimoError)
        // Sin la medida, la pieza insertada diría encajar con algo que nadie sabe
        // cuánto mide: la cota dejaría de poder derivarse y de poder explicarse.
        assertEquals(1, otro.medidas().size)
        assertEquals("agujero del tubo", otro.medidas().single().nombre)
        assertTrue(otro.medidasSinUso().isEmpty())
    }

    @Test
    fun `una malla no se guarda como plantilla y un nombre vacío tampoco`() {
        val ruta = Files.createTempFile("yunkil-plantillas-neg-", ".json").toString()
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val id = assertNotNull(editor.seleccionado)

        assertFalse(editor.guardarComoPlantilla(id, "", ruta))
        assertNotNull(
            BibliotecaDePlantillas.guardar("", assertNotNull(editor.pieza(id)), ruta),
            "guardar sin nombre se rechaza con su motivo",
        )
        assertFalse(editor.insertarPlantilla("No existe", ruta))
    }
}
