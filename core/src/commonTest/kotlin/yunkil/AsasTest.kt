package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.TipoPieza
import yunkil.doc.Asa
import yunkil.doc.asas
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Empujar y tirar de una cara.
 *
 * Es el gesto insignia de Shapr3D y de Plasticity, y en un B-rep es una operación
 * topológica. Aquí no hay caras que empujar, pero cada tipo de pieza **declara sus
 * asas**: qué parámetro gobierna cada cara. Igual que el inspector se construye desde
 * `TipoPieza.parametros`, empujar se resuelve desde `TipoPieza.asas`, y añadir una
 * primitiva no obliga a tocar la interfaz.
 *
 * La regla que hace que esto funcione y no parezca roto es la **compensación del
 * centro**: las primitivas están centradas en el origen, así que crecer 10 mm mueve las
 * dos caras 5. Sin compensar, empujar una cara mueve también la de enfrente, y es
 * exactamente el motivo por el que el «empujar cara» de una herramienta paramétrica mal
 * hecha se siente inservible.
 */
class AsasTest {

    private fun caja(anchura: Float = 40f, altura: Float = 20f, profundidad: Float = 30f): Pair<Editor, String> {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CAJA.name, null)
        val id = assertNotNull(editor.seleccionado)
        editor.fijarParametro(id, "anchura", anchura)
        editor.fijarParametro(id, "altura", altura)
        editor.fijarParametro(id, "profundidad", profundidad)
        editor.fijarParametro(id, "redondeo", 0f)
        return editor to id
    }

    /**
     * Empuja y comprueba que no hubo rechazo.
     *
     * El booleano de `Editor` significa «hay que recompilar el shader», no «salió bien»:
     * una operación correcta que solo mueve números devuelve `false`. El fallo se lee en
     * `ultimoError`, y confundir las dos cosas ya rompió una vez el aplicador de planes.
     */
    private fun empujar(editor: Editor, id: String, asa: Asa, milimetros: Float) {
        editor.empujar(id, asa.name, milimetros)
        assertNull(editor.ultimoError, "empujar ${asa.etiqueta} fue rechazado")
    }

    private fun valor(editor: Editor, id: String, clave: String): Float =
        assertNotNull(editor.parametrosDe(id).firstOrNull { it.clave == clave }, "no hay parámetro $clave").valor

    private fun posicion(editor: Editor, id: String): Triple<Float, Float, Float> {
        val t = editor.transformDe(id)
        return Triple(t[0], t[1], t[2])
    }

    @Test
    fun `empujar la cara mas X ensancha la pieza y deja quieta la de enfrente`() {
        val (editor, id) = caja()
        empujar(editor, id, Asa.DERECHA, 10f)

        assertEquals(50f, valor(editor, id, "anchura"))
        val (x, _, _) = posicion(editor, id)
        assertTrue(abs(x - 5f) < 1e-4f, "el centro tenía que irse a x=5, está en $x")
    }

    @Test
    fun `empujar la cara menos X crece hacia el otro lado`() {
        val (editor, id) = caja()
        empujar(editor, id, Asa.IZQUIERDA, 10f)

        assertEquals(50f, valor(editor, id, "anchura"))
        val (x, _, _) = posicion(editor, id)
        assertTrue(abs(x + 5f) < 1e-4f, "el centro tenía que irse a x=-5, está en $x")
    }

    @Test
    fun `tirar hacia dentro encoge la pieza sin mover la cara opuesta`() {
        val (editor, id) = caja()
        empujar(editor, id, Asa.DERECHA, -10f)

        assertEquals(30f, valor(editor, id, "anchura"))
        val (x, _, _) = posicion(editor, id)
        assertTrue(abs(x + 5f) < 1e-4f, "encoger por +X mueve el centro a x=-5, está en $x")
    }

    @Test
    fun `cada cara gobierna su propio parametro`() {
        val (editor, id) = caja()
        empujar(editor, id, Asa.ARRIBA, 6f)
        empujar(editor, id, Asa.DETRAS, 4f)

        assertEquals(40f, valor(editor, id, "anchura"))
        assertEquals(26f, valor(editor, id, "altura"))
        assertEquals(34f, valor(editor, id, "profundidad"))
        val (x, y, z) = posicion(editor, id)
        assertTrue(abs(x) < 1e-4f, "la anchura no se tocó, x debe seguir en 0")
        assertTrue(abs(y - 3f) < 1e-4f, "y=$y")
        assertTrue(abs(z + 2f) < 1e-4f, "z=$z")
    }

    @Test
    fun `al topar con el maximo el centro solo se mueve lo que la pieza crecio`() {
        // Este es el fallo que hay que impedir: recortar el parámetro y compensar el
        // centro con lo *pedido* en vez de con lo *conseguido* desplaza la pieza entera
        // sin que crezca, y la cara que se estaba sujetando se mueve.
        val (editor, id) = caja(anchura = 590f)
        empujar(editor, id, Asa.DERECHA, 100f)

        assertEquals(600f, valor(editor, id, "anchura"), "la anchura topa en su máximo")
        val (x, _, _) = posicion(editor, id)
        assertTrue(abs(x - 5f) < 1e-4f, "solo creció 10 mm, así que el centro va a x=5, está en $x")
    }

    @Test
    fun `un asa radial cambia el radio y no mueve el centro`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CILINDRO.name, null)
        val id = assertNotNull(editor.seleccionado)
        editor.fijarParametro(id, "radio", 8f)
        editor.fijarParametro(id, "altura", 30f)

        empujar(editor, id, Asa.CONTORNO, 4f)
        assertEquals(12f, valor(editor, id, "radio"))
        val (x, y, z) = posicion(editor, id)
        assertTrue(abs(x) + abs(y) + abs(z) < 1e-4f, "un radio crece a los dos lados: el centro no se mueve")
    }

    @Test
    fun `con la pieza girada el centro se compensa en el eje girado`() {
        val (editor, id) = caja()
        // Un cuarto de vuelta en Y lleva el eje X local sobre el −Z del mundo.
        editor.girarPieza(id, 0f, 90f, 0f, absoluto = true)
        empujar(editor, id, Asa.DERECHA, 10f)

        assertEquals(50f, valor(editor, id, "anchura"))
        val (x, y, z) = posicion(editor, id)
        assertTrue(abs(x) < 1e-3f, "girada, la compensación no va en x: x=$x")
        assertTrue(abs(y) < 1e-3f, "ni en y: y=$y")
        assertTrue(abs(abs(z) - 5f) < 1e-3f, "va en z: z=$z")
    }

    @Test
    fun `la escala de la pieza se descuenta del arrastre`() {
        // El arrastre llega en milímetros del mundo. Con la pieza al doble, 10 mm de
        // mundo son 5 de parámetro; no descontarlo haría que la pieza creciera el doble
        // de lo que se arrastró y la cara se despegaría del cursor.
        val (editor, id) = caja()
        editor.escalarPieza(id, 2f)
        empujar(editor, id, Asa.DERECHA, 10f)
        assertEquals(45f, valor(editor, id, "anchura"))
    }

    @Test
    fun `la escala de los padres tambien cuenta`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.UNION.name, null)
        val grupo = assertNotNull(editor.seleccionado)
        editor.escalarPieza(grupo, 2f)

        editor.anadir(TipoPieza.CAJA.name, grupo)
        val id = assertNotNull(editor.seleccionado)
        editor.fijarParametro(id, "anchura", 40f)
        empujar(editor, id, Asa.DERECHA, 10f)
        assertEquals(45f, valor(editor, id, "anchura"))
    }

    @Test
    fun `una pieza sin esa asa se rechaza sin tocar el documento`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.TORO.name, null)
        val id = assertNotNull(editor.seleccionado)
        val antes = valor(editor, id, "radioMayor")

        editor.empujar(id, Asa.ARRIBA.name, 10f)
        assertNotNull(editor.ultimoError, "un toro no tiene cara superior")
        assertEquals(antes, valor(editor, id, "radioMayor"))
    }

    @Test
    fun `una operacion no tiene asas`() {
        val editor = Editor(Documento.vacio())
        assertTrue(TipoPieza.UNION.asas.isEmpty(), "una unión no es una superficie que empujar")
        assertTrue(TipoPieza.DIFERENCIA.asas.isEmpty())
        editor.empujar(editor.raizId, Asa.DERECHA.name, 5f)
        assertNotNull(editor.ultimoError, "la raíz es una unión: no hay nada que empujar")
    }

    @Test
    fun `las asas declaradas apuntan a parametros que existen`() {
        // La misma disciplina que el inspector: si un tipo declara un asa sobre un
        // parámetro que no tiene, empujar fallaría en tiempo de ejecución y solo se
        // vería al arrastrar. Aquí salta al ejecutar las pruebas.
        for (tipo in TipoPieza.entries) {
            val claves = tipo.parametros.map { it.clave }.toSet()
            for ((asa, clave) in tipo.asas) {
                assertTrue(
                    clave in claves,
                    "$tipo declara ${asa.etiqueta} sobre «$clave», que no es uno de sus parámetros: $claves",
                )
            }
            assertTrue(
                !tipo.esOperacion || tipo.asas.isEmpty(),
                "$tipo es una operación y no debería tener asas",
            )
        }
    }

    @Test
    fun `empujar es un solo punto de deshacer`() {
        val (editor, id) = caja()
        empujar(editor, id, Asa.DERECHA, 10f)
        assertTrue(editor.puedeDeshacer)
        editor.deshacer()

        assertEquals(40f, valor(editor, id, "anchura"), "deshacer devuelve la medida")
        val (x, _, _) = posicion(editor, id)
        assertTrue(abs(x) < 1e-4f, "y también el centro: x=$x")
    }

    @Test
    fun `empujar cero no cambia nada`() {
        val (editor, id) = caja()
        empujar(editor, id, Asa.DERECHA, 0f)
        assertEquals(40f, valor(editor, id, "anchura"))
        val (x, y, z) = posicion(editor, id)
        assertTrue(abs(x) + abs(y) + abs(z) < 1e-6f, "un arrastre de cero no mueve el centro")
    }

    @Test
    fun `la normal del impacto elige el asa`() {
        val (editor, id) = caja()
        assertEquals(Asa.DERECHA.name, editor.asaParaNormal(id, 1f, 0f, 0f))
        assertEquals(Asa.ABAJO.name, editor.asaParaNormal(id, 0f, -1f, 0f))
        assertEquals(Asa.DELANTE.name, editor.asaParaNormal(id, 0f, 0f, 1f))
    }

    @Test
    fun `girada la pieza la normal del mundo sigue eligiendo su cara local`() {
        val (editor, id) = caja()
        editor.girarPieza(id, 0f, 90f, 0f, absoluto = true)
        // Con la pieza girada un cuarto de vuelta en Y, la cara que mira al −Z del mundo
        // es su «derecha» local. Elegir por la componente mayor del mundo daría DETRAS.
        assertEquals(Asa.DERECHA.name, editor.asaParaNormal(id, 0f, 0f, -1f))
    }

    @Test
    fun `la pared curva de un cilindro es su contorno`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CILINDRO.name, null)
        val id = assertNotNull(editor.seleccionado)
        // Una normal radial no se parece a ninguna cara plana del cilindro.
        assertEquals(Asa.CONTORNO.name, editor.asaParaNormal(id, 1f, 0f, 0f))
        assertEquals(Asa.ARRIBA.name, editor.asaParaNormal(id, 0f, 1f, 0f))
    }

    @Test
    fun `una pieza sin asas no devuelve ninguna`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.TORO.name, null)
        val id = assertNotNull(editor.seleccionado)
        assertNull(editor.asaParaNormal(id, 0f, 1f, 0f))
    }
}
