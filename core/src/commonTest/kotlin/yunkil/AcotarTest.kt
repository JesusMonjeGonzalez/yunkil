package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `acotar`: llevar una cota concreta a su medida real conservando las proporciones.
 *
 * Es la operación que cierra el hueco entre lo que un modelo de lenguaje sabe hacer
 * y lo que hace falta para imprimir. Las proporciones las acierta; los milímetros no,
 * y a partir de una imagen no puede acertarlos porque en una foto no hay escala. Con
 * esto basta una medida conocida para fijar el resto.
 */
class AcotarTest {

    private fun editorConPlan(plan: String): Editor {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(plan.trimIndent())
        val interpretado = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        val resultado = editor.aplicarPlan(interpretado, null)
        assertTrue(resultado.exito, "no se aplicó: ${resultado.resumen}")
        assertTrue(resultado.omitidas.isEmpty(), "se cayeron operaciones: ${resultado.omitidas}")
        return editor
    }

    /** Tamaño del modelo entero en el mundo, tal y como lo ve la interfaz. */
    private fun tamanoDelModelo(editor: Editor): Triple<Float, Float, Float> {
        val lo = editor.cotaMinima
        val hi = editor.cotaMaxima
        return Triple(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2])
    }

    @Test
    fun `acotar el modelo conserva las proporciones`() {
        // Una caja de 40x20x30 llevada a 80 de ancho tiene que salir 80x40x60: si el
        // escalado no fuera uniforme, la pieza cuadraría en X y mentiría en el resto.
        val editor = editorConPlan(
            """
            {"resumen":"Bloque","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"bloque","nombre":"Bloque",
               "parametros":{"anchura":40,"altura":20,"profundidad":30,"redondeo":0}},
              {"op":"acotar","objetivo":"modelo","eje":"X","medida":80}
            ]}
            """
        )

        val c = tamanoDelModelo(editor)
        assertTrue(abs(c.first - 80f) < 0.05f, "ancho ${c.first}, se pidió 80")
        assertTrue(abs(c.second - 40f) < 0.05f, "alto ${c.second}, la proporción pide 40")
        assertTrue(abs(c.third - 60f) < 0.05f, "fondo ${c.third}, la proporción pide 60")
    }

    @Test
    fun `acotar funciona sobre un conjunto ya montado`() {
        // El caso que importa de verdad: modelar con proporciones cómodas y cerrar
        // con una sola medida al final, que es lo que se le pide al modelo.
        val editor = editorConPlan(
            """
            {"resumen":"Torre de dos cuerpos","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"base","nombre":"Base",
               "parametros":{"anchura":20,"altura":10,"profundidad":20,"redondeo":0}},
              {"op":"crear","tipo":"CAJA","alias":"torre","nombre":"Torre",
               "parametros":{"anchura":10,"altura":30,"profundidad":10,"redondeo":0}},
              {"op":"colocar","objetivo":"torre","referencia":"base","cara":"arriba","centrar":true},
              {"op":"acotar","objetivo":"modelo","eje":"Y","medida":120}
            ]}
            """
        )

        val c = tamanoDelModelo(editor)
        assertTrue(abs(c.second - 120f) < 0.1f, "alto ${c.second}, se pidió 120")
        // 40 de alto original con 20 de ancho: al triplicar, el ancho va a 60.
        assertTrue(abs(c.first - 60f) < 0.1f, "ancho ${c.first}, la proporción pide 60")
    }

    @Test
    fun `el interprete acepta como acotar lo que escriben los modelos de verdad`() {
        // Ningún modelo emite «acotar» a la primera: escribe dimension, set_size o
        // scale_to, y con las claves en inglés. Rechazarlo por eso sería tirar un
        // plan correcto por la forma de escribirlo.
        val editor = editorConPlan(
            """
            {"resumen":"Bloque","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"BOX","alias":"bloque","nombre":"Bloque",
               "parametros":{"anchura":40,"altura":20,"profundidad":30,"redondeo":0}},
              {"action":"set_size","target":"modelo","axis":"x","size":80}
            ]}
            """
        )

        val c = tamanoDelModelo(editor)
        assertTrue(abs(c.first - 80f) < 0.05f, "ancho ${c.first}, se pidió 80")
    }

    @Test
    fun `una medida imposible se rechaza en vez de deformar la pieza`() {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"resumen":"Bloque","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"bloque","nombre":"Bloque",
               "parametros":{"anchura":40,"altura":20,"profundidad":30,"redondeo":0}},
              {"op":"acotar","objetivo":"modelo","eje":"X","medida":0}
            ]}
            """.trimIndent()
        )
        val interpretado = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        val resultado = editor.aplicarPlan(interpretado, null)

        assertTrue(resultado.omitidas.isNotEmpty(), "una medida de 0 debería caerse, no aplicarse")

        // Y lo importante: la caja sigue midiendo lo que medía.
        val c = tamanoDelModelo(editor)
        assertTrue(abs(c.first - 40f) < 0.05f, "la pieza se deformó igualmente: ancho ${c.first}")
    }
}
