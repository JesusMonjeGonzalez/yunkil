package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VersionDeDocumentoTest {

    @Test
    fun `una edicion y su deshacer conservan el contenido pero avanzan la version`() {
        val editor = Editor(Documento.vacio())
        val jsonInicial = editor.aJson()
        val versionInicial = editor.versionDocumento

        assertTrue(editor.anadir("CAJA", null))
        assertTrue(editor.versionDocumento > versionInicial)
        assertTrue(editor.deshacer())

        assertEquals(jsonInicial, editor.aJson())
        assertTrue(editor.versionDocumento > versionInicial, "el caso ABA no debe recuperar una versión anterior")
    }

    @Test
    fun `el fantasma no cambia la version del documento`() {
        val editor = Editor(Documento.vacio())
        val plan = assertNotNull(
            editor.interpretarPlan(
                """{"operaciones":[{"op":"crear","tipo":"CAJA"}]}""",
            ).plan,
        )
        val version = editor.versionDocumento

        editor.previsualizar(plan)
        assertEquals(version, editor.versionDocumento)
        editor.previsualizar(null)
        assertEquals(version, editor.versionDocumento)
    }

    @Test
    fun `una propuesta caducada se rechaza atomicamente`() {
        val editor = Editor(Documento.vacio())
        val plan = assertNotNull(
            editor.interpretarPlan(
                """{"operaciones":[{"op":"crear","tipo":"ESFERA"}]}""",
            ).plan,
        )
        val version = editor.versionDocumento
        assertTrue(editor.anadir("CAJA", null))
        val antes = editor.aJson()

        val resultado = editor.aplicarParteEnVersion(plan, listOf(0), version)

        assertFalse(resultado.exito)
        assertTrue(resultado.error?.contains("documento cambió") == true)
        assertEquals(antes, editor.aJson(), "el rechazo de versión tocó el documento")
    }
}
