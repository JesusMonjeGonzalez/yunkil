package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.Acotar
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La post-condición de cotas: si la petición dijo cuánto tiene que medir la pieza, se
 * mide la pieza y, si no cuadra, se emite un `acotar`.
 *
 * Es el arnés que más paga porque ataca el fallo que cometen todos los modelos: las
 * proporciones salen bien y los milímetros no. El caso que lo motiva es real —se pidió
 * una pieza de 60 × 40 × 25 y salió de 76 × 38,6 × 56— y el arreglo ya existía en el
 * vocabulario sin que nadie lo usara para esto.
 *
 * `acotar` escala **uniforme**, así que arregla el tamaño y no las proporciones. Eso es
 * exactamente lo que hace falta cuando el modelo acierta la forma y falla la escala, y
 * por eso el arreglo solo se acepta si se mide que deja la pieza más cerca de lo pedido.
 */
class PostcondicionDeCotasTest {

    private fun editorConPlan(anchura: Float, altura: Float, profundidad: Float): Pair<Editor, yunkil.ia.PlanDeModelado> {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"resumen":"Caja","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"c","nombre":"Caja",
               "parametros":{"anchura":$anchura,"altura":$altura,"profundidad":$profundidad}}
            ]}
            """.trimIndent()
        )
        return editor to assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
    }

    private fun cotasTrasAplicar(editor: Editor, plan: yunkil.ia.PlanDeModelado): List<Float> {
        val banco = Editor(Documento.vacio())
        assertTrue(banco.aplicarPlan(plan, null).exito, "el plan acotado no se aplicó")
        val cotas = assertNotNull(banco.cotasDelModelo(), "no hay geometría que medir")
        return listOf(cotas.size.x, cotas.size.y, cotas.size.z)
    }

    @Test
    fun `una pieza a mitad de escala se acota al tamaño pedido`() {
        val (editor, plan) = editorConPlan(30f, 20f, 12.5f)

        val acotado = assertNotNull(
            editor.acotarPlan(plan, "Hazme una caja de 60 × 40 × 25 mm"),
            "la pieza mide la mitad de lo pedido; debería añadirse un acotar",
        )

        assertTrue(acotado.operaciones.any { it is Acotar }, "el arreglo tiene que ser un acotar")
        val (x, y, z) = cotasTrasAplicar(editor, acotado)
        assertTrue(abs(x - 60f) < 0.5f, "X quedó en $x y se pidió 60")
        assertTrue(abs(y - 40f) < 0.5f, "Y quedó en $y y se pidió 40")
        assertTrue(abs(z - 25f) < 0.5f, "Z quedó en $z y se pidió 25")
    }

    @Test
    fun `una pieza que ya mide lo pedido no se toca`() {
        val (editor, plan) = editorConPlan(60f, 40f, 25f)
        assertNull(editor.acotarPlan(plan, "Hazme una caja de 60 × 40 × 25 mm"))
    }

    @Test
    fun `sin cotas en la peticion no se acota nada`() {
        val (editor, plan) = editorConPlan(30f, 20f, 12.5f)
        assertNull(editor.acotarPlan(plan, "Hazme una caja bonita para tornillos"))
    }

    @Test
    fun `una cota nombrada acota su propio eje`() {
        val (editor, plan) = editorConPlan(40f, 10f, 30f)

        val acotado = assertNotNull(
            editor.acotarPlan(plan, "una placa de 8 cm de ancho"),
            "mide 40 de ancho y se pidieron 80",
        )
        val (x, _, _) = cotasTrasAplicar(editor, acotado)
        assertTrue(abs(x - 80f) < 0.5f, "X quedó en $x y se pidieron 80")
    }

    /**
     * Salió de una ejecución real del banco. La pieza medía 59,7 en X —clavada— y el
     * arnés la escaló a 62,7 porque así bajaba el error **medio** de las tres cotas.
     * Bajó la media y estropeó justo la cota que el banco comprobaba.
     *
     * Es la mitad de la regla que `coserPlan` ya tenía escrita y que aquí faltaba: no
     * basta con que el total mejore, hace falta además que **ninguna cota empeore**.
     * Un escalado que mejora la media a costa de una cota que ya estaba bien no es un
     * arreglo, es barajar.
     */
    @Test
    fun `no se escala si eso empeora una cota que ya estaba bien`() {
        val (editor, plan) = editorConPlan(59.7f, 38f, 22.6f)
        assertNull(
            editor.acotarPlan(plan, "una caja de 60 × 40 × 25 mm"),
            "X estaba a 0,5 % de lo pedido y escalar la habría llevado al 4,5 %",
        )
    }

    @Test
    fun `una pieza con las proporciones equivocadas no se da por buena a ciegas`() {
        // Se pidió 60 × 40 × 25 y sale 76 × 38,6 × 56: el caso real. Un escalado
        // uniforme no puede arreglar las proporciones, así que lo único que se exige
        // es que la pieza acabe más cerca de lo pedido de lo que estaba.
        val (editor, plan) = editorConPlan(76f, 38.6f, 56f)
        val acotado = assertNotNull(editor.acotarPlan(plan, "un soporte de 60 × 40 × 25 mm"))

        val cotas = cotasTrasAplicar(editor, acotado).sortedDescending()
        val pedidas = listOf(60f, 40f, 25f)
        val antes = listOf(76f, 56f, 38.6f).zip(pedidas).sumOf { (m, p) -> abs(m / p - 1f).toDouble() }
        val despues = cotas.zip(pedidas).sumOf { (m, p) -> abs(m / p - 1f).toDouble() }
        assertTrue(despues < antes, "el acotado no acercó nada: antes $antes, después $despues")
    }
}
