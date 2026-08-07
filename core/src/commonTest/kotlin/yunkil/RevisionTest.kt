package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pruebas de la revisión geométrica de un plan antes de enseñárselo al usuario.
 *
 * El `Interprete` solo comprueba que el plan **se pueda aplicar**: que el JSON
 * esté bien, que los tipos existan y que los alias resuelvan. Un plan puede pasar
 * esa criba entera y describir una pieza que no se sostiene, que no se puede
 * imprimir o cuyas restas no restan nada. Esta revisión mide el resultado sobre el
 * campo y devuelve el motivo en un castellano que el modelo pueda corregir.
 */
class RevisionTest {

    private fun planDe(editor: Editor, json: String) =
        checkNotNull(editor.interpretarPlan(json.trimIndent()).plan) {
            "el plan de prueba no se pudo interpretar: ${editor.interpretarPlan(json.trimIndent()).motivoDelRechazo}"
        }

    @Test
    fun `una pieza que flota sobre otra no pasa la revision`() {
        val editor = Editor(Documento.vacio())
        val plan = planDe(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"base","nombre":"Base",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"crear","tipo":"CAJA","alias":"tapa","nombre":"Tapa",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"mover","objetivo":"tapa","y":40,"absoluto":true}
            ]}
            """,
        )

        val revision = editor.revisarPlan(plan)

        assertFalse(revision.aceptable, "la tapa flota a 30 mm de la base y la revisión la dio por buena")
        // Nombrar la pieza no es cosmético: es lo que permite al modelo corregir el
        // «colocar» que le faltó en vez de rehacer el plan entero a ciegas.
        assertTrue(
            "Tapa" in revision.informeParaModelo,
            "el informe no dice qué pieza está suelta: ${revision.informeParaModelo}",
        )
    }

    @Test
    fun `la misma tapa apoyada con colocar si pasa la revision`() {
        val editor = Editor(Documento.vacio())
        val plan = planDe(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"base","nombre":"Base",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"crear","tipo":"CAJA","alias":"tapa","nombre":"Tapa",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"colocar","objetivo":"tapa","referencia":"base","cara":"arriba","centrar":true}
            ]}
            """,
        )

        val revision = editor.revisarPlan(plan)

        // Si esto falla, el revisor rechaza modelos correctos y el bucle de
        // corrección se convierte en un generador de reintentos infinitos.
        assertTrue(revision.aceptable, "rechazó una pila correcta: ${revision.informeParaModelo}")
    }

    @Test
    fun `una operacion que se cayo por el camino se le devuelve al modelo`() {
        val editor = Editor(Documento.vacio())
        val plan = planDe(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"base","nombre":"Base",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"fijar","objetivo":"base","clave":"radio","valor":5}
            ]}
            """,
        )

        val revision = editor.revisarPlan(plan)

        // El plan «funcionó»: la caja existe. Pero el modelo pidió un radio que una
        // caja no tiene, y si nadie se lo dice da por hecho que lo consiguió.
        assertFalse(revision.aceptable, "dio por bueno un plan al que se le cayó una operación")
        assertTrue(
            "radio" in revision.informeParaModelo,
            "el informe no dice qué operación se perdió: ${revision.informeParaModelo}",
        )
    }

    @Test
    fun `una resta que no toca la pieza no pasa la revision`() {
        val editor = Editor(Documento.vacio())
        val plan = planDe(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"cuerpo","nombre":"Cuerpo",
               "parametros":{"anchura":40,"altura":20,"profundidad":40}},
              {"op":"envolver","objetivo":"cuerpo","tipo":"DIFERENCIA","alias":"resta"},
              {"op":"crear","tipo":"CILINDRO","alias":"agujero","padre":"resta","nombre":"Agujero",
               "parametros":{"radio":5,"altura":30}},
              {"op":"mover","objetivo":"agujero","x":200,"absoluto":true}
            ]}
            """,
        )

        val revision = editor.revisarPlan(plan)

        // El modelo cree que ha hecho un agujero. No hay agujero: el cilindro está a
        // 180 mm de la pieza y la diferencia no quita absolutamente nada.
        assertFalse(revision.aceptable, "dio por buena una resta que no corta nada")
        assertTrue(
            "Agujero" in revision.informeParaModelo,
            "el informe no dice qué resta es inútil: ${revision.informeParaModelo}",
        )
    }

    @Test
    fun `el mensaje de revision no acusa al modelo de haber escrito mal el plan`() {
        val mensaje = yunkil.ia.Vocabulario.revision(
            motivos = listOf("«Tapa» flota en el aire (a 30 mm de lo más cercano)"),
            respuestaAnterior = """{"operaciones":[]}""",
        )

        // El plan era correcto como JSON: repetirle que use «el esquema permitido» lo
        // manda a arreglar lo único que sí había hecho bien.
        assertFalse("esquema" in mensaje, "el mensaje culpa al formato: $mensaje")
        assertTrue("Tapa" in mensaje, "el mensaje no lleva la medida concreta: $mensaje")
    }

    @Test
    fun `una pieza real con patas y un agujero pasa la revision`() {
        val editor = Editor(Documento.vacio())
        val plan = planDe(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"DIFERENCIA","alias":"resta","nombre":"Cuerpo"},
              {"op":"crear","tipo":"CAJA","alias":"placa","padre":"resta","nombre":"Placa",
               "parametros":{"anchura":60,"altura":8,"profundidad":60}},
              {"op":"crear","tipo":"CILINDRO","alias":"agujero","padre":"resta","nombre":"Agujero",
               "parametros":{"radio":6,"altura":40}},
              {"op":"crear","tipo":"CILINDRO","alias":"pata","nombre":"Pata",
               "parametros":{"radio":5,"altura":20}},
              {"op":"colocar","objetivo":"pata","referencia":"resta","cara":"abajo","centrar":true}
            ]}
            """,
        )

        val revision = editor.revisarPlan(plan)

        // Aquí hay de todo lo que el revisor vigila —una diferencia que sí corta, una
        // pata apoyada, varias piezas— y no debe protestar por nada.
        assertTrue(revision.aceptable, "protestó por un modelo correcto: ${revision.informeParaModelo}")
    }

    @Test
    fun `una esfera suelta no es un error de modelado`() {
        // Una esfera apoya en un punto, así que el analizador avisa —con razón— de
        // que hay material que arranca en el aire. Pero eso no es un plan mal hecho:
        // es una pieza que necesita soporte, que es cosa del laminador y del usuario.
        // Devolvérselo al modelo como si hubiera fallado lo manda a deformar la
        // esfera para esquivar un voladizo que no tiene arreglo geométrico.
        val editor = Editor(Documento.vacio())
        val plan = planDe(editor, """{"reemplazar":true,"operaciones":[{"op":"crear","tipo":"ESFERA","alias":"bola"}]}""")

        val revision = editor.revisarPlan(plan)

        assertTrue(revision.aceptable, "rechaza una esfera: ${revision.informeParaModelo}")
    }

    @Test
    fun `un cono suelto no es un error de modelado`() {
        // La punta de un cono mide menos que la boquilla por definición. Pedirle al
        // modelo que la arregle es pedirle que deje de hacer conos.
        val editor = Editor(Documento.vacio())
        val plan = planDe(editor, """{"reemplazar":true,"operaciones":[{"op":"crear","tipo":"CONO","alias":"punta"}]}""")

        assertTrue(editor.revisarPlan(plan).aceptable, "rechaza un cono por tener punta")
    }

    @Test
    fun `una pared imposible si sigue siendo un error de modelado`() {
        // El contraste que da sentido a lo anterior: esto no se arregla con soportes
        // ni girando la pieza, se arregla cambiando la cota. Eso sí vuelve al modelo.
        val editor = Editor(Documento.vacio())
        val plan = planDe(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"c","nombre":"Chapa",
               "parametros":{"anchura":40,"altura":0.3,"profundidad":40,"redondeo":0}}
            ]}
            """,
        )

        assertFalse(editor.revisarPlan(plan).aceptable, "da por buena una chapa de 0,3 mm")
    }

    @Test
    fun `revisar no toca el documento`() {
        val editor = Editor(Documento.vacio())
        val antes = editor.aJson()
        val plan = planDe(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"base",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}}
            ]}
            """,
        )

        editor.revisarPlan(plan)

        assertEquals(antes, editor.aJson(), "la revisión dejó rastro en el documento")
        assertFalse(editor.puedeDeshacer, "la revisión metió un punto de deshacer")
    }
}
