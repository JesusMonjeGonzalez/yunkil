package yunkil

import yunkil.kernel.Caja
import yunkil.kernel.Diferencia
import yunkil.kernel.Cilindro
import yunkil.kernel.Esfera
import yunkil.kernel.Perfil2D
import yunkil.kernel.Punto2
import yunkil.kernel.Revolucion
import yunkil.kernel.SdfNode
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vec3
import yunkil.malla.ContorneadoDual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dónde se cruza consigo misma la malla, medido.
 *
 * El README decía que el contorneado fallaba «en codos cerrados a ciertas resoluciones» y
 * que quedaba por diagnosticar, con un cordón de 4 mm como único caso conocido. Estas
 * pruebas acotan el fallo con la geometría más simple en la que aparece, que es donde se
 * puede razonar sobre él.
 *
 * La causa resultó ser la **diagonal del cuadrilátero**. Las cuatro celdas de una cara
 * dual casi nunca dan cuatro puntos coplanares, así que elegir diagonal es elegir qué
 * superficie se dibuja; se partía siempre por la misma. Mientras la superficie es suave da
 * igual, porque las dos opciones se parecen. En una arista viva entrante los vértices se
 * van a esquinas opuestas de sus celdas, y dos cuadriláteros vecinos partidos cada uno por
 * la diagonal que se aleja de su arista común se pliegan el uno contra el otro. Esas dos
 * mitades no comparten ningún vértice, así que el certificado las veía cruzarse.
 *
 * Partir por la **diagonal más corta** lo arregla, y lo eligen igual los dos vecinos porque
 * depende solo de la geometría de la cara.
 *
 * Había un segundo defecto, y era de otra clase: no de mallado sino del campo. Una
 * revolución cuyo contorno cierra por el eje contaba esa arista de cierre como superficie,
 * y desde el eje medía cero en vez de medir hasta la pared. Se arregló en el perfil, que es
 * donde estaba: al girar, una arista sobre el eje no barre nada.
 */
class ContorneadoEnAristasVivasTest {

    private val resoluciones = (25..60 step 5).map { it / 100f }

    private fun cruces(nodo: SdfNode, resolucion: Float): Int =
        ContorneadoDual(nodo, resolucion).generar().autoIntersecciones()

    @Test
    fun `un primitivo suelto no se cruza consigo mismo a ninguna resolucion`() {
        val sueltos = mapOf<String, SdfNode>(
            "esfera" to Esfera(10f),
            "caja" to Caja(Vec3(30f, 1.5f, 6f), 0f),
            "caja redondeada" to Caja(Vec3(30f, 1.5f, 6f), 1f),
            "cilindro" to Cilindro(4f, 14f, 0f),
        )
        for ((nombre, nodo) in sueltos) {
            for (r in resoluciones) {
                assertEquals(0, cruces(nodo, r), "«$nombre» se cruza a $r mm")
            }
        }
    }

    @Test
    fun `un escalon concavo no se cruza a ninguna resolucion`() {
        // Este era el defecto que bloqueaba el cupón de calibración: dos cuadriláteros
        // vecinos, cada uno partido por la diagonal que se aleja de la arista que
        // comparten, plegándose el uno contra el otro. En una superficie suave no se nota
        // porque las dos diagonales se parecen; en una arista viva entrante los vértices
        // se van a esquinas opuestas de sus celdas y la diferencia es toda la cara.
        val escalon = Union(
            Cilindro(8f, 1.6f, 0f),
            Transformado(
                Cilindro(4f, 14f, 0f),
                Transform.IDENTITY.copy(translation = Vec3(0f, 7f, 0f)),
            ),
            0f,
        )
        for (r in resoluciones) {
            assertEquals(0, cruces(escalon, r), "el escalón se cruza a $r mm")
        }
    }

    @Test
    fun `una revolucion cuyo contorno toca el eje tampoco se cruza`() {
        // Este era el segundo defecto, y era de otra clase: no de mallado, sino del
        // campo. El contorno cierra por el propio eje, y esa arista de cierre se estaba
        // contando como superficie; el campo valía cero justo en el eje, el mallador
        // veía allí un cambio de signo donde no hay nada y ponía cuatro vértices casi en
        // el mismo punto —se midieron separaciones de 4·10⁻⁷ mm— con astillas que se
        // cruzaban entre sí. Solo aparecía a las resoluciones en las que la rejilla cae
        // justo sobre el eje, que es lo que lo hacía difícil de ver.
        //
        // Al girar, esa arista no barre superficie: se colapsa en el eje. Desde que el
        // perfil lo sabe —Perfil2D.evaluar con el eje de revolución—, desde el eje se
        // mide hasta la pared de verdad.
        val revolucion = Revolucion(
            Perfil2D.poligono(
                listOf(
                    Punto2(0f, 0f), Punto2(8f, 0f), Punto2(8f, 1.6f),
                    Punto2(4f, 1.6f), Punto2(4f, 15f), Punto2(0f, 15f),
                ),
            ),
            0f,
        )
        for (r in resoluciones) {
            assertEquals(0, cruces(revolucion, r), "la revolución se cruza a $r mm")
        }
    }

    @Test
    fun `una placa con un taladro no se cruza a ninguna resolucion`() {
        val placa = Diferencia(Caja(Vec3(30f, 1.5f, 6f), 0f), Cilindro(4.2f, 8f, 0f), 0f)
        for (r in resoluciones) {
            assertEquals(0, cruces(placa, r), "la placa taladrada se cruza a $r mm")
        }
    }

    @Test
    fun `la malla sigue siendo cerrada y bien orientada en una arista viva`() {
        // Partir por la otra diagonal cambia los triángulos, así que hay que comprobar que
        // no cambia lo que la malla es: un sólido estanco con las normales hacia fuera.
        val escalon = Union(
            Cilindro(8f, 1.6f, 0f),
            Transformado(
                Cilindro(4f, 14f, 0f),
                Transform.IDENTITY.copy(translation = Vec3(0f, 7f, 0f)),
            ),
            0f,
        )
        for (r in resoluciones) {
            val topologia = ContorneadoDual(escalon, r).generar().revisarTopologia()
            assertTrue(topologia.esCerrada, "quedan agujeros a $r mm: $topologia")
            assertTrue(topologia.estaBienOrientada, "hay caras del revés a $r mm: $topologia")
            assertEquals(0, topologia.triangulosDegenerados, "degenerados a $r mm")
        }
    }
}
