package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.Acotar
import yunkil.ia.Aplicador
import yunkil.ia.Crear
import yunkil.ia.EjeNombrado
import yunkil.ia.PlanDeModelado
import yunkil.ia.Taladro
import yunkil.kernel.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El historial navegable: los planes aplicados se pueden reproducir, y un plan del
 * pasado se puede reemplazar sin deshacer lo que vino después.
 *
 * Es la mitad de la promesa de Shapr3D/Fusion que el modelo paramétrico ya tenía
 * a medias: el documento es paramétrico, pero cambiarlo exigía deshacer. Con la
 * lista de planes aplicados, «los agujeros eran M3, ponlos M4» es reemplazar un
 * plan y reproducir el resto.
 */
class HistorialNavegableTest {

    private fun planConTaladro(designacion: String): PlanDeModelado = PlanDeModelado(
        resumen = "Caja con agujeros $designacion",
        operaciones = listOf(
            Crear(
                tipo = "CAJA", alias = "caja",
                parametros = mapOf("anchura" to 40f, "altura" to 20f, "profundidad" to 40f),
            ),
            Taladro(objetivo = "caja", designacion = designacion, desplazamiento = listOf(0f, 0f)),
        ),
    )

    @Test
    fun `un plan aplicado queda registrado en el documento`() {
        val editor = Editor(Documento.vacio())
        val resultado = editor.aplicarPlan(planConTaladro("M3"), null)
        assertTrue(resultado.exito, "no se aplicó: ${resultado.resumen}")

        assertEquals(1, editor.planesAplicados.size)
        assertEquals("Caja con agujeros M3", editor.planesAplicados[0].resumen)
    }

    @Test
    fun `los planes aplicados sobreviven al guardado y la apertura`() {
        val editor = Editor(Documento.vacio())
        editor.aplicarPlan(planConTaladro("M3"), null)
        val texto = editor.aJson()

        val reabierto = Editor(Documento.vacio())
        assertTrue(reabierto.desdeJson(texto = texto), "no se pudo reabrir: ${reabierto.ultimoError}")
        assertEquals(1, reabierto.planesAplicados.size)
    }

    @Test
    fun `reproducir los planes devuelve la misma geometria`() {
        val editor = Editor(Documento.vacio())
        val resultado = editor.aplicarPlan(planConTaladro("M3"), null)
        assertTrue(resultado.exito)

        val reproducido = assertNotNull(editor.reproducirPlanes())
        val original = editor.documentoActual

        // La geometría compilada tiene que coincidir: el mismo campo, las mismas cotas.
        val nodoOriginal = assertNotNull(original.compilar())
        val nodoReproducido = assertNotNull(reproducido.compilar())
        val cotasOriginal = nodoOriginal.cotas()
        val cotasReproducidas = nodoReproducido.cotas()
        assertEquals(cotasOriginal.min, cotasReproducidas.min, "mínimos distintos")
        assertEquals(cotasOriginal.max, cotasReproducidas.max, "máximos distintos")
    }

    @Test
    fun `cambiar M3 por M4 en el historial rehace el documento completo`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.aplicarPlan(planConTaladro("M3"), null).exito)

        // El agujero M3 pasante tiene el diámetro de tabla (3,4 mm) más holgura.
        // Se comprueba contra el campo, no contra el texto: es la verdad de referencia.
        val antes = assertNotNull(editor.documentoActual.compilar())
        val radioM3 = radioDelAgujero(antes, Vec3(0f, 0f, 0f))
        assertTrue(radioM3 in 1.7f..2.1f, "radio M3 inesperado: $radioM3")

        val reemplazo = planConTaladro("M4")
        assertTrue(editor.reemplazarPlanEnHistoria(0, reemplazo), "no se reemplazó: ${editor.ultimoError}")

        val despues = assertNotNull(editor.documentoActual.compilar())
        val radioM4 = radioDelAgujero(despues, Vec3(0f, 0f, 0f))
        assertTrue(radioM4 > radioM3 + 0.3f, "el agujero no creció: M3 $radioM3 → M4 $radioM4")
        assertEquals("Caja con agujeros M4", editor.planesAplicados[0].resumen)
        assertEquals(1, editor.planesAplicados.size, "la historia no debe crecer al reemplazar")
    }

    @Test
    fun `reemplazar un plan es un unico punto de deshacer`() {
        val editor = Editor(Documento.vacio())
        editor.aplicarPlan(planConTaladro("M3"), null)

        assertTrue(editor.reemplazarPlanEnHistoria(0, planConTaladro("M4")))
        // Un solo deshacer vuelve al M3: el reemplazo fue un punto, no dos.
        assertTrue(editor.deshacer())
        val vuelto = assertNotNull(editor.documentoActual.compilar())
        assertTrue(radioDelAgujero(vuelto, Vec3(0f, 0f, 0f)) < 2.1f)
        assertEquals("Caja con agujeros M3", editor.planesAplicados[0].resumen)
    }

    @Test
    fun `un indice fuera de rango se rechaza sin tocar el documento`() {
        val editor = Editor(Documento.vacio())
        editor.aplicarPlan(planConTaladro("M3"), null)

        assertTrue(!editor.reemplazarPlanEnHistoria(5, planConTaladro("M4")))
        assertNotNull(editor.ultimoError, "debería explicar el rechazo")
        assertEquals(1, editor.planesAplicados.size)
        assertEquals("Caja con agujeros M3", editor.planesAplicados[0].resumen)
    }

    @Test
    fun `reproducir tras un plan que ya no se puede aplicar explica el motivo`() {
        val editor = Editor(Documento.vacio())
        // Un plan con una operación inválida se aplica como omitida: la historia
        // guarda lo que se aplicó, y la reproducción re-ejecuta lo mismo.
        editor.aplicarPlan(
            PlanDeModelado(
                operaciones = listOf(
                    Crear(tipo = "CAJA", alias = "caja"),
                    Acotar(objetivo = "caja", eje = EjeNombrado.X, medida = 50f),
                ),
            ),
            null,
        )
        val reproducido = assertNotNull(editor.reproducirPlanes())
        val original = assertNotNull(editor.documentoActual.compilar())
        assertEquals(original.cotas().max.x, reproducido.compilar()?.cotas()?.max?.x)
    }

    /** El radio del agujero central, leyendo el campo hacia +X desde el centro. */
    private fun radioDelAgujero(nodo: yunkil.kernel.SdfNode, centro: Vec3): Float {
        // Dentro del agujero el campo es positivo (vacío); el material vuelve a
        // negativo al otro lado de la pared. El radio es hasta la pared lejana.
        var t = 0f
        while (t < 100f) {
            if (nodo.evaluar(centro + Vec3(t, 0f, 0f)) < 0f) return t
            t += 0.05f
        }
        return 100f
    }
}
