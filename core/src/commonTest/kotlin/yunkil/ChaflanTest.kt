package yunkil

import yunkil.kernel.AcuerdoLocal
import yunkil.kernel.Caja
import yunkil.kernel.ModoDeAcuerdo
import yunkil.kernel.PerfilDeAcuerdo
import yunkil.kernel.Vec3
import yunkil.kernel.chaflanMin
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * El chaflán: cortar plano donde el filete redondea.
 *
 * Hasta hoy no existía y el intérprete mandaba `chaflan` a `filete`, así que quien pedía
 * un corte plano recibía un redondeo. Es la única casilla del análisis de competidores
 * que estaba marcada como hecha sin estarlo.
 *
 * La mezcla de chaflán clásica —`min(min(a,b), (a+b−k)·√½)`— **no se puede usar tal
 * cual** en Yunkil, y este fichero existe para atar por qué. Tiene dos defectos y los
 * dos importan aquí más que en un shader de demo:
 *
 *  1. **No se apaga.** Con `k = 0` el término cruzado sigue mandando dentro del sólido
 *     —para `a = b = −10` da −14,1 en vez de −10—, y la caída local del acuerdo existe
 *     precisamente para que fuera de su esfera la booleana sea **exacta**.
 *  2. **Se desmadra hacia dentro.** El error crece con la profundidad sin techo, y ahí
 *     dentro es donde leen `pared` y el analizador de grosor: una pared medida sobre un
 *     campo que miente por 14 mm no es una medida, es un número.
 *
 * La versión de aquí acota el término cruzado a `0,71·k` por debajo de la booleana. Con
 * eso la **superficie no cambia** —en la superficie las dos distancias son pequeñas y el
 * tope no llega a actuar— y el campo de dentro tiene un error acotado y declarado.
 */
class ChaflanTest {

    private val raiz2 = 0.70710678f

    @Test
    fun `sin anchura el chaflan es la booleana exacta`() {
        // Es la propiedad que hace que el acuerdo sea *local*: fuera de su esfera de
        // influencia la pieza tiene que ser exactamente la booleana de siempre.
        for (a in listOf(-10f, -1f, 0f, 1f, 10f)) {
            for (b in listOf(-10f, -1f, 0f, 1f, 10f)) {
                assertTrue(
                    abs(chaflanMin(a, b, 0f) - min(a, b)) < 1e-6f,
                    "con k=0 el chaflán de ($a, $b) tiene que ser min y dio ${chaflanMin(a, b, 0f)}",
                )
            }
        }
    }

    @Test
    fun `el chaflan corta mas material que el filete en la propia arista`() {
        // En la arista las dos distancias valen cero. El filete deja −k/4 y el chaflán
        // −0,71·k: corta más, que es exactamente la diferencia entre biselar y redondear.
        val k = 4f
        val chaflan = chaflanMin(0f, 0f, k)
        assertTrue(chaflan < -k * 0.25f, "el chaflán dio $chaflan y tenía que cortar más que el filete")
        assertTrue(abs(chaflan + k * raiz2) < 1e-5f, "en la arista el chaflán vale −0,71·k, y dio $chaflan")
    }

    @Test
    fun `el chaflan nunca se aleja de la booleana mas de lo declarado`() {
        // El tope es lo que hace utilizable esta mezcla: sin él, dentro del sólido el
        // término cruzado crece sin límite y envenena todo lo que lee el campo por
        // dentro —el grosor de pared, el vaciado, el analizador—.
        val k = 3f
        for (a in -50..50) for (b in -50..50) {
            val fa = a * 0.5f
            val fb = b * 0.5f
            val exacto = min(fa, fb)
            val valor = chaflanMin(fa, fb, k)
            assertTrue(
                valor <= exacto + 1e-5f && valor >= exacto - k * raiz2 - 1e-5f,
                "chaflán($fa, $fb, $k) = $valor, fuera de [${exacto - k * raiz2}, $exacto]",
            )
        }
    }

    @Test
    fun `el nodo declara un gradiente que de verdad acota al suyo`() {
        // El mismo trato que se le dio al filete: si la mezcla no es 1-Lipschitz, el
        // generador publica un paso de trazado seguro y el renderizador lo respeta. Un
        // paso mayor que el gradiente real se salta la pieza.
        val nodo = AcuerdoLocal(
            a = Caja(Vec3(10f, 10f, 10f), 0f),
            b = Caja(Vec3(10f, 10f, 10f), 0f).let { it },
            modo = ModoDeAcuerdo.UNION,
            centro = Vec3(10f, 10f, 0f),
            radio = 8f,
            fusion = 4f,
            perfil = PerfilDeAcuerdo.CHAFLAN,
        )
        val h = 0.05f
        var peor = 0f
        var x = -14f
        while (x <= 14f) {
            var y = -14f
            while (y <= 14f) {
                val p = Vec3(x, y, 0f)
                val gx = (nodo.evaluar(Vec3(x + h, y, 0f)) - nodo.evaluar(Vec3(x - h, y, 0f))) / (2 * h)
                val gy = (nodo.evaluar(Vec3(x, y + h, 0f)) - nodo.evaluar(Vec3(x, y - h, 0f))) / (2 * h)
                val g = kotlin.math.sqrt(gx * gx + gy * gy)
                if (g > peor) peor = g
                y += 0.25f
            }
            x += 0.25f
        }
        assertTrue(
            peor <= nodo.lipschitz + 0.02f,
            "el gradiente medido es $peor y el nodo declara ${nodo.lipschitz}",
        )
    }
}
