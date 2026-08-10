package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.compilar
import yunkil.kernel.Vec3
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PatronCircularTest {

    @Test
    fun `la ia crea un patron circular parametrico visible en cpu y metal`() {
        val editor = Editor(Documento.vacio())
        val plan = assertNotNull(
            editor.interpretarPlan(
                """
                {"reemplazar":true,"operaciones":[
                  {"op":"crear","tipo":"CAJA","alias":"diente","parametros":{"anchura":4,"altura":6,"profundidad":2},"posicion":[20,0,0]},
                  {"op":"envolver","objetivo":"diente","tipo":"REPETICION_CIRCULAR","eje":"Y","cuenta":4,"parametros":{"angulo":360}}
                ]}
                """.trimIndent(),
            ).plan,
        )

        assertTrue(editor.aplicarPlan(plan).exito)
        val nodo = assertNotNull(editor.documentoActual.compilar())
        assertTrue(nodo.evaluar(Vec3(20f, 0f, 0f)) < 0f)
        assertTrue(nodo.evaluar(Vec3(0f, 0f, 20f)) < 0f)
        assertTrue("cos(" in editor.fuenteMsl && "sin(" in editor.fuenteMsl)
    }
}
