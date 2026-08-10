package yunkil

import yunkil.kernel.Caja
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
 * Lo medido:
 *
 *  - Un primitivo suelto —esfera, caja, cilindro, caja redondeada— **nunca** se cruza.
 *  - Un **escalón cóncavo** sí, y a unas resoluciones sí y a otras no. Da igual cómo se
 *    construya el escalón: sale igual uniendo dos cilindros que revolucionando un contorno
 *    de una pieza. No es, por tanto, la costura de una booleana: es la arista viva
 *    entrante, que es justo el caso que peor lleva el dual contouring —el vértice de la
 *    celda quiere colocarse fuera de ella, se le recorta a su celda, y los cuadriláteros
 *    que salen pueden cruzarse—.
 *
 * El certificado hace lo que debe: se niega a escribir. El síntoma es una exportación que
 * no sale, no una pieza rota.
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
    fun `un escalon concavo se cruza a algunas resoluciones y a otras no`() {
        // Esta prueba documenta un defecto, no una garantía: cuando alguien arregle el
        // contorneado, fallará. Cuando falle, lo que hay que hacer es borrarla y quitar el
        // límite del README, no relajarla.
        val union = Union(
            Cilindro(8f, 1.6f, 0f),
            Transformado(
                Cilindro(4f, 14f, 0f),
                Transform.IDENTITY.copy(translation = Vec3(0f, 7f, 0f)),
            ),
            0f,
        )
        val revolucion = Revolucion(
            Perfil2D.poligono(
                listOf(
                    Punto2(0f, 0f), Punto2(8f, 0f), Punto2(8f, 1.6f),
                    Punto2(4f, 1.6f), Punto2(4f, 15f), Punto2(0f, 15f),
                ),
            ),
            0f,
        )

        for ((nombre, nodo) in mapOf("unión" to union, "revolución" to revolucion)) {
            val fallan = resoluciones.filter { cruces(nodo, it) > 0 }
            assertTrue(
                fallan.isNotEmpty(),
                "el escalón por $nombre ya no se cruza a ninguna resolución: el defecto está " +
                    "arreglado, borra esta prueba y el límite del README",
            )
            assertTrue(
                fallan.size < resoluciones.size,
                "el escalón por $nombre se cruza a todas las resoluciones; antes no",
            )
        }
    }
}
