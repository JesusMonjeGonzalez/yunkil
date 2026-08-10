package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Medir entre dos piezas.
 *
 * Es lo que tiene cualquier editor 3D y aquí no había: el analizador de fabricación sabía
 * medir huecos —los usa para avisar de holguras y de interferencias— pero ese número no
 * se podía pedir. Había que provocar un aviso para enterarse de cuánto separaba dos
 * piezas, que es como usar la alarma de incendios de termómetro.
 *
 * Se mide **sobre el campo y no sobre las cajas envolventes**: dos cilindros separados en
 * diagonal tienen las cajas solapadas y las piezas a tres milímetros, y quien pregunta
 * quiere los tres milímetros.
 */
class MedirTest {

    private fun editorCon(plan: String): Editor {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(plan.trimIndent())
        val interpretado = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        val aplicado = editor.aplicarPlan(interpretado, null)
        assertTrue(aplicado.exito, "no se aplicó: ${aplicado.resumen}")
        return editor
    }

    /** Dos cajas de 20 de lado, centradas en X a la distancia que se pida. */
    private fun dosCajas(separacionEntreCentros: Float): Pair<Editor, Pair<String, String>> {
        val editor = editorCon(
            """
            {"resumen":"Dos cajas","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"a","nombre":"A",
               "parametros":{"anchura":20,"altura":20,"profundidad":20}},
              {"op":"crear","tipo":"CAJA","alias":"b","nombre":"B",
               "parametros":{"anchura":20,"altura":20,"profundidad":20}},
              {"op":"mover","objetivo":"b","x":$separacionEntreCentros,"absoluto":true}
            ]}
            """
        )
        val a = assertNotNull(editor.filas().firstOrNull { it.nombre == "A" }).id
        val b = assertNotNull(editor.filas().firstOrNull { it.nombre == "B" }).id
        return editor to (a to b)
    }

    @Test
    fun `dos piezas separadas dan el hueco que las separa`() {
        // Centros a 50, lados de 20: 50 − 10 − 10 = 30 de aire.
        val (editor, ids) = dosCajas(50f)
        val m = assertNotNull(editor.medirEntre(ids.first, ids.second), "no midió")

        assertTrue(abs(m.hueco - 30f) < 1f, "el hueco salió ${m.hueco} y son 30")
        assertTrue(m.solape < 0.001f, "no se tocan, no puede haber solape: ${m.solape}")
        assertTrue(abs(m.entreCentros - 50f) < 0.01f, "entre centros hay ${m.entreCentros}")
    }

    @Test
    fun `dos piezas que se tocan dan hueco cero`() {
        val (editor, ids) = dosCajas(20f)
        val m = assertNotNull(editor.medirEntre(ids.first, ids.second))
        assertTrue(m.hueco < 1f, "se tocan y el hueco salió ${m.hueco}")
    }

    @Test
    fun `dos piezas que se meten una en otra declaran el solape`() {
        // Centros a 10 con lados de 20: se solapan 10 mm.
        val (editor, ids) = dosCajas(10f)
        val m = assertNotNull(editor.medirEntre(ids.first, ids.second))

        assertTrue(m.hueco < 0.001f, "solapadas no hay hueco: ${m.hueco}")
        assertTrue(m.solape > 1f, "se meten 10 mm y el solape salió ${m.solape}")
    }

    @Test
    fun `el desglose por eje dice hacia dónde está la otra pieza`() {
        val (editor, ids) = dosCajas(50f)
        val m = assertNotNull(editor.medirEntre(ids.first, ids.second))

        assertTrue(abs(m.porEje[0] - 50f) < 0.01f, "en X hay ${m.porEje[0]}")
        assertTrue(abs(m.porEje[1]) < 0.01f, "en Y no se han movido: ${m.porEje[1]}")
        assertTrue(abs(m.porEje[2]) < 0.01f, "en Z tampoco: ${m.porEje[2]}")
    }

    @Test
    fun `medir una pieza contra sí misma no tiene sentido y se dice`() {
        val (editor, ids) = dosCajas(50f)
        assertNull(editor.medirEntre(ids.first, ids.first))
    }

    @Test
    fun `medir algo que no existe no revienta`() {
        val (editor, ids) = dosCajas(50f)
        assertNull(editor.medirEntre(ids.first, "no-existe"))
    }
}
