package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.organico.MotorOrganico
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Edición semántica de partes orgánicas y selección por punto: el lado núcleo de la
 * prioridad 2. Mover la cola o quitar una oreja no exige reescribir el contrato ni
 * volver a pedirle nada a la IA; el identificador ya sabe a qué parte se refiere.
 */
class OrganicoSemanticoTest {

    private val gato = """
        {"esquema":"yunkil.organico.v1","nombre":"Gato","unidades":"mm","fusionMm":2,
         "partes":[
          {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,12,0],"b":[0,34,0],"radio":12},
          {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,46,0],"radio":11,"unidoA":"cuerpo"},
          {"id":"oreja","rol":"OREJA","forma":"TRONCO","a":[0,56,0],"b":[3,66,2],"radioA":3,"radioB":0.5,"unidoA":"cabeza"},
          {"id":"anillo","rol":"DETALLE","forma":"ESFERA","centro":[0,54,0],"radio":2,"unidoA":"oreja"},
          {"id":"ojo","rol":"OJO","forma":"ESFERA","centro":[0,50,-11],"radio":2.5,"unidoA":"cabeza"},
          {"id":"cola","rol":"COLA","forma":"CURVA",
           "puntos":[0,26,6, 8,34,16, 6,44,22, -4,48,16],"radios":[5,3.5,2,0.4],
           "unidoA":"cuerpo"}
         ]}
    """.trimIndent()

    private fun editorConGato(): Pair<Editor, String> {
        val editor = Editor(Documento.vacio())
        val contrato = assertNotNull(MotorOrganico.interpretar(gato).contratoCanonico)
        assertTrue(editor.anadirEscultura(contrato), editor.ultimoError)
        val id = assertNotNull(editor.seleccionado)
        return editor to id
    }

    @Test
    fun `mover una parte cambia el contrato y se deshace`() {
        val (editor, gatoId) = editorConGato()
        val antes = assertNotNull(editor.contratoDeEscultura(gatoId))

        editor.moverParteOrganica(gatoId, "cola", 10f, 0f, -4f)
        assertNull(editor.ultimoError, editor.ultimoError)
        val despues = assertNotNull(editor.contratoDeEscultura(gatoId))
        assertFalse(antes == despues)

        assertTrue(editor.deshacer())
        assertEquals(antes, editor.contratoDeEscultura(gatoId))
    }

    @Test
    fun `mover una parte lejos de su padre se rechaza y no toca el documento`() {
        val (editor, gatoId) = editorConGato()
        val antes = assertNotNull(editor.contratoDeEscultura(gatoId))

        // La cabeza a 80 mm del cuerpo deja de tocarlo: el validador del contrato es
        // el mismo que el de la IA, y aquí también falla cerrado.
        assertFalse(editor.moverParteOrganica(gatoId, "cabeza", 80f, 0f, 0f))
        assertEquals(antes, editor.contratoDeEscultura(gatoId))
    }

    @Test
    fun `engordar una parte toca sus radios y no su eje`() {
        val (editor, gatoId) = editorConGato()
        val cotasAntes = assertNotNull(editor.cotasDe(gatoId))

        editor.engordarParteOrganica(gatoId, "cuerpo", 2f)
        assertNull(editor.ultimoError, editor.ultimoError)
        val cotasDespues = assertNotNull(editor.cotasDe(gatoId))
        assertTrue(
            cotasDespues.size.x > cotasAntes.size.x,
            "engordar el cuerpo engorda la huella en X",
        )
    }

    @Test
    fun `quitar una parte reataja a las que colgaban de ella`() {
        val (editor, gatoId) = editorConGato()
        // El anillo colgaba de la oreja; al quitar la oreja pasa a colgar de la
        // cabeza, que es de quien colgaba la oreja. Sin el reataje, quitar una parte
        // intermedia rompería media figura por contactos que ya no se cumplen.
        assertTrue(editor.quitarParteOrganica(gatoId, "oreja"), editor.ultimoError)

        val contrato = assertNotNull(editor.contratoDeEscultura(gatoId))
        assertFalse(contrato.contains("oreja"))
        val leido = MotorOrganico.interpretar(contrato)
        assertTrue(leido.aceptado, leido.motivo)
        assertTrue(leido.contratoCanonico!!.contains("\"unidoA\":\"cabeza\""))
    }

    @Test
    fun `el cuerpo no se quita y una parte desconocida tampoco`() {
        val (editor, gatoId) = editorConGato()
        assertFalse(editor.quitarParteOrganica(gatoId, "cuerpo"))
        assertFalse(editor.quitarParteOrganica(gatoId, "ala"))
        assertNotNull(editor.contratoDeEscultura(gatoId), "el documento queda como estaba")
    }

    @Test
    fun `un punto del mundo sabe a que parte pertenece`() {
        val (editor, gatoId) = editorConGato()
        // Sin transformación, mundo y local coinciden. El ojo de la mascota del test
        // no existe; el centro de la cabeza está en (0, 46, 0).
        assertEquals("cabeza", editor.parteOrganicaBajo(gatoId, 0f, 46f, 0f))
        assertEquals("cuerpo", editor.parteOrganicaBajo(gatoId, 0f, 20f, 0f))
        assertEquals("cola", editor.parteOrganicaBajo(gatoId, 8f, 34f, 16f))
        // Un punto en el aire no es de nadie.
        assertNull(editor.parteOrganicaBajo(gatoId, 60f, 60f, 60f))
    }

    @Test
    fun `la seleccion por punto sigue a la parte movida`() {
        val (editor, gatoId) = editorConGato()
        // La oreja baja 3 mm: sigue entrando en la cabeza —sus muestras piden quedarse
        // dentro de la tolerancia de contacto— y el contrato sigue siendo válido.
        // Moverla 30 mm la partiría del cuerpo, y eso se rechaza.
        editor.moverParteOrganica(gatoId, "oreja", 0f, -3f, 0f)
        assertNull(editor.ultimoError, editor.ultimoError)
        assertEquals("oreja", editor.parteOrganicaBajo(gatoId, 3f, 63f, 2f))
        assertNull(
            editor.parteOrganicaBajo(gatoId, 3f, 66f, 2f),
            "donde estaba la punta de la oreja ya no hay oreja",
        )
    }

    @Test
    fun `la tolerancia de una medida corta se come la holgura y se dice`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CILINDRO", null), editor.ultimoError)
        val pasador = assertNotNull(editor.seleccionado)
        val medida = assertNotNull(editor.declararMedida("agujero del tubo", 20f))
        editor.fijarTolerancia(medida, 0.3f)
        assertNull(editor.ultimoError, "fijar la tolerancia no puede fallar")
        assertTrue(editor.declararEncaje(pasador, medida), editor.ultimoError)

        // Holgura declarada 0,2 mm contra una medida incierta en ±0,3: no se puede
        // decir que se cumpla, y la verificación lo cuenta en vez de callarlo.
        val informe = assertNotNull(editor.analizarFabricacion())
        val medido = informe.encajes.single()
        assertTrue(medido.holguraMedida.isNaN(), "no se puede certificar lo incierto")
        assertTrue(
            medido.motivo?.contains("se come") == true,
            "el motivo nombra la incertidumbre: ${medido.motivo}",
        )
    }
}
