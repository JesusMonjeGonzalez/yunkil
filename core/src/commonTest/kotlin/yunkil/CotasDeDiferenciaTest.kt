package yunkil

import yunkil.kernel.AcuerdoLocal
import yunkil.kernel.Caja
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.ModoDeAcuerdo
import yunkil.kernel.Vec3
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Las cotas de una resta con acuerdo.
 *
 * Lo encontró el banco de modelado, y así es como debe encontrarse un fallo de este tipo:
 * el modelo hizo una caja de 60 mm con el canto interior redondeado —`fusion` en la
 * DIFERENCIA, que es exactamente lo correcto— y el banco midió 62. Reproducido a mano sin
 * la fusión daba 60,0 exactos, así que el ancho de más no lo ponía el modelo: lo ponía
 * `Diferencia.cotas()`, que expandía sus cotas por la anchura de la mezcla.
 *
 * Y no debe expandirlas. `smoothMax(a, −b, k)` es siempre **mayor o igual** que
 * `max(a, −b)`, y mayor significa *menos* material: una resta con acuerdo solo puede quitar
 * más, nunca añadir. Las cotas del minuendo bastan y son conservadoras por construcción.
 *
 * No era cosmético. `acotar` lee esas cotas para escalar el conjunto y el analizador de
 * fabricación las usa para muestrear: con la caja midiendo 2 mm más de lo que mide, pedir
 * «que tenga 60 de ancho» dejaba la pieza a 58.
 */
class CotasDeDiferenciaTest {

    private val bloque = Caja(Vec3(30f, 12.5f, 20f))
    private val hueco = Caja(Vec3(27.6f, 11.3f, 17.6f))

    @Test
    fun `restar con acuerdo no ensancha las cotas`() {
        val exacta = Diferencia(bloque, hueco, fusion = 0f)
        val redondeada = Diferencia(bloque, hueco, fusion = 1f)

        assertTrue(
            abs(redondeada.cotas().size.x - exacta.cotas().size.x) < 1e-4f,
            "con acuerdo mide ${redondeada.cotas().size.x} y sin él ${exacta.cotas().size.x}",
        )
        assertTrue(abs(redondeada.cotas().size.x - 60f) < 1e-4f, "la caja mide 60 de ancho")
        assertTrue(abs(redondeada.cotas().size.y - 25f) < 1e-4f, "y 25 de alto")
    }

    @Test
    fun `las cotas de la resta siguen conteniendo todo el material`() {
        // La regla del kernel no se negocia: las cotas pueden sobrar, nunca faltar. Que
        // sean más apretadas solo vale si siguen conteniendo el campo.
        for (fusion in listOf(0f, 0.5f, 2f, 6f)) {
            val nodo = Diferencia(bloque, Esfera(24f), fusion = fusion)
            val c = nodo.cotas()
            val azar = Random(29)
            repeat(4_000) {
                val p = Vec3(
                    azar.nextFloat() * 100 - 50,
                    azar.nextFloat() * 100 - 50,
                    azar.nextFloat() * 100 - 50,
                )
                if (nodo.evaluar(p) < 0f) {
                    assertTrue(
                        p.x >= c.min.x - 1e-3f && p.x <= c.max.x + 1e-3f &&
                            p.y >= c.min.y - 1e-3f && p.y <= c.max.y + 1e-3f &&
                            p.z >= c.min.z - 1e-3f && p.z <= c.max.z + 1e-3f,
                        "con fusion=$fusion hay material en $p y las cotas $c no lo cubren",
                    )
                }
            }
        }
    }

    @Test
    fun `un acuerdo local en modo diferencia tampoco ensancha`() {
        val exacta = AcuerdoLocal(bloque, hueco, ModoDeAcuerdo.DIFERENCIA, Vec3.ZERO, 0f, 0f)
        val conFilete = AcuerdoLocal(bloque, hueco, ModoDeAcuerdo.DIFERENCIA, Vec3(20f, 10f, 0f), 6f, 3f)

        assertTrue(
            abs(conFilete.cotas().size.x - exacta.cotas().size.x) < 1e-4f,
            "con filete mide ${conFilete.cotas().size.x} y sin él ${exacta.cotas().size.x}",
        )
    }

    @Test
    fun `unir con acuerdo si ensancha, y tiene que seguir haciendolo`() {
        // El caso contrario, para que la corrección no se pase de lista: `smoothMin` es
        // menor o igual que el mínimo, y menor significa *más* material. Una unión con
        // acuerdo sí desborda las cotas de sus hijos y hay que declararlo.
        val a = Caja(Vec3(10f, 10f, 10f))
        val b = yunkil.kernel.Transformado(
            Caja(Vec3(10f, 10f, 10f)),
            yunkil.kernel.Transform(translation = Vec3(20f, 0f, 0f)),
        )
        val exacta = yunkil.kernel.Union(a, b, fusion = 0f)
        val fundida = yunkil.kernel.Union(a, b, fusion = 4f)

        assertTrue(
            fundida.cotas().size.x > exacta.cotas().size.x,
            "la unión fundida tiene que declarar más alcance que la exacta",
        )
    }
}
