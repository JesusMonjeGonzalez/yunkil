package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.TipoPieza
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las acciones que cualquiera que venga de un CAD busca a los dos minutos.
 *
 * Ninguna es difícil y todas se apoyan en lo que ya hay. Están juntas aquí porque lo que
 * de verdad importa de este bloque es que la herramienta deje de sentirse como una demo:
 * aislar para ver qué estás tocando, sacar una pieza de un grupo, copiarla, y apoyar una
 * cara en el plato antes de imprimir. Sin esto hay que reconstruir a mano lo que en
 * Shapr3D es un clic derecho.
 */
class InteraccionTest {

    private fun montaje(): Triple<Editor, String, String> {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.UNION.name, null)
        val grupo = assertNotNull(editor.seleccionado)
        editor.anadir(TipoPieza.CAJA.name, grupo)
        val caja = assertNotNull(editor.seleccionado)
        editor.anadir(TipoPieza.ESFERA.name, grupo)
        val esfera = assertNotNull(editor.seleccionado)
        return Triple(editor, caja, esfera)
    }

    // ------------------------------------------------------------------ aislar

    @Test
    fun `aislar deja visible solo la pieza y sus ancestros`() {
        val (editor, caja, esfera) = montaje()
        editor.aislar(caja)
        assertNull(editor.ultimoError)

        assertTrue(editor.esVisible(caja), "la pieza aislada se ve")
        assertTrue(!editor.esVisible(esfera), "sus hermanas no")
        assertTrue(editor.esVisible(editor.raizId), "y la raíz sigue visible o no se vería nada")
    }

    @Test
    fun `dejar de aislar devuelve todo a la vista`() {
        val (editor, caja, esfera) = montaje()
        editor.aislar(caja)
        editor.mostrarTodo()

        assertTrue(editor.esVisible(caja))
        assertTrue(editor.esVisible(esfera), "mostrar todo recupera lo que aislar escondió")
    }

    @Test
    fun `aislar es un solo paso de deshacer`() {
        val (editor, caja, esfera) = montaje()
        editor.aislar(caja)
        editor.deshacer()
        assertTrue(editor.esVisible(esfera), "deshacer devuelve la visibilidad de golpe")
    }

    // ----------------------------------------------------------------- extraer

    @Test
    fun `extraer saca la pieza del grupo y la deja con el abuelo`() {
        val (editor, caja, _) = montaje()
        val grupo = assertNotNull(editor.padreDe(caja))

        editor.extraer(caja)
        assertNull(editor.ultimoError)
        assertEquals(editor.raizId, editor.padreDe(caja), "la caja pasa a ser hija de la raíz")
        assertTrue(editor.existe(grupo), "el grupo sigue existiendo con lo que le quedaba")
    }

    @Test
    fun `extraer conserva la posicion en el mundo`() {
        // Es lo que hace que la operación sirva: si al sacarla de un grupo desplazado la
        // pieza salta a otro sitio, hay que recolocarla a mano y no se ha ganado nada.
        val (editor, caja, _) = montaje()
        val grupo = assertNotNull(editor.padreDe(caja))
        editor.mover(grupo, 0f, 0f, 50f, absoluto = true)
        editor.mover(caja, 10f, 0f, 0f, absoluto = true)

        val antes = editor.centroEnElMundo(caja)
        editor.extraer(caja)
        val despues = editor.centroEnElMundo(caja)

        assertNotNull(antes)
        assertNotNull(despues)
        assertTrue(abs(antes[0] - despues[0]) < 1e-3f, "x: ${antes[0]} -> ${despues[0]}")
        assertTrue(abs(antes[1] - despues[1]) < 1e-3f, "y: ${antes[1]} -> ${despues[1]}")
        assertTrue(abs(antes[2] - despues[2]) < 1e-3f, "z: ${antes[2]} -> ${despues[2]}")
    }

    @Test
    fun `la raiz no se puede extraer`() {
        val editor = Editor(Documento.vacio())
        editor.extraer(editor.raizId)
        assertNotNull(editor.ultimoError)
    }

    @Test
    fun `una pieza que ya cuelga de la raiz no se extrae`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CAJA.name, null)
        val caja = assertNotNull(editor.seleccionado)
        editor.extraer(caja)
        assertNotNull(editor.ultimoError, "no hay abuelo al que subirla")
    }

    // --------------------------------------------------------- copiar y pegar

    @Test
    fun `copiar y pegar duplica la pieza con sus hijos`() {
        val (editor, caja, _) = montaje()
        editor.fijarParametro(caja, "anchura", 77f)

        val portapapeles = assertNotNull(editor.copiar(caja), "copiar tenía que devolver algo")
        editor.pegar(portapapeles, editor.raizId)
        assertNull(editor.ultimoError)

        val pegada = assertNotNull(editor.seleccionado)
        assertTrue(pegada != caja, "la copia es otra pieza")
        assertEquals(
            77f,
            assertNotNull(editor.parametrosDe(pegada).firstOrNull { it.clave == "anchura" }).valor,
            "y trae sus medidas",
        )
        assertEquals(editor.raizId, editor.padreDe(pegada), "pegada donde se pidió")
    }

    @Test
    fun `pegar un arbol entero renombra todos los identificadores`() {
        // Si la copia conservara los ids, el documento tendría dos piezas con el mismo
        // identificador y cualquier operación tocaría la equivocada.
        val (editor, _, _) = montaje()
        val grupo = assertNotNull(editor.padreDe(assertNotNull(editor.seleccionado)))
        val portapapeles = assertNotNull(editor.copiar(grupo))
        editor.pegar(portapapeles, editor.raizId)

        val ids = editor.filas().map { it.id }
        assertEquals(ids.size, ids.distinct().size, "hay identificadores repetidos: $ids")
    }

    @Test
    fun `pegar basura se rechaza sin tocar el documento`() {
        val (editor, _, _) = montaje()
        val antes = editor.filas().size
        editor.pegar("{esto no es una pieza", editor.raizId)
        assertNotNull(editor.ultimoError)
        assertEquals(antes, editor.filas().size)
    }

    // -------------------------------------------------------- apoyar en el plato

    @Test
    fun `apoyar una cara en el plato la deja mirando abajo`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CAJA.name, null)
        val caja = assertNotNull(editor.seleccionado)

        // Se señala la cara que mira hacia +X y se pide que se apoye.
        editor.apoyarEnElPlato(caja, 1f, 0f, 0f)
        assertNull(editor.ultimoError)

        // Después del giro, esa misma cara tiene que mirar hacia abajo.
        val n = assertNotNull(editor.direccionEnElMundo(caja, 1f, 0f, 0f))
        assertTrue(n[1] < -0.99f, "la cara elegida tenía que apuntar a −Y, apunta a (${n[0]}, ${n[1]}, ${n[2]})")
    }

    @Test
    fun `apoyar la cara que ya mira abajo no gira nada`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CAJA.name, null)
        val caja = assertNotNull(editor.seleccionado)

        editor.apoyarEnElPlato(caja, 0f, -1f, 0f)
        val n = assertNotNull(editor.direccionEnElMundo(caja, 0f, -1f, 0f))
        assertTrue(n[1] < -0.99f, "sigue mirando abajo")
    }

    @Test
    fun `apoyar la cara de arriba da media vuelta`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CAJA.name, null)
        val caja = assertNotNull(editor.seleccionado)
        editor.fijarParametro(caja, "altura", 10f)
        editor.fijarParametro(caja, "anchura", 40f)

        editor.apoyarEnElPlato(caja, 0f, 1f, 0f)
        val n = assertNotNull(editor.direccionEnElMundo(caja, 0f, 1f, 0f))
        assertTrue(n[1] < -0.99f, "una vuelta de 180° la pone mirando abajo: (${n[0]}, ${n[1]}, ${n[2]})")
    }

    @Test
    fun `apoyar tambien deja la pieza sobre el plato y no flotando`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CAJA.name, null)
        val caja = assertNotNull(editor.seleccionado)
        editor.mover(caja, 0f, 60f, 0f, absoluto = true)

        editor.apoyarEnElPlato(caja, 1f, 0f, 0f)
        assertTrue(
            abs(editor.cotaMinima[1]) < 0.01f,
            "apoyar tiene que dejar la base en y=0, está en ${editor.cotaMinima[1]}",
        )
    }

    @Test
    fun `una normal degenerada se rechaza`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CAJA.name, null)
        val caja = assertNotNull(editor.seleccionado)
        editor.apoyarEnElPlato(caja, 0f, 0f, 0f)
        assertNotNull(editor.ultimoError)
    }
}
