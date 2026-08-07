package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.fabricacion.hojasEnMundo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coser un plan: aplicar el arreglo del revisor sin pasar por el modelo.
 *
 * El banco midió que el único modo de fallo que queda es el contacto entre piezas,
 * y que decírselo al modelo no basta: tres rondas repitiendo «usa colocar» no
 * arreglaban la pieza. Emitir la operación literal subió la geometría limpia de 6/8
 * a 7/8, y ahí se ve el límite del camino: la operación la escribe Yunkil, la copia
 * el modelo, y la copia puede salir mal.
 *
 * Si Yunkil ya sabe *exactamente* qué operación arregla la pieza, no hay ningún
 * motivo para pedirle a nadie que la transcriba. Se aplica, se vuelve a medir el
 * campo, y solo se acepta si el sólido quedó entero de verdad.
 */
class CoserTest {

    private fun planDe(editor: Editor, json: String) =
        checkNotNull(editor.interpretarPlan(json.trimIndent()).plan) {
            "el plan de prueba no se pudo interpretar"
        }

    private val TAPA_FLOTANDO = """
        {"reemplazar":true,"operaciones":[
          {"op":"crear","tipo":"CAJA","alias":"base","nombre":"Base",
           "parametros":{"anchura":40,"altura":10,"profundidad":40}},
          {"op":"crear","tipo":"CAJA","alias":"tapa","nombre":"Tapa",
           "parametros":{"anchura":40,"altura":10,"profundidad":40}},
          {"op":"mover","objetivo":"tapa","y":40,"absoluto":true}
        ]}
    """

    @Test
    fun `una tapa que flota se cose sin gastar una ronda del modelo`() {
        val editor = Editor(Documento.vacio())
        val plan = planDe(editor, TAPA_FLOTANDO)
        assertTrue(!editor.revisarPlan(plan).aceptable, "el caso de prueba ya se sostenía")

        val cosido = editor.coserPlan(plan)

        assertNotNull(cosido, "el revisor sabía qué operación arreglaba la pieza y no la aplicó")
        assertTrue(
            editor.revisarPlan(cosido).aceptable,
            "el plan cosido sigue sin sostenerse: ${editor.revisarPlan(cosido).informeParaModelo}",
        )
    }

    @Test
    fun `coser no toca lo que el modelo ya escribio`() {
        val editor = Editor(Documento.vacio())
        val plan = planDe(editor, TAPA_FLOTANDO)

        val cosido = assertNotNull(editor.coserPlan(plan))

        // Un arreglo que reescribe el plan del modelo es un arreglo en el que no se
        // puede confiar: lo que se añade va al final y lo anterior queda intacto.
        assertEquals(plan.operaciones, cosido.operaciones.take(plan.operaciones.size))
        assertTrue(cosido.operaciones.size > plan.operaciones.size, "no añadió ninguna operación")
    }

    @Test
    fun `las piezas cosidas se solapan, no se quedan a contacto cero`() {
        val editor = Editor(Documento.vacio())
        val plan = planDe(editor, TAPA_FLOTANDO)
        val cosido = assertNotNull(editor.coserPlan(plan))

        editor.aplicarPlan(cosido)
        val hojas = editor.documentoActual.hojasEnMundo()
        val base = assertNotNull(hojas.firstOrNull { it.nombre == "Base" }).nodo.cotas()
        val tapa = assertNotNull(hojas.firstOrNull { it.nombre == "Tapa" }).nodo.cotas()

        // Dos sólidos que se tocan exactamente en un plano son un sólido para el
        // campo y un filo para el mallador. La receta que se le da al modelo dice
        // «solapa al menos el grosor de una pared»; el arreglo automático tiene que
        // cumplir su propio consejo.
        assertTrue(
            tapa.min.y < base.max.y - 1e-4f,
            "la tapa quedó a contacto cero: tapa.min.y=${tapa.min.y}, base.max.y=${base.max.y}",
        )
        // Y no puede tragarse la pieza: solapar de más cambia la cota pedida.
        assertTrue(
            base.max.y - tapa.min.y < 5f,
            "solapó media pieza: ${base.max.y - tapa.min.y} mm",
        )
    }

    @Test
    fun `un plan que ya se sostiene no se cose`() {
        val editor = Editor(Documento.vacio())
        val plan = planDe(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"base","nombre":"Base",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"crear","tipo":"CAJA","alias":"tapa","nombre":"Tapa",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"colocar","objetivo":"tapa","referencia":"base","cara":"arriba"}
            ]}
            """,
        )

        assertNull(editor.coserPlan(plan), "cosió una pieza que ya estaba entera")
    }

    @Test
    fun `una pieza suelta al lado, y no encima, se cose por la cara que toca`() {
        val editor = Editor(Documento.vacio())
        val plan = planDe(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"cuerpo","nombre":"Cuerpo",
               "parametros":{"anchura":40,"altura":20,"profundidad":20}},
              {"op":"crear","tipo":"CAJA","alias":"oreja","nombre":"Oreja",
               "parametros":{"anchura":10,"altura":20,"profundidad":20}},
              {"op":"mover","objetivo":"oreja","x":60,"absoluto":true}
            ]}
            """,
        )
        val cosido = assertNotNull(editor.coserPlan(plan))

        editor.aplicarPlan(cosido)
        val hojas = editor.documentoActual.hojasEnMundo()
        val cuerpo = assertNotNull(hojas.firstOrNull { it.nombre == "Cuerpo" }).nodo.cotas()
        val oreja = assertNotNull(hojas.firstOrNull { it.nombre == "Oreja" }).nodo.cotas()

        // La cara sale de dónde está la pieza, no de un valor por omisión: una oreja
        // que estaba a la derecha y se pega arriba está tan mal puesta como antes.
        assertTrue(oreja.min.x < cuerpo.max.x, "no la pegó por la derecha: oreja.min.x=${oreja.min.x}")
        assertTrue(oreja.min.x > cuerpo.center.x, "la metió dentro del cuerpo")
    }

    @Test
    fun `una operacion puede nombrar la pieza por su nombre y no solo por su alias`() {
        val editor = Editor(Documento.vacio())
        val plan = planDe(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"b","nombre":"Base",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"crear","tipo":"CAJA","alias":"t","nombre":"Tapa",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"colocar","objetivo":"Tapa","referencia":"Base","cara":"arriba"}
            ]}
            """,
        )

        val resultado = editor.aplicarPlan(plan)

        // El revisor le enseña al modelo los nombres de las piezas, no sus alias, y
        // el propio prompt está lleno de nombres. Exigir el alias convertía una
        // receta correcta en una operación omitida sin explicación.
        assertTrue(resultado.omitidas.isEmpty(), "no resolvió por nombre: ${resultado.omitidas}")
        val hojas = editor.documentoActual.hojasEnMundo()
        val base = assertNotNull(hojas.firstOrNull { it.nombre == "Base" }).nodo.cotas()
        val tapa = assertNotNull(hojas.firstOrNull { it.nombre == "Tapa" }).nodo.cotas()
        assertTrue(tapa.min.y >= base.max.y - 1e-4f, "la tapa no acabó encima de la base")
    }
}
