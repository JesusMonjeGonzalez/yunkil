package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Taladrar una extrusión por un punto de su propio contorno.
 *
 * Esta prueba nace de una ejecución real. Al darle a un modelo local con visión el
 * dibujo de una escuadra en L, razonó bien todo el plan y se atascó exactamente aquí:
 *
 * > «`desplazamiento` is from the piece's center. The centroid of this L-shape is
 * > roughly around (25, 25). But I don't need to calculate exact centroid… This is
 * > tricky. Alternative: create the L-shape as two boxes joined…»
 *
 * Es decir: la operación de dominio que existe para que no calcule le estaba pidiendo
 * calcular, y por eso se planteó tirar el contorno y volver a apilar cajas, que da
 * peor pieza. El modelo sí sabe una coordenada con total seguridad —la que acaba de
 * escribir en el contorno—, y con `punto` es la que da.
 */
class TaladroEnPerfilTest {

    private fun aplicar(plan: String): Editor {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(plan.trimIndent())
        val interpretado = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        val resultado = editor.aplicarPlan(interpretado, null)
        assertTrue(resultado.exito, "no se aplicó: ${resultado.resumen}")
        assertTrue(resultado.omitidas.isEmpty(), "se cayeron operaciones: ${resultado.omitidas}")
        return editor
    }

    /** Escuadra en L de 60×60 con ala de 15, como la del dibujo de la prueba. */
    private val escuadra = """
        {"resumen":"Escuadra","reemplazar":true,"operaciones":[
          {"op":"crear","tipo":"EXTRUSION","alias":"esc","nombre":"Escuadra",
           "parametros":{"altura":6}},
          {"op":"perfil","objetivo":"esc","forma":"LIBRE",
           "puntos":[[0,0],[60,0],[60,15],[15,15],[15,60],[0,60]]},
    """.trimIndent()

    @Test
    fun `un punto del contorno cae donde dice el contorno`() {
        // El agujero va en [30, 7.5]: centrado a lo alto del ala horizontal, a 30 de
        // su arranque. El contorno ocupa 0..60 en las dos direcciones, así que su
        // centro está en (30, 30): el desplazamiento equivalente sería (0, −22.5), y
        // ese −22.5 es justo el número que el modelo no puede deducir.
        val editor = aplicar(
            escuadra + """
              {"op":"taladro","objetivo":"esc","diametro":5,"punto":[30,7.5]}
            ]}
            """.trimIndent()
        )

        val broca = editor.filas().firstOrNull { it.nombre.startsWith("Taladro") }
        assertNotNull(broca, "no se creó ninguna broca")

        // El contorno vive en XZ y no está centrado, así que sus coordenadas SON las
        // locales de la extrusión: el agujero tiene que acabar exactamente en (30, 7.5).
        // El desplazamiento equivalente sería (0, −22.5), y ese −22.5 es justo el
        // número que el modelo no puede deducir.
        val t = editor.transformDe(broca.id)
        assertTrue(abs(t[0] - 30f) < 0.05f, "X del taladro ${t[0]}, debería ser 30")
        assertTrue(abs(t[2] - 7.5f) < 0.05f, "Z del taladro ${t[2]}, debería ser 7.5")
    }

    @Test
    fun `el segundo agujero tambien encuentra el contorno`() {
        // Al primer taladro la extrusión queda envuelta en una DIFERENCIA, así que el
        // segundo ya no apunta a la extrusión sino al envoltorio. Si no se buscara el
        // contorno hacia abajo, el primero funcionaría y el segundo fallaría: la peor
        // forma posible de fallar, porque parece que va bien.
        val editor = aplicar(
            escuadra + """
              {"op":"taladro","objetivo":"esc","diametro":5,"punto":[30,7.5]},
              {"op":"taladro","objetivo":"seleccion","diametro":5,"punto":[48,7.5]}
            ]}
            """.trimIndent()
        )

        val brocas = editor.filas().filter { it.nombre.startsWith("Taladro") }
        assertTrue(brocas.size == 2, "se esperaban dos brocas y hay ${brocas.size}")

        val x = brocas.map { editor.transformDe(it.id)[0] }.sorted()
        assertTrue(abs(x[0] - 30f) < 0.05f, "primer agujero en X=${x[0]}, debería ser 30")
        assertTrue(abs(x[1] - 48f) < 0.05f, "segundo agujero en X=${x[1]}, debería ser 48")
    }

    @Test
    fun `punto y desplazamiento describen el mismo agujero`() {
        // Las dos formas tienen que aterrizar en el mismo sitio. Es lo que convierte
        // «punto» en azúcar y no en un segundo sistema de coordenadas: si divergieran,
        // habría dos verdades sobre dónde está el agujero y algún día no coincidirían.
        // Y el comportamiento de siempre queda blindado de paso.
        val conPunto = aplicar(
            escuadra + """
              {"op":"taladro","objetivo":"esc","diametro":5,"punto":[30,7.5]}
            ]}
            """.trimIndent()
        )
        val conDesplazamiento = aplicar(
            escuadra + """
              {"op":"taladro","objetivo":"esc","diametro":5,"desplazamiento":[0,-22.5]}
            ]}
            """.trimIndent()
        )

        fun posicionDeLaBroca(editor: Editor): List<Float> {
            val broca = assertNotNull(editor.filas().firstOrNull { it.nombre.startsWith("Taladro") })
            return editor.transformDe(broca.id).take(3)
        }

        val a = posicionDeLaBroca(conPunto)
        val b = posicionDeLaBroca(conDesplazamiento)
        for (i in 0..2) {
            assertTrue(abs(a[i] - b[i]) < 0.01f, "los dos caminos no coinciden: $a frente a $b")
        }
    }

    @Test
    fun `pedir un punto de contorno donde no hay contorno se explica`() {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"resumen":"Caja","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"c","nombre":"Caja",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"taladro","objetivo":"c","diametro":5,"punto":[10,10]}
            ]}
            """.trimIndent()
        )
        val plan = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        val resultado = editor.aplicarPlan(plan, null)

        assertTrue(resultado.omitidas.isNotEmpty(), "una caja no tiene contorno; debería quejarse")
        assertTrue(
            resultado.omitidas.any { "contorno" in it },
            "el motivo debería nombrar el contorno: ${resultado.omitidas}",
        )
    }
}
