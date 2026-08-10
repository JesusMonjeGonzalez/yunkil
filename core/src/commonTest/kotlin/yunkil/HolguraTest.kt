package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `holgura`: la cota que hay que dar para que dos piezas encajen de verdad.
 *
 * Es la misma familia que `taladro` y `acotar`, y existe por el mismo motivo. Un modelo
 * de lenguaje sabe perfectamente que un tapón para un tubo de 20 mm no puede medir 20,
 * y **no sabe cuánto menos**: eso depende de la boquilla, del material y de cuánto
 * engorda la impresora, que es justo lo que el perfil de fabricación tiene tabulado y el
 * modelo no puede adivinar. Pedirle el número es garantizar que se equivoque.
 *
 * Dos sentidos, porque el error se comete en los dos:
 *
 *  - `ENTRA`: la pieza va dentro de un hueco que mide lo dicho, así que sale más pequeña.
 *  - `RECIBE`: la pieza **es** el hueco y tiene que tragarse algo que mide lo dicho, así
 *    que sale más grande.
 *
 * Es la misma cuenta que ya hace `Roscas` para el agujero de paso —nominal más dos
 * holguras—, sacada de la rosca y puesta donde vale para cualquier encaje.
 */
class HolguraTest {

    private fun editorCon(plan: String): Pair<Editor, yunkil.ia.ResultadoDeAplicacion> {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(plan.trimIndent())
        val interpretado = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        return editor to editor.aplicarPlan(interpretado, null)
    }

    private fun anchoDe(editor: Editor): Float = editor.cotaMaxima[0] - editor.cotaMinima[0]

    @Test
    fun `un tapon para un agujero de 20 sale por debajo de 20`() {
        val (editor, resultado) = editorCon(
            """
            {"resumen":"Tapón","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CILINDRO","alias":"t","nombre":"Tapón",
               "parametros":{"radio":15,"altura":10}},
              {"op":"holgura","objetivo":"t","eje":"X","medida":20,"encaje":"ENTRA"}
            ]}
            """
        )
        assertTrue(resultado.exito, "no se aplicó: ${resultado.resumen}")

        val ancho = anchoDe(editor)
        assertTrue(ancho < 20f, "mide $ancho y un tapón de 20 clavados no entra en un agujero de 20")
        assertTrue(ancho > 19f, "mide $ancho y bailaría dentro; la holgura es de décimas, no de milímetros")
    }

    @Test
    fun `un hueco que recibe un eje de 8 sale por encima de 8`() {
        val (editor, resultado) = editorCon(
            """
            {"resumen":"Casquillo","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CILINDRO","alias":"c","nombre":"Casquillo",
               "parametros":{"radio":10,"altura":10}},
              {"op":"holgura","objetivo":"c","eje":"X","medida":8,"encaje":"RECIBE"}
            ]}
            """
        )
        assertTrue(resultado.exito, "no se aplicó: ${resultado.resumen}")

        val ancho = anchoDe(editor)
        assertTrue(ancho > 8f, "mide $ancho: un agujero de 8 clavados no traga un eje de 8")
        assertTrue(ancho < 9f, "mide $ancho, que ya es un agujero de otra medida")
    }

    @Test
    fun `los dos sentidos se separan por el doble de la holgura`() {
        // No es cosmética: si «entra» y «recibe» dieran lo mismo, la operación no estaría
        // diciendo nada que `acotar` no dijera ya.
        val (dentro, _) = editorCon(
            """
            {"resumen":"A","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"a","parametros":{"anchura":10,"altura":10,"profundidad":10}},
              {"op":"holgura","objetivo":"a","eje":"X","medida":30,"encaje":"ENTRA"}
            ]}
            """
        )
        val (fuera, _) = editorCon(
            """
            {"resumen":"B","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"b","parametros":{"anchura":10,"altura":10,"profundidad":10}},
              {"op":"holgura","objetivo":"b","eje":"X","medida":30,"encaje":"RECIBE"}
            ]}
            """
        )
        val separacion = anchoDe(fuera) - anchoDe(dentro)
        assertTrue(separacion > 0.3f, "los dos sentidos se separan solo $separacion mm")
    }

    @Test
    fun `una medida imposible se rechaza en vez de dar una pieza del revés`() {
        // Una holgura mayor que la propia medida daría un tamaño negativo, y escalar por
        // un factor negativo devuelve la pieza del revés sin que nadie se entere.
        val (_, resultado) = editorCon(
            """
            {"resumen":"Imposible","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"a","parametros":{"anchura":10,"altura":10,"profundidad":10}},
              {"op":"holgura","objetivo":"a","eje":"X","medida":0.1,"encaje":"ENTRA"}
            ]}
            """
        )
        assertTrue(
            resultado.omitidas.isNotEmpty(),
            "una holgura que se come la medida entera debería quejarse: ${resultado.resumen}",
        )
    }
}
