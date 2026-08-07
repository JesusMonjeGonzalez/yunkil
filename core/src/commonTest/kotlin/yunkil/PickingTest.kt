package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.Pieza
import yunkil.doc.TipoPieza
import yunkil.doc.atribuir
import yunkil.doc.compilar
import yunkil.doc.solo
import yunkil.kernel.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Señalar una pieza en el viewport.
 *
 * Mover una pieza era teclear números porque faltaba el eslabón anterior: saber qué
 * pieza hay debajo del cursor. Se resuelve en el núcleo y no en el shader, y esa
 * decisión tiene motivo: la evaluación en CPU es la verdad de referencia del sistema,
 * así que señalar no puede discrepar de lo que se exporta ni de lo que analiza el
 * analizador. Además funciona sobre una `MALLA` horneada, que el shader todavía no
 * sabe ni pintar.
 *
 * La regla de atribución es un hecho geométrico, no una política: la superficie que
 * hay en el punto de impacto pertenece a la pieza cuyo propio contorno pasa por ahí.
 * De eso sale lo que uno quiere que pase al pinchar la pared de un agujero —que se
 * seleccione el taladro y su diámetro aparezca en el inspector—, y no hay que
 * escribir ninguna excepción para conseguirlo.
 */
class PickingTest {

    private fun caja(editor: Editor, padre: String?, anchura: Float, altura: Float, profundidad: Float): String {
        editor.anadir(TipoPieza.CAJA.name, padre)
        val id = assertNotNull(editor.seleccionado)
        editor.fijarParametro(id, "anchura", anchura)
        editor.fijarParametro(id, "altura", altura)
        editor.fijarParametro(id, "profundidad", profundidad)
        editor.fijarParametro(id, "redondeo", 0f)
        return id
    }

    private fun esfera(editor: Editor, padre: String?, radio: Float): String {
        editor.anadir(TipoPieza.ESFERA.name, padre)
        val id = assertNotNull(editor.seleccionado)
        editor.fijarParametro(id, "radio", radio)
        return id
    }

    @Test
    fun `un rayo hacia el origen devuelve la pieza y su superficie`() {
        val editor = Editor(Documento.vacio())
        val id = esfera(editor, null, 10f)

        val golpe = assertNotNull(
            editor.senalar(0f, 0f, 100f, 0f, 0f, -1f),
            "el rayo tenía que dar en la esfera",
        )

        assertEquals(id, golpe.piezaId)
        // La superficie de una esfera de radio 10 centrada en el origen está en z = 10.
        assertTrue(abs(golpe.z - 10f) < 0.05f, "impacto en z=${golpe.z}, esperado 10")
        assertTrue(golpe.nz > 0.99f, "la normal tenía que apuntar hacia el rayo: ${golpe.nz}")
    }

    @Test
    fun `un rayo que no cruza nada no devuelve impacto`() {
        val editor = Editor(Documento.vacio())
        caja(editor, null, 20f, 20f, 20f)
        assertNull(editor.senalar(0f, 500f, 100f, 0f, 0f, -1f))
    }

    @Test
    fun `un documento vacio no devuelve impacto`() {
        assertNull(Editor(Documento.vacio()).senalar(0f, 0f, 100f, 0f, 0f, -1f))
    }

    @Test
    fun `entre dos piezas gana la que el rayo encuentra antes`() {
        val editor = Editor(Documento.vacio())
        val cerca = caja(editor, null, 20f, 20f, 20f)
        editor.mover(cerca, 0f, 0f, 40f, absoluto = true)
        val lejos = caja(editor, null, 20f, 20f, 20f)
        editor.mover(lejos, 0f, 0f, -40f, absoluto = true)

        assertEquals(cerca, assertNotNull(editor.senalar(0f, 0f, 200f, 0f, 0f, -1f)).piezaId)
        assertEquals(lejos, assertNotNull(editor.senalar(0f, 0f, -200f, 0f, 0f, 1f)).piezaId)
    }

    @Test
    fun `la pared de un agujero pertenece al taladro y la cara exterior a la caja`() {
        // Caja de 40x20x40 con un cilindro vertical de radio 3 restado en el centro:
        // el caso que hace útil el picking en una herramienta paramétrica.
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.DIFERENCIA.name, null)
        val diferencia = assertNotNull(editor.seleccionado)

        val bloque = caja(editor, diferencia, 40f, 20f, 40f)

        editor.anadir(TipoPieza.CILINDRO.name, diferencia)
        val taladro = assertNotNull(editor.seleccionado)
        editor.fijarParametro(taladro, "radio", 3f)
        editor.fijarParametro(taladro, "altura", 60f)
        editor.fijarParametro(taladro, "redondeo", 0f)

        // La cara superior de la caja, lejos del agujero.
        assertEquals(bloque, editor.atribuir(15f, 10f, 0f))
        // La pared del agujero: ahí el contorno que pasa es el del cilindro.
        assertEquals(taladro, editor.atribuir(3f, 0f, 0f))
    }

    @Test
    fun `una pieza oculta no se puede senalar`() {
        val editor = Editor(Documento.vacio())
        val id = caja(editor, null, 20f, 20f, 20f)
        editor.fijarVisible(id, false)

        assertNull(editor.senalar(0f, 0f, 100f, 0f, 0f, -1f))
    }

    @Test
    fun `la copia reflejada de una simetria se atribuye a la pieza original`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.SIMETRIA.name, null)
        val simetria = assertNotNull(editor.seleccionado)
        editor.fijarEje(simetria, "X")

        val id = esfera(editor, simetria, 5f)
        editor.mover(id, 30f, 0f, 0f, absoluto = true)

        // El reflejo vive en x = −30 y no hay ninguna pieza ahí: la única que puede
        // haberlo producido es la esfera original.
        val golpe = assertNotNull(
            editor.senalar(-200f, 0f, 0f, 1f, 0f, 0f),
            "el reflejo tenía que estar ahí",
        )
        assertEquals(id, golpe.piezaId)
    }

    @Test
    fun `el rayo respeta la transformacion acumulada de los padres`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.UNION.name, null)
        val grupo = assertNotNull(editor.seleccionado)
        editor.mover(grupo, 0f, 0f, 50f, absoluto = true)

        val id = esfera(editor, grupo, 8f)

        val golpe = assertNotNull(
            editor.senalar(0f, 0f, 300f, 0f, 0f, -1f),
            "la esfera está en z=50 por el padre",
        )
        assertEquals(id, golpe.piezaId)
        assertTrue(abs(golpe.z - 58f) < 0.05f, "impacto en z=${golpe.z}, esperado 58")
    }

    @Test
    fun `atribuir un punto lejano no devuelve nada`() {
        val editor = Editor(Documento.vacio())
        caja(editor, null, 20f, 20f, 20f)
        assertNull(editor.atribuir(500f, 500f, 500f))
    }

    @Test
    fun `senalar no depende de la escala del modelo`() {
        // Una pieza de 400 mm y otra de 2 mm tienen que señalarse igual de bien: el
        // avance del trazado sale de la propia distancia, no de un paso fijo.
        val gordo = Editor(Documento.vacio())
        val grande = caja(gordo, null, 400f, 400f, 400f)
        assertEquals(grande, assertNotNull(gordo.senalar(0f, 0f, 5000f, 0f, 0f, -1f)).piezaId)

        val fino = Editor(Documento.vacio())
        val pequena = esfera(fino, null, 1f)
        assertEquals(pequena, assertNotNull(fino.senalar(0f, 0f, 40f, 0f, 0f, -1f)).piezaId)
    }

    @Test
    fun `la pieza aislada conserva los modificadores de sus padres`() {
        // `solo` es la maquinaria de la atribución: al quedarse con una rama tiene que
        // seguir pasando por el vaciado del padre, o el contorno que se compara no
        // sería el que de verdad se ve.
        val esfera = Pieza.nueva(TipoPieza.ESFERA).let { it.copy(parametros = it.parametros + ("radio" to 10f)) }
        val hueco = Pieza.nueva(TipoPieza.VACIADO).let {
            it.copy(
                parametros = it.parametros + ("grosor" to 2f),
                hijos = listOf(esfera, Pieza.nueva(TipoPieza.CAJA)),
            )
        }

        val soloLaEsfera = assertNotNull(hueco.solo(esfera.id))
        assertEquals(TipoPieza.VACIADO, soloLaEsfera.tipo)
        assertEquals(1, soloLaEsfera.hijos.size)

        // El campo de una cáscara de 2 mm sobre una esfera de radio 10 vale 0 en r = 11.
        val campo = assertNotNull(soloLaEsfera.compilar())
        assertTrue(abs(campo.evaluar(Vec3(11f, 0f, 0f))) < 0.01f, "la cáscara exterior no está en r=11")
    }

    @Test
    fun `una pieza que no esta en el arbol no aisla nada`() {
        assertNull(Pieza.nueva(TipoPieza.CAJA).solo("no-existe"))
    }
}
