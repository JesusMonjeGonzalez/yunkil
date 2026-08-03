package yunkil

import yunkil.kernel.Aabb
import yunkil.kernel.Axis
import yunkil.kernel.Caja
import yunkil.kernel.Capsula
import yunkil.kernel.Cilindro
import yunkil.kernel.Cono
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.Interseccion
import yunkil.kernel.Quat
import yunkil.kernel.Repeticion
import yunkil.kernel.SdfNode
import yunkil.kernel.Simetria
import yunkil.kernel.Toro
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vaciado
import yunkil.kernel.Vec3
import yunkil.kernel.empaquetarUniforms
import yunkil.kernel.normal
import yunkil.kernel.preorden
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TOL = 1e-4f

private fun assertCercano(esperado: Float, real: Float, mensaje: String = "") {
    assertTrue(
        abs(esperado - real) <= TOL,
        "$mensaje esperado $esperado pero fue $real (diferencia ${abs(esperado - real)})",
    )
}

/**
 * Nube de puntos determinista alrededor del origen. Se usa para las propiedades
 * que deben cumplirse en todo el dominio, no solo en puntos escogidos.
 */
private fun nube(n: Int = 400, alcance: Float = 60f, semilla: Int = 20260803): List<Vec3> {
    val r = Random(semilla)
    return List(n) {
        Vec3(
            (r.nextFloat() * 2f - 1f) * alcance,
            (r.nextFloat() * 2f - 1f) * alcance,
            (r.nextFloat() * 2f - 1f) * alcance,
        )
    }
}

class PrimitivasTest {

    @Test
    fun `la esfera devuelve la distancia euclidea exacta`() {
        val s = Esfera(10f)
        assertCercano(-10f, s.evaluar(Vec3.ZERO), "centro")
        assertCercano(0f, s.evaluar(Vec3(10f, 0f, 0f)), "superficie")
        assertCercano(5f, s.evaluar(Vec3(15f, 0f, 0f)), "exterior")
        assertCercano(-4f, s.evaluar(Vec3(0f, 6f, 0f)), "interior")
    }

    @Test
    fun `la caja mide bien por cara, por arista y por vertice`() {
        val c = Caja(Vec3(10f, 10f, 10f))
        assertCercano(-10f, c.evaluar(Vec3.ZERO), "centro")
        assertCercano(5f, c.evaluar(Vec3(15f, 0f, 0f)), "frente a una cara")
        // Fuera por dos ejes a la vez: la distancia es la diagonal, no la suma.
        assertCercano(5f, c.evaluar(Vec3(13f, 14f, 0f)), "frente a una arista")
        assertCercano(
            Vec3(3f, 4f, 12f).length(),
            c.evaluar(Vec3(13f, 14f, 22f)),
            "frente a un vértice",
        )
    }

    @Test
    fun `el redondeo de la caja retranquea la superficie`() {
        val viva = Caja(Vec3(10f, 10f, 10f))
        val redondeada = Caja(Vec3(10f, 10f, 10f), redondeo = 2f)
        // En el centro de una cara el redondeo no cambia nada.
        assertCercano(viva.evaluar(Vec3(15f, 0f, 0f)), redondeada.evaluar(Vec3(15f, 0f, 0f)))
        // En la esquina sí: la caja redondeada está más lejos.
        val esquina = Vec3(12f, 12f, 12f)
        assertTrue(redondeada.evaluar(esquina) > viva.evaluar(esquina))
    }

    @Test
    fun `el cilindro mide bien lateral, axial y en el canto`() {
        val c = Cilindro(radio = 5f, altura = 20f)
        assertCercano(-5f, c.evaluar(Vec3.ZERO), "centro")
        assertCercano(3f, c.evaluar(Vec3(8f, 0f, 0f)), "lateral")
        assertCercano(4f, c.evaluar(Vec3(0f, 14f, 0f)), "sobre la tapa")
        assertCercano(5f, c.evaluar(Vec3(8f, 14f, 0f)), "diagonal desde el canto")
    }

    @Test
    fun `el cono con radios iguales coincide con el cilindro`() {
        val cil = Cilindro(radio = 6f, altura = 15f)
        val con = Cono(radioInferior = 6f, radioSuperior = 6f, altura = 15f)
        for (p in nube(200, alcance = 25f)) {
            assertCercano(cil.evaluar(p), con.evaluar(p), "en $p")
        }
    }

    @Test
    fun `el toro mide desde su circunferencia generatriz`() {
        val t = Toro(radioMayor = 20f, radioMenor = 5f)
        assertCercano(-5f, t.evaluar(Vec3(20f, 0f, 0f)), "sobre la generatriz")
        assertCercano(0f, t.evaluar(Vec3(25f, 0f, 0f)), "superficie exterior")
        assertCercano(0f, t.evaluar(Vec3(15f, 0f, 0f)), "superficie interior")
        assertCercano(15f, t.evaluar(Vec3.ZERO), "el agujero central")
    }

    @Test
    fun `la capsula es un segmento engrosado`() {
        val c = Capsula(radio = 4f, altura = 10f)
        assertCercano(-4f, c.evaluar(Vec3.ZERO), "centro")
        assertCercano(0f, c.evaluar(Vec3(0f, 9f, 0f)), "polo superior")
        assertCercano(2f, c.evaluar(Vec3(6f, 0f, 0f)), "lateral")
    }
}

class OperacionesTest {

    private val a = Esfera(10f)
    private val b = Transformado(Esfera(10f), Transform(translation = Vec3(8f, 0f, 0f)))

    @Test
    fun `sin fusion la union es exactamente el minimo`() {
        val u = Union(a, b)
        for (p in nube()) {
            assertCercano(minOf(a.evaluar(p), b.evaluar(p)), u.evaluar(p), "en $p")
        }
    }

    @Test
    fun `sin fusion la interseccion es exactamente el maximo`() {
        val i = Interseccion(a, b)
        for (p in nube()) {
            assertCercano(maxOf(a.evaluar(p), b.evaluar(p)), i.evaluar(p), "en $p")
        }
    }

    @Test
    fun `la diferencia quita material y nunca lo anade`() {
        val d = Diferencia(a, b)
        for (p in nube()) {
            // Todo punto sólido de la diferencia debe ser sólido en el minuendo.
            if (d.evaluar(p) < 0f) assertTrue(a.evaluar(p) < 0f, "en $p")
            // Y ningún punto del sustraendo puede quedar sólido.
            if (b.evaluar(p) < 0f) assertTrue(d.evaluar(p) >= 0f, "en $p")
        }
    }

    @Test
    fun `la fusion suaviza sin despegarse del minimo`() {
        val duro = Union(a, b, fusion = 0f)
        val suave = Union(a, b, fusion = 4f)
        for (p in nube()) {
            // Fusionar solo puede añadir material en la garganta, nunca quitarlo.
            assertTrue(
                suave.evaluar(p) <= duro.evaluar(p) + TOL,
                "la fusión quitó material en $p",
            )
        }
    }
}

class ModificadoresTest {

    @Test
    fun `la traslacion desplaza el campo sin deformarlo`() {
        val movida = Transformado(Esfera(5f), Transform(translation = Vec3(10f, 0f, 0f)))
        assertCercano(-5f, movida.evaluar(Vec3(10f, 0f, 0f)), "nuevo centro")
        assertCercano(5f, movida.evaluar(Vec3.ZERO), "origen antiguo")
    }

    @Test
    fun `la rotacion no altera las distancias de una forma simetrica`() {
        val girada = Transformado(
            Caja(Vec3(10f, 2f, 2f)),
            Transform(rotation = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), (PI / 2).toFloat())),
        )
        // La caja larga en X pasa a serlo en Z tras girar 90° alrededor de Y.
        assertCercano(0f, girada.evaluar(Vec3(0f, 0f, 10f)), "extremo girado")
        assertCercano(-2f, girada.evaluar(Vec3.ZERO), "centro")
    }

    @Test
    fun `la escala uniforme mantiene el campo como distancia verdadera`() {
        val escalada = Transformado(Esfera(5f), Transform(scale = 2f))
        // Una esfera de radio 5 escalada al doble es una esfera de radio 10.
        val referencia = Esfera(10f)
        for (p in nube(200, alcance = 30f)) {
            assertCercano(referencia.evaluar(p), escalada.evaluar(p), "en $p")
        }
    }

    @Test
    fun `el vaciado produce una cascara del grosor pedido`() {
        val hueca = Vaciado(Esfera(20f), grosor = 3f)
        // La superficie original queda en mitad del espesor de la cáscara.
        assertCercano(-1.5f, hueca.evaluar(Vec3(20f, 0f, 0f)), "sobre la superficie original")
        assertCercano(0f, hueca.evaluar(Vec3(21.5f, 0f, 0f)), "cara exterior")
        assertCercano(0f, hueca.evaluar(Vec3(18.5f, 0f, 0f)), "cara interior")
        assertTrue(hueca.evaluar(Vec3.ZERO) > 0f, "el interior debe quedar hueco")
    }

    @Test
    fun `la simetria refleja respecto al plano del eje`() {
        val original = Transformado(Esfera(4f), Transform(translation = Vec3(10f, 0f, 0f)))
        val reflejada = Simetria(original, Axis.X)
        assertCercano(-4f, reflejada.evaluar(Vec3(10f, 0f, 0f)), "copia original")
        assertCercano(-4f, reflejada.evaluar(Vec3(-10f, 0f, 0f)), "copia reflejada")
    }

    @Test
    fun `la repeticion coloca las copias centradas y espaciadas`() {
        val fila = Repeticion(Esfera(2f), cuenta = 3, paso = 10f, eje = Axis.X)
        assertCercano(-2f, fila.evaluar(Vec3(-10f, 0f, 0f)), "primera")
        assertCercano(-2f, fila.evaluar(Vec3.ZERO), "central")
        assertCercano(-2f, fila.evaluar(Vec3(10f, 0f, 0f)), "última")
        assertTrue(fila.evaluar(Vec3(20f, 0f, 0f)) > 0f, "no debe haber una cuarta")
    }
}

class CotasTest {

    private val modelo: SdfNode = Diferencia(
        Union(
            Caja(Vec3(20f, 10f, 15f), redondeo = 2f),
            Transformado(Esfera(12f), Transform(translation = Vec3(0f, 10f, 0f))),
            fusion = 3f,
        ),
        Transformado(Cilindro(5f, 60f), Transform(translation = Vec3(0f, 0f, 0f))),
    )

    @Test
    fun `las cotas contienen todo el solido`() {
        val c = modelo.cotas()
        var comprobados = 0
        for (p in nube(3000, alcance = 60f)) {
            if (modelo.evaluar(p) < 0f) {
                comprobados++
                assertTrue(
                    p.x >= c.min.x - TOL && p.x <= c.max.x + TOL &&
                        p.y >= c.min.y - TOL && p.y <= c.max.y + TOL &&
                        p.z >= c.min.z - TOL && p.z <= c.max.z + TOL,
                    "el punto sólido $p quedó fuera de las cotas $c",
                )
            }
        }
        assertTrue(comprobados > 50, "la nube apenas tocó el sólido, el test no prueba nada")
    }

    @Test
    fun `una interseccion vacia no produce cotas invertidas`() {
        val separadas = Interseccion(
            Esfera(1f),
            Transformado(Esfera(1f), Transform(translation = Vec3(100f, 0f, 0f))),
        )
        val c = separadas.cotas()
        assertTrue(c.min.x <= c.max.x && c.min.y <= c.max.y && c.min.z <= c.max.z, "cotas $c")
    }
}

class UniformsTest {

    private val modelo: SdfNode = Union(
        Caja(Vec3(10f, 5f, 5f), redondeo = 1f),
        Transformado(Cilindro(3f, 20f), Transform(translation = Vec3(5f, 0f, 0f))),
        fusion = 2f,
    )

    @Test
    fun `el empaquetado sigue exactamente el preorden del arbol`() {
        val esperado = modelo.preorden().flatMap { it.escalares }
        assertEquals(esperado, modelo.empaquetarUniforms().toList())
    }

    @Test
    fun `cambiar un parametro no altera el tamano del buffer`() {
        val otro = (modelo as Union).copy(fusion = 9f)
        assertEquals(modelo.empaquetarUniforms().size, otro.empaquetarUniforms().size)
    }
}

class NormalTest {

    @Test
    fun `la normal de una esfera apunta radialmente hacia fuera`() {
        val n = Esfera(10f).normal(Vec3(10f, 0f, 0f))
        assertCercano(1f, n.x, "componente radial")
        assertCercano(0f, n.y)
        assertCercano(0f, n.z)
    }

    @Test
    fun `la normal siempre sale unitaria`() {
        val modelo = Union(Caja(Vec3(10f, 6f, 6f), redondeo = 2f), Esfera(8f))
        for (p in nube(200, alcance = 20f)) {
            assertCercano(1f, modelo.normal(p).length(), "en $p")
        }
    }
}
