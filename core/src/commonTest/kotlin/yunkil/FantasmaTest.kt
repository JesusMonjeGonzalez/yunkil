package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.kernel.Caja
import yunkil.kernel.Cilindro
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vec3
import yunkil.kernel.empaquetarUniforms
import yunkil.msl.MslGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * La previsualización fantasma: ver la propuesta de la IA **sobre la pieza** antes de
 * aceptarla, y no leerla en una lista.
 *
 * El shader lleva entonces dos árboles a la vez —el documento y lo que dejaría el
 * plan— y con eso la clasificación de cada punto es una lectura de dos signos: dentro
 * de los dos es material que se queda, dentro solo del fantasma es material que se
 * añade, dentro solo del documento es material que se quita. A un B-rep esto le cuesta
 * dos booleanas; aquí es comparar dos distancias.
 *
 * Lo que se comprueba aquí es lo que puede romperse **en silencio**: los dos árboles
 * comparten un solo buffer de uniforms, así que un desfase de un hueco no da error de
 * compilación, da un fantasma con otras cotas.
 */
class FantasmaTest {

    private val generador = MslGenerator()

    private fun documentoYFantasma(): Pair<yunkil.kernel.SdfNode, yunkil.kernel.SdfNode> {
        val base = modeloDePrueba()
        val añadido = Transformado(Esfera(6f), Transform(translation = Vec3(0f, 14f, 0f)))
        return base to Diferencia(Union(base, añadido, fusion = 1.5f), Cilindro(3f, 60f))
    }

    @Test
    fun `el shader con fantasma reserva los uniforms de los dos arboles`() {
        val (base, fantasma) = documentoYFantasma()
        val shader = generador.generar(base, fantasma)

        // Los dos buffers viajan concatenados: primero el del documento entero
        // —escalares y cajas— y después el del fantasma. Si el generador contara otra
        // cosa, el fantasma leería la cola del documento y saldría una pieza inventada.
        assertEquals(
            base.empaquetarUniforms().size + fantasma.empaquetarUniforms().size,
            shader.numeroDeUniforms,
            "el shader leería fuera del buffer o dejaría huecos",
        )
        assertTrue(shader.fuente.contains("yk_fantasma"), "el fantasma no llegó al shader")
    }

    @Test
    fun `sin fantasma el shader sale exactamente igual que antes`() {
        // La misma garantía que se le exigió a las texturas de malla: con el camino
        // nuevo apagado, la fuente no cambia ni un byte. Es lo que convierte «no he
        // roto los casos de paridad» en un hecho comprobable en vez de una promesa.
        val base = modeloDePrueba()
        assertEquals(generador.generar(base).fuente, generador.generar(base, null).fuente)
    }

    @Test
    fun `la huella cambia al poner y al quitar el fantasma`() {
        // El renderizador recompila comparando huellas. Si el fantasma no entrara en la
        // huella, la propuesta se enseñaría con el shader anterior: o sea, no se
        // enseñaría, y nada lo diría.
        val (base, fantasma) = documentoYFantasma()
        assertNotEquals(
            generador.generar(base).huellaTopologica,
            generador.generar(base, fantasma).huellaTopologica,
        )
    }

    @Test
    fun `el paso seguro del shader es el mas exigente de los dos arboles`() {
        // El trazado avanza por el mínimo de los dos campos. Un fantasma con filete
        // local necesita pasos más cortos, y quedarse con el paso del documento —que no
        // lo tiene— dejaría agujeros justo en el canto que se está proponiendo.
        val base = Caja(Vec3(10f, 10f, 10f))
        val conFilete = yunkil.kernel.AcuerdoLocal(
            Caja(Vec3(10f, 10f, 10f)),
            Transformado(Caja(Vec3(10f, 10f, 10f)), Transform(translation = Vec3(20f, 0f, 0f))),
            yunkil.kernel.ModoDeAcuerdo.UNION,
            centro = Vec3(10f, 10f, 0f),
            radio = 6f,
            fusion = 4f,
        )
        assertEquals(1f, generador.generar(base).pasoSeguro, "una caja sola es 1-Lipschitz")
        assertTrue(
            generador.generar(base, conFilete).pasoSeguro < 1f,
            "el paso del fantasma no llegó al shader",
        )
    }

    // ------------------------------------------------------------------ el editor

    private fun editorConCaja(): Pair<Editor, yunkil.ia.PlanDeModelado> {
        val editor = Editor(Documento.vacio())
        val creacion = editor.interpretarPlan(
            """
            {"resumen":"Base","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"b","nombre":"Base",
               "parametros":{"anchura":60,"altura":20,"profundidad":40}}
            ]}
            """.trimIndent()
        )
        assertTrue(editor.aplicarPlan(assertNotNull(creacion.plan), null).exito)

        val propuesta = editor.interpretarPlan(
            """
            {"resumen":"Torre","reemplazar":false,"operaciones":[
              {"op":"crear","tipo":"CILINDRO","alias":"t","nombre":"Torre",
               "parametros":{"radio":8,"altura":30}},
              {"op":"mover","objetivo":"t","y":25}
            ]}
            """.trimIndent()
        )
        return editor to assertNotNull(propuesta.plan, "no se interpretó: ${propuesta.motivoDelRechazo}")
    }

    @Test
    fun `previsualizar deja el buffer cuadrado con lo que el shader declara`() {
        // Es la prueba que caza el fallo callado: si `uniforms()` y `numeroDeUniforms`
        // se separan, el shader lee memoria de nadie y la pantalla dibuja cualquier cosa.
        val (editor, plan) = editorConCaja()
        assertEquals(editor.uniforms().size, editor.numeroDeUniforms, "sin fantasma ya estaba mal")

        assertTrue(editor.previsualizar(plan), "poner el fantasma tiene que pedir recompilación")
        assertEquals(editor.uniforms().size, editor.numeroDeUniforms, "con el fantasma puesto")

        assertTrue(editor.previsualizar(null), "quitarlo también")
        assertEquals(editor.uniforms().size, editor.numeroDeUniforms, "tras quitarlo")
    }

    @Test
    fun `previsualizar no toca el documento ni el historial`() {
        // Mirar una propuesta no puede cambiar la pieza: es exactamente la promesa que
        // hace el panel al enseñarla con casillas antes de aplicar nada.
        val (editor, plan) = editorConCaja()
        val antes = editor.aJson()
        val deshacer = editor.puedeDeshacer
        val filas = editor.filas().size

        editor.previsualizar(plan)

        assertEquals(antes, editor.aJson(), "el fantasma tocó el documento")
        assertEquals(deshacer, editor.puedeDeshacer, "el fantasma metió un punto de deshacer")
        assertEquals(filas, editor.filas().size, "el fantasma apareció en el árbol")
    }

    @Test
    fun `quitar el fantasma devuelve el shader de siempre`() {
        val (editor, plan) = editorConCaja()
        val original = editor.fuenteMsl
        editor.previsualizar(plan)
        assertNotEquals(original, editor.fuenteMsl, "el fantasma no cambió el shader")
        editor.previsualizar(null)
        assertEquals(original, editor.fuenteMsl, "no se volvió al shader del documento")
    }

    @Test
    fun `un plan que no se puede aplicar no deja fantasma`() {
        // Sin esto, una propuesta rota apagaría el viewport o —peor— enseñaría el
        // documento anterior haciéndolo pasar por la propuesta.
        val (editor, _) = editorConCaja()
        val roto = editor.interpretarPlan(
            """
            {"resumen":"Nada","reemplazar":false,"operaciones":[
              {"op":"mover","objetivo":"noExiste","desplazamiento":[0,10,0]}
            ]}
            """.trimIndent()
        )
        val plan = roto.plan
        if (plan != null) {
            assertFalse(editor.previsualizar(plan), "un plan que no aplica no puede pintar nada")
            assertFalse(editor.hayFantasma, "quedó un fantasma de un plan que no se aplicó")
        }
    }

    @Test
    fun `se puede previsualizar solo la parte marcada`() {
        // Las casillas del panel cambian lo que se aplicaría, así que tienen que cambiar
        // lo que se ve. Un fantasma que enseña la propuesta entera mientras el usuario
        // desmarca operaciones está mintiendo justo en el momento de decidir.
        val (editor, plan) = editorConCaja()
        editor.previsualizar(plan)
        val entero = editor.uniforms()

        editor.previsualizar(plan, listOf(0))
        assertNotEquals(entero, editor.uniforms(), "desmarcar el movimiento no cambió el fantasma")
        assertEquals(editor.uniforms().size, editor.numeroDeUniforms)
    }
}
