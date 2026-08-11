package yunkil

import yunkil.doc.Editor
import yunkil.doc.TipoPieza
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mover y girar **desde el mundo**, que es lo único que sabe decir un gizmo.
 *
 * El gizmo vive en la pantalla: dibuja tres flechas que apuntan a X, Y y Z del mundo y lo
 * que entrega es «tantos milímetros a lo largo de esta dirección del mundo». La pieza, en
 * cambio, guarda su traslación **en el marco de su padre**, y con un grupo girado los dos
 * marcos no coinciden: arrastrar la flecha X escribiendo directamente en `posX` movería la
 * pieza en su Z, y el usuario vería la pieza irse por donde no ha tirado.
 *
 * La conversión es geometría, así que vive en el núcleo y se mide aquí. La aplicación solo
 * pone píxeles y pasa el gesto.
 */
class GizmoEnElMundoTest {

    private fun conUnaCaja(): Pair<Editor, String> {
        val editor = Editor()
        val raiz = editor.filas().first().id
        editor.anadir(TipoPieza.CAJA.name, raiz)
        return editor to editor.filas().first { it.tipo == "CAJA" }.id
    }

    /** Una caja dentro de un grupo girado un cuarto de vuelta alrededor de Y. */
    private fun enUnGrupoGirado(): Pair<Editor, String> {
        val editor = Editor()
        val raiz = editor.filas().first().id
        editor.anadir(TipoPieza.UNION.name, raiz)
        val grupo = editor.filas().first { it.tipo == "UNION" && it.id != raiz }.id
        editor.anadir(TipoPieza.CAJA.name, grupo)
        val caja = editor.filas().first { it.tipo == "CAJA" }.id
        editor.fijarTransform(grupo, 0f, 0f, 0f, 0f, 90f, 0f, 1f)
        return editor to caja
    }

    private fun centro(editor: Editor, id: String): List<Float> =
        assertNotNull(editor.centroEnElMundo(id), "la pieza $id no tiene centro")

    @Test
    fun `mover una pieza suelta a lo largo de X la mueve esos milimetros`() {
        val (editor, caja) = conUnaCaja()
        val antes = centro(editor, caja)

        editor.moverEnElMundo(caja, 1f, 0f, 0f, 12f)

        val despues = centro(editor, caja)
        assertEquals(12f, despues[0] - antes[0], 1e-3f)
        assertEquals(0f, despues[1] - antes[1], 1e-3f)
        assertEquals(0f, despues[2] - antes[2], 1e-3f)
    }

    @Test
    fun `la direccion se normaliza, asi que el arrastre manda la distancia`() {
        val (editor, caja) = conUnaCaja()
        val antes = centro(editor, caja)

        // Una dirección sin normalizar es lo que va a llegar del gizmo el día que sus ejes
        // dejen de ser unitarios. Los milímetros los pone el arrastre, no el vector.
        editor.moverEnElMundo(caja, 0f, 5f, 0f, 7f)

        assertEquals(7f, centro(editor, caja)[1] - antes[1], 1e-3f)
    }

    @Test
    fun `dentro de un grupo girado la pieza se mueve por el mundo y no por su eje local`() {
        val (editor, caja) = enUnGrupoGirado()
        val antes = centro(editor, caja)

        editor.moverEnElMundo(caja, 1f, 0f, 0f, 10f)

        val despues = centro(editor, caja)
        assertEquals(10f, despues[0] - antes[0], 1e-3f, "la flecha X del gizmo mueve en X del mundo")
        assertEquals(0f, despues[2] - antes[2], 1e-3f, "y no en Z, que es donde cae su X local")
    }

    @Test
    fun `girar deja el centro donde estaba`() {
        // El giro es alrededor del centro de la pieza, no de su origen: un gizmo que gira
        // alrededor del origen del documento manda la pieza de viaje en cuanto está
        // descentrada, y es de las cosas que se notan a la primera.
        val (editor, caja) = conUnaCaja()
        editor.moverEnElMundo(caja, 1f, 0f, 0f, 40f)
        val antes = centro(editor, caja)

        editor.girarEnElMundo(caja, 0f, 1f, 0f, 90f)

        val despues = centro(editor, caja)
        for (i in 0..2) {
            assertTrue(
                abs(despues[i] - antes[i]) < 1e-2f,
                "el centro se ha ido: $antes → $despues",
            )
        }
    }

    @Test
    fun `girar noventa grados alrededor de Y lleva el eje X de la pieza a menos Z`() {
        val (editor, caja) = conUnaCaja()

        editor.girarEnElMundo(caja, 0f, 1f, 0f, 90f)

        // Regla de la mano derecha: con Y hacia arriba, +90° lleva +X a −Z.
        val x = assertNotNull(editor.direccionEnElMundo(caja, 1f, 0f, 0f))
        assertEquals(0f, x[0], 1e-3f)
        assertEquals(-1f, x[2], 1e-3f)
    }

    @Test
    fun `girar dentro de un grupo girado gira alrededor del eje del mundo`() {
        val (editor, caja) = enUnGrupoGirado()

        editor.girarEnElMundo(caja, 1f, 0f, 0f, 90f)

        // Alrededor de X del mundo, +90° lleva +Y a +Z. Que el padre esté girado no cambia
        // el eje: el gizmo dibujó una flecha en X del mundo y eso es lo que se ha pedido.
        val y = assertNotNull(editor.direccionEnElMundo(caja, 0f, 1f, 0f))
        assertEquals(0f, y[1], 1e-3f)
        assertEquals(1f, y[2], 1e-3f)
    }

    @Test
    fun `escalar deja el centro donde estaba y multiplica la caja`() {
        // Mismo motivo que el giro: escalar respecto del origen local mandaría de viaje a
        // cualquier pieza descentrada, y quien arrastra el asa espera que crezca donde está.
        val (editor, caja) = conUnaCaja()
        editor.moverEnElMundo(caja, 1f, 0f, 0f, 40f)
        val antes = centro(editor, caja)
        val ancho = editor.cotaMaxima[0] - editor.cotaMinima[0]

        editor.escalarEnElMundo(caja, 2f)

        val despues = centro(editor, caja)
        for (i in 0..2) {
            assertTrue(abs(despues[i] - antes[i]) < 1e-2f, "el centro se ha ido: $antes → $despues")
        }
        assertEquals(ancho * 2f, editor.cotaMaxima[0] - editor.cotaMinima[0], 1e-2f)
    }

    @Test
    fun `escalar encadena factores, que es como llega un arrastre`() {
        val (editor, caja) = conUnaCaja()
        val ancho = editor.cotaMaxima[0] - editor.cotaMinima[0]

        repeat(10) { editor.escalarEnElMundo(caja, 1.1f) }

        // 1,1 elevado a diez son 2,5937…
        assertEquals(ancho * 2.5937f, editor.cotaMaxima[0] - editor.cotaMinima[0], 0.05f)
    }

    @Test
    fun `un factor de escala imposible se rechaza`() {
        val (editor, caja) = conUnaCaja()
        val ancho = editor.cotaMaxima[0] - editor.cotaMinima[0]

        editor.escalarEnElMundo(caja, 0f)
        editor.escalarEnElMundo(caja, -2f)

        assertEquals(ancho, editor.cotaMaxima[0] - editor.cotaMinima[0], 1e-3f)
        assertNotNull(editor.ultimoError)
    }

    @Test
    fun `un eje sin direccion se rechaza en vez de dejar la pieza en un sitio raro`() {
        val (editor, caja) = conUnaCaja()
        val antes = centro(editor, caja)

        editor.moverEnElMundo(caja, 0f, 0f, 0f, 10f)
        editor.girarEnElMundo(caja, 0f, 0f, 0f, 45f)

        assertEquals(antes, centro(editor, caja))
        assertNotNull(editor.ultimoError)
    }
}
