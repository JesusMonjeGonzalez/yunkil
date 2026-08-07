package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.TipoPieza
import yunkil.kernel.AcuerdoLocal
import yunkil.kernel.Caja
import yunkil.kernel.Cilindro
import yunkil.kernel.Esfera
import yunkil.kernel.ModoDeAcuerdo
import yunkil.kernel.SdfNode
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vec3
import yunkil.kernel.empaquetarUniforms
import yunkil.msl.MslGenerator
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Filete y chaflán sobre la arista que se elija.
 *
 * El motor de filetes de Parasolid es el foso de Plasticity y son años de casos límite.
 * Aquí no se compite con eso: se aprovecha que en un campo el acuerdo **ya** es un
 * parámetro, y lo único que faltaba era poder limitarlo a un sitio. `AcuerdoLocal`
 * multiplica la anchura de la mezcla por una caída centrada en el punto que se pinchó:
 * dentro de esa esfera hay filete, fuera la booleana sigue siendo exacta.
 *
 * La ventaja sobre un B-rep es que **no hace falta topología**: la arista se elige
 * apuntando, y el punto de impacto del picking es el centro de la influencia.
 *
 * Y la desventaja, dicha aquí para que no la descubra un usuario: es un filete «de
 * bola». Si la arista se curva dentro de la esfera de influencia, el radio no sale
 * constante a lo largo de ella. A 0,4 mm de boquilla eso no se ve; en una superficie de
 * producto sí.
 */
class AcuerdoLocalTest {

    private val caja = Caja(Vec3(10f, 10f, 10f))
    private val otra = Transformado(Caja(Vec3(10f, 10f, 10f)), Transform(translation = Vec3(20f, 0f, 0f)))

    private fun acuerdo(
        modo: ModoDeAcuerdo = ModoDeAcuerdo.UNION,
        centro: Vec3 = Vec3(10f, 10f, 0f),
        radio: Float = 6f,
        fusion: Float = 4f,
    ) = AcuerdoLocal(caja, otra, modo, centro, radio, fusion)

    @Test
    fun `lejos del punto elegido la booleana sigue siendo exacta`() {
        val nodo = acuerdo()
        val exacto = Union(caja, otra, 0f)
        // Un punto en la esquina de enfrente, a más de un radio de la influencia.
        for (p in listOf(Vec3(-10f, -10f, 0f), Vec3(0f, 0f, 12f), Vec3(30f, -10f, 0f))) {
            assertTrue(
                abs(nodo.evaluar(p) - exacto.evaluar(p)) < 1e-5f,
                "en $p el acuerdo local tenía que valer lo mismo que la unión exacta",
            )
        }
    }

    @Test
    fun `en el punto elegido hay filete`() {
        val nodo = acuerdo()
        val exacto = Union(caja, otra, 0f)
        val p = Vec3(10f, 10f, 0f)
        val diferencia = abs(nodo.evaluar(p) - exacto.evaluar(p))
        assertTrue(diferencia > 0.1f, "en el punto pinchado tenía que redondear, y difiere solo $diferencia")
        // El mínimo suave polinómico no puede apartarse más de k/4 del mínimo exacto.
        assertTrue(diferencia <= 4f / 4f + 1e-4f, "se apartó $diferencia, más de k/4")
    }

    @Test
    fun `sin fusion es la booleana exacta en todas partes`() {
        val nodo = acuerdo(fusion = 0f)
        val exacto = Union(caja, otra, 0f)
        val azar = Random(7)
        repeat(2_000) {
            val p = Vec3(azar.nextFloat() * 60 - 20, azar.nextFloat() * 60 - 20, azar.nextFloat() * 60 - 20)
            assertTrue(abs(nodo.evaluar(p) - exacto.evaluar(p)) < 1e-5f, "difiere en $p")
        }
    }

    @Test
    fun `con radio cero no hay influencia`() {
        // Radio cero es «ningún sitio», no «todas partes»: dividir por él sin cuidado
        // daría infinito y el campo entero saldría a NaN.
        val nodo = acuerdo(radio = 0f)
        val exacto = Union(caja, otra, 0f)
        for (p in listOf(Vec3(10f, 10f, 0f), Vec3(0f, 0f, 0f), Vec3(9.9f, 10f, 0f))) {
            val v = nodo.evaluar(p)
            assertTrue(v.isFinite(), "el campo no puede salir infinito en $p")
            assertTrue(abs(v - exacto.evaluar(p)) < 1e-5f, "sin radio no hay filete, y en $p difiere")
        }
    }

    @Test
    fun `los tres modos hacen lo que dicen`() {
        val esfera = Esfera(10f)
        val dentro = Transformado(Esfera(6f), Transform(translation = Vec3(8f, 0f, 0f)))
        val centro = Vec3(8f, 0f, 0f)

        val union = AcuerdoLocal(esfera, dentro, ModoDeAcuerdo.UNION, centro, 5f, 2f)
        val resta = AcuerdoLocal(esfera, dentro, ModoDeAcuerdo.DIFERENCIA, centro, 5f, 2f)
        val comun = AcuerdoLocal(esfera, dentro, ModoDeAcuerdo.INTERSECCION, centro, 5f, 2f)

        // Un punto que solo está dentro de la esfera pequeña.
        val soloEnLaPequena = Vec3(13f, 0f, 0f)
        assertTrue(union.evaluar(soloEnLaPequena) < 0f, "la unión conserva el material de la pequeña")
        assertTrue(resta.evaluar(soloEnLaPequena) > 0f, "la diferencia lo quita")
        assertTrue(comun.evaluar(soloEnLaPequena) > 0f, "la intersección no lo tiene: no es común")

        // Un punto dentro de las dos.
        val enLasDos = Vec3(6f, 0f, 0f)
        assertTrue(union.evaluar(enLasDos) < 0f)
        assertTrue(resta.evaluar(enLasDos) > 0f, "restar quita lo que la pequeña ocupa")
        assertTrue(comun.evaluar(enLasDos) < 0f, "lo común sí está")
    }

    @Test
    fun `las cotas contienen el campo`() {
        val nodo = acuerdo()
        val c = nodo.cotas()
        val azar = Random(11)
        repeat(3_000) {
            val p = Vec3(azar.nextFloat() * 80 - 30, azar.nextFloat() * 80 - 30, azar.nextFloat() * 80 - 30)
            if (nodo.evaluar(p) < 0f) {
                assertTrue(
                    p.x >= c.min.x - 1e-3f && p.x <= c.max.x + 1e-3f &&
                        p.y >= c.min.y - 1e-3f && p.y <= c.max.y + 1e-3f &&
                        p.z >= c.min.z - 1e-3f && p.z <= c.max.z + 1e-3f,
                    "hay material en $p y las cotas $c no lo cubren",
                )
            }
        }
    }

    @Test
    fun `el gradiente se queda dentro del limite declarado`() {
        // Una mezcla cuya anchura cambia con la posición **no es exactamente
        // 1-Lipschitz**: el término de la caída añade gradiente. En vez de esconderlo se
        // mide y se acota, que es la misma disciplina que la desviación del perfil.
        //
        // El límite es 1,15 con `fusion <= radio`. Por encima de 1 el trazado por esferas
        // podría pasarse de largo, y por eso el generador publica un paso seguro que el
        // renderizador aplica.
        val nodo = acuerdo(radio = 6f, fusion = 6f)
        val azar = Random(13)
        var peor = 0f
        repeat(20_000) {
            val p = Vec3(azar.nextFloat() * 20 + 2f, azar.nextFloat() * 20 + 2f, azar.nextFloat() * 12 - 6f)
            val e = 0.01f
            val dx = nodo.evaluar(Vec3(p.x + e, p.y, p.z)) - nodo.evaluar(Vec3(p.x - e, p.y, p.z))
            val dy = nodo.evaluar(Vec3(p.x, p.y + e, p.z)) - nodo.evaluar(Vec3(p.x, p.y - e, p.z))
            val dz = nodo.evaluar(Vec3(p.x, p.y, p.z + e)) - nodo.evaluar(Vec3(p.x, p.y, p.z - e))
            peor = max(peor, Vec3(dx, dy, dz).length() / (2f * e))
        }
        assertTrue(peor <= AcuerdoLocal.LIPSCHITZ_MAXIMO, "el peor gradiente medido fue $peor")
    }

    @Test
    fun `el paso seguro del shader baja cuando hay acuerdos locales`() {
        val generador = MslGenerator()
        val sinAcuerdo = generador.generar(Union(caja, otra, 2f))
        val conAcuerdo = generador.generar(acuerdo())

        assertEquals(1f, sinAcuerdo.pasoSeguro, "sin acuerdos locales el paso es el entero")
        assertTrue(
            conAcuerdo.pasoSeguro < 1f && conAcuerdo.pasoSeguro > 0.5f,
            "con acuerdo local el paso baja pero no se hunde: ${conAcuerdo.pasoSeguro}",
        )
    }

    @Test
    fun `la huella distingue el modo pero no el sitio`() {
        val generador = MslGenerator()
        val union = generador.generar(acuerdo(modo = ModoDeAcuerdo.UNION))
        val resta = generador.generar(acuerdo(modo = ModoDeAcuerdo.DIFERENCIA))
        val movido = generador.generar(acuerdo(modo = ModoDeAcuerdo.UNION, centro = Vec3(-3f, 2f, 1f)))

        assertNotEquals(union.huellaTopologica, resta.huellaTopologica, "cambiar de modo cambia el shader")
        assertEquals(union.huellaTopologica, movido.huellaTopologica, "mover el filete solo reescribe uniforms")
    }

    @Test
    fun `los uniforms reservados son los que se empaquetan`() {
        val generador = MslGenerator()
        for (modo in ModoDeAcuerdo.entries) {
            val nodo: SdfNode = AcuerdoLocal(caja, Cilindro(4f, 30f), modo, Vec3(1f, 2f, 3f), 5f, 2f)
            val generado = generador.generar(nodo)
            assertEquals(
                generado.numeroDeUniforms,
                nodo.empaquetarUniforms().size,
                "el shader de $modo reserva un número de uniforms distinto del empaquetado",
            )
        }
    }

    // ---------------------------------------------------------------- documento

    @Test
    fun `una booleana sin radio de acuerdo compila a la booleana de siempre`() {
        // Se comprueba por la huella topológica, que es observable desde fuera y es
        // justo lo que decide qué shader se compila.
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CAJA.name, null)
        editor.anadir(TipoPieza.CAJA.name, null)
        assertTrue("aU" !in editor.huellaTopologica, "sin filete no debe aparecer el nodo nuevo")
        assertTrue("u" in editor.huellaTopologica, "y sí la unión de siempre")
    }

    @Test
    fun `filetear pone el acuerdo en el booleano que junta las piezas`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CAJA.name, null)
        val primera = assertNotNull(editor.seleccionado)
        editor.anadir(TipoPieza.CAJA.name, null)
        editor.mover(assertNotNull(editor.seleccionado), 20f, 0f, 0f, absoluto = true)

        // Se pincha en la arista entre las dos: la operación sube al booleano padre.
        editor.filetear(primera, 10f, 10f, 0f, 6f, 3f)
        assertNull(editor.ultimoError, "filetear fue rechazado")

        assertTrue("aU" in editor.huellaTopologica, "el árbol tenía que llevar el acuerdo local")
    }

    @Test
    fun `filetear sin un booleano por encima se rechaza`() {
        // La raíz es una unión, así que hace falta un caso donde de verdad no haya
        // ninguno: una pieza suelta cuyo único ancestro es ella misma.
        val editor = Editor(Documento.vacio())
        editor.filetear(editor.raizId, 0f, 0f, 0f, 5f, 2f)
        assertNotNull(editor.ultimoError, "la raíz vacía no junta nada que filetear")
    }

    @Test
    fun `quitar el filete devuelve la booleana exacta`() {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CAJA.name, null)
        val primera = assertNotNull(editor.seleccionado)
        editor.anadir(TipoPieza.CAJA.name, null)
        editor.mover(assertNotNull(editor.seleccionado), 20f, 0f, 0f, absoluto = true)

        editor.filetear(primera, 10f, 10f, 0f, 6f, 3f)
        editor.quitarFilete(editor.raizId)
        assertNull(editor.ultimoError)

        assertTrue("aU" !in editor.huellaTopologica, "quitar el filete vuelve a la booleana exacta")
    }
}
