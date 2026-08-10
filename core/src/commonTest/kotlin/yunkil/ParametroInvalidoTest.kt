package yunkil

import yunkil.doc.Editor
import yunkil.doc.TipoPieza
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Un parámetro inválido no puede dejar el documento en un estado del que no se sale.
 *
 * El caso que abrió esto: el ángulo total de una repetición circular se ofrece en la
 * interfaz de −360° a 360°, y `RepeticionCircular` rechaza el cero en su constructor.
 * Llevar el deslizador hasta ahí no fallaba una edición: guardaba el documento roto y
 * **después** reventaba al compilar. A partir de ese momento fallaba cada regeneración
 * posterior, porque todas volvían a compilar el mismo árbol imposible, y el viewport se
 * quedaba muerto. De ese estado no se salía deshaciendo, porque deshacer también
 * regenera.
 *
 * Se arregla en los dos sitios donde había que arreglarlo:
 *
 *  - `aplicar` valida que el documento nuevo se puede construir **antes** de sustituir
 *    al anterior, así que cualquier nodo que rechace algo en su constructor —hoy el
 *    ángulo, mañana otro— deja el documento intacto en vez de envenenado.
 *  - el parámetro declara su hueco prohibido en el cero, y el deslizador lo salta en
 *    lugar de atascarse, que es lo que pasaría si la validación fuera lo único.
 */
class ParametroInvalidoTest {

    private fun editorConRepeticionCircular(): Pair<Editor, String> {
        val editor = Editor()
        val raiz = editor.filas().first().id
        editor.anadir(TipoPieza.CILINDRO.name, raiz)
        val cilindro = editor.filas()[1].id
        assertTrue(
            editor.envolver(cilindro, TipoPieza.REPETICION_CIRCULAR.name),
            editor.ultimoError ?: "no envolvió",
        )
        return editor to editor.filas()[1].id
    }

    @Test
    fun `el angulo cero no revienta ni envenena el documento`() {
        val (editor, envoltorio) = editorConRepeticionCircular()
        val cotasAntes = assertNotNull(editor.cotasDelModelo())

        // Ni siquiera debe lanzar: el editor rechaza o corrige, pero no explota.
        editor.fijarParametro(envoltorio, "angulo", 0f)

        // Y lo importante: el modelo sigue vivo después.
        val cotasDespues = assertNotNull(
            editor.cotasDelModelo(),
            "el documento dejó de compilar tras poner el ángulo a cero",
        )
        assertTrue(cotasDespues.size.length() > 0f, "el modelo se quedó vacío: $cotasAntes")
    }

    @Test
    fun `el deslizador salta el cero en vez de pararse en el`() {
        val (editor, envoltorio) = editorConRepeticionCircular()

        for (pedido in listOf(0f, 0.2f, -0.3f, 0.9f)) {
            editor.fijarParametro(envoltorio, "angulo", pedido)
            val valor = assertNotNull(
                editor.parametrosDe(envoltorio).firstOrNull { it.clave == "angulo" },
            ).valor
            assertTrue(
                abs(valor) >= 1f - 1e-4f,
                "pidiendo $pedido el ángulo se quedó en $valor, dentro del hueco prohibido",
            )
            // Y se va al lado hacia el que iba, no siempre al positivo.
            if (pedido < 0f) assertTrue(valor < 0f, "un ángulo negativo saltó al lado contrario")
        }
    }

    @Test
    fun `el documento sigue siendo el de antes si el cambio no se puede construir`() {
        // La garantía general, más allá del ángulo: `aplicar` no deja nada a medias.
        val (editor, envoltorio) = editorConRepeticionCircular()
        editor.fijarParametro(envoltorio, "angulo", 180f)
        val antes = editor.parametrosDe(envoltorio).first { it.clave == "angulo" }.valor

        // Un valor imposible por la vía que sí puede colarse: fuera de rango se recorta,
        // así que se comprueba que el recorte deja siempre algo construible.
        editor.fijarParametro(envoltorio, "angulo", 100_000f)
        assertNotNull(editor.cotasDelModelo(), "un ángulo enorme dejó el documento sin compilar")

        editor.fijarParametro(envoltorio, "angulo", Float.NaN)
        assertNotNull(editor.cotasDelModelo(), "un NaN dejó el documento sin compilar")
        val despues = editor.parametrosDe(envoltorio).first { it.clave == "angulo" }.valor
        assertTrue(despues.isFinite(), "el ángulo se quedó en NaN: venía de $antes")
    }
}
