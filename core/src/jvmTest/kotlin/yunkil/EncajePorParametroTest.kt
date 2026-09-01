package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.FormatoYunkil
import yunkil.doc.aplanar
import yunkil.doc.extension
import yunkil.ia.EjeNombrado
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gobernar dos cotas independientes por parámetro —la prioridad 4 del README— sin
 * romper la invariante de la escala uniforme: la derivación por parámetro toca el
 * número responsable del eje, nunca la escala de la pieza.
 */
class EncajePorParametroTest {

    private fun editorConCaja(): Triple<Editor, String, String> {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val caja = assertNotNull(editor.seleccionado)
        val medidaX = assertNotNull(editor.declararMedida("vano horizontal", 40f))
        val medidaY = assertNotNull(editor.declararMedida("vano vertical", 20f))
        return Triple(editor, caja, "$medidaX|$medidaY")
    }

    @Test
    fun `una caja con dos encajes por parametro obedece a los dos`() {
        val (editor, caja, medidas) = editorConCaja()
        val (mx, my) = medidas.split("|")
        assertTrue(
            editor.declararEncaje(caja, mx, EjeNombrado.X, porParametro = true),
            editor.ultimoError,
        )
        assertTrue(
            editor.declararEncaje(caja, my, EjeNombrado.Y, porParametro = true),
            editor.ultimoError,
        )

        val cotas = assertNotNull(editor.cotasDe(caja))
        // ENTRA por deslizante con holgura de fábrica 0,2: 40 - 0,4 y 20 - 0,4.
        assertEquals(39.6f, cotas.size.x, 0.05f)
        assertEquals(19.6f, cotas.size.y, 0.05f)
        // La profundidad no la gobierna nadie y no se ha movido.
        assertEquals(30f, cotas.size.z, 0.05f)
    }

    @Test
    fun `corregir una medida mueve su cota y no la del otro encaje`() {
        val (editor, caja, medidas) = editorConCaja()
        val (mx, my) = medidas.split("|")
        assertTrue(editor.declararEncaje(caja, mx, EjeNombrado.X, porParametro = true), editor.ultimoError)
        assertTrue(editor.declararEncaje(caja, my, EjeNombrado.Y, porParametro = true), editor.ultimoError)
        val antesY = assertNotNull(editor.cotasDe(caja)).size.y

        assertTrue(editor.fijarMedida(mx, 50f), editor.ultimoError)

        val despues = assertNotNull(editor.cotasDe(caja))
        assertEquals(49.6f, despues.size.x, 0.05f)
        assertEquals(antesY, despues.size.y, "la altura no manda en el eje X y no se mueve")
    }

    @Test
    fun `mezclar por parametro y por escala en una pieza se rechaza`() {
        val (editor, caja, medidas) = editorConCaja()
        val (mx, my) = medidas.split("|")
        assertTrue(editor.declararEncaje(caja, mx, EjeNombrado.X), editor.ultimoError)
        assertFalse(
            editor.declararEncaje(caja, my, EjeNombrado.Y, porParametro = true),
            "una escala uniforme pisaría al encaje por parámetro",
        )
        assertEquals(1, editor.encajesDe(caja).size)
    }

    @Test
    fun `un eje sin parametro responsable se rechaza y se dice`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CONO", null), editor.ultimoError)
        val cono = assertNotNull(editor.seleccionado)
        val medida = assertNotNull(editor.declararMedida("hueco", 40f))
        assertFalse(editor.declararEncaje(cono, medida, EjeNombrado.X, porParametro = true))
        assertTrue(
            editor.ultimoError?.contains("parámetro") == true,
            "el motivo nombra el problema: ${editor.ultimoError}",
        )
        // Y por escala, como siempre, sí se deja.
        assertTrue(editor.declararEncaje(cono, medida, EjeNombrado.X), editor.ultimoError)
    }

    @Test
    fun `un archivo del esquema 1 con encaje singular migra a la lista`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CILINDRO", null), editor.ultimoError)
        val pasador = assertNotNull(editor.seleccionado)
        val medida = assertNotNull(editor.declararMedida("agujero del tubo", 20f))
        assertTrue(editor.declararEncaje(pasador, medida), editor.ultimoError)
        assertEquals(1, editor.encajesDe(pasador).size)

        // Cirugía inversa: el JSON del esquema 2 vuelve a la forma del esquema 1.
        val jsonV2 = FormatoYunkil.codificar(editor.documento())
        val jsonV1 = jsonV2
            .replace("\"versionEsquema\": 2", "\"versionEsquema\": 1")
            .replace(Regex("\"encajes\":\\s*\\[\\s*(\\{.*?\\})\\s*\\]", RegexOption.DOT_MATCHES_ALL), "\"encaje\": $1")

        val reabierto = FormatoYunkil.decodificar(jsonV1)
        assertEquals(2, reabierto.versionEsquema, "la migración sube la versión")
        assertEquals(1, reabierto.raiz.aplanar().first { it.first.id == pasador }.first.encajes.size)
        assertNull(FormatoYunkil.validar(reabierto))
    }

    @Test
    fun `el encaje por parametro no rompe el camino de la escala en otras piezas`() {
        // Una caja gobernada por parámetro junto a un cilindro gobernado por escala:
        // cada pieza resuelve lo suyo y la caja no arrastra al cilindro.
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val caja = assertNotNull(editor.seleccionado)
        assertTrue(editor.anadir("CILINDRO", null), editor.ultimoError)
        val cilindro = assertNotNull(editor.seleccionado)
        val medida = assertNotNull(editor.declararMedida("vano", 40f))
        assertTrue(editor.declararEncaje(caja, medida, EjeNombrado.X, porParametro = true), editor.ultimoError)
        assertTrue(editor.declararEncaje(cilindro, medida, EjeNombrado.X), editor.ultimoError)

        val cotasCaja = assertNotNull(editor.cotasDe(caja))
        val cotasCilindro = assertNotNull(editor.cotasDe(cilindro))
        assertEquals(39.6f, cotasCaja.size.x, 0.05f)
        // El cilindro de radio 8 no llega a 39,6 por escala: se escala hasta cumplirla.
        assertEquals(39.6f, extension(cotasCilindro, EjeNombrado.X), 0.05f)
    }
}
