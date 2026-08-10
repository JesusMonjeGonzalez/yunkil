package yunkil

import yunkil.ia.CotasPedidas
import yunkil.ia.EjeNombrado
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cuando la petición dice «60 × 40 × 25 mm», eso no es una preferencia: es un hecho
 * comprobable sobre la pieza que salga. Leerlo es el primer paso de la post-condición
 * de cotas, que es el arnés que más paga de los cuatro.
 *
 * El listón para dar algo por dicho es alto a propósito. Una petición está llena de
 * números que no son cotas —«cuatro agujeros M3», «2 mm de pared»— y confundir uno con
 * la medida de la pieza escalaría el modelo entero por un malentendido.
 */
class CotasPedidasTest {

    private fun midan(texto: String) = assertNotNull(CotasPedidas.leer(texto), "no leyó nada en «$texto»")

    @Test
    fun `lee las tres cotas escritas con aspas`() {
        val cotas = midan("Hazme una caja de 60 × 40 × 25 mm con tapa")
        assertEquals(listOf(60f, 40f, 25f), cotas.libres.sortedDescending())
        assertNull(cotas.eje, "un triple no dice qué cota va en qué eje")
    }

    @Test
    fun `lee las tres cotas escritas con equis y sin espacios`() {
        assertEquals(listOf(60f, 40f, 25f), midan("una base de 60x40x25mm").libres.sortedDescending())
    }

    @Test
    fun `los centimetros pasan a milimetros`() {
        val cotas = midan("una placa de 8 cm de ancho")
        assertEquals(EjeNombrado.X, cotas.eje)
        assertTrue(abs(cotas.medidaDelEje!! - 80f) < 0.01f, "8 cm son 80 mm, no ${cotas.medidaDelEje}")
    }

    @Test
    fun `una cota con su eje nombrado se queda con el eje`() {
        val cotas = midan("un soporte de 120 mm de alto")
        assertEquals(EjeNombrado.Y, cotas.eje)
        assertTrue(abs(cotas.medidaDelEje!! - 120f) < 0.01f)
    }

    @Test
    fun `una cota de largo no elige eje, porque largo no dice cual`() {
        // «Largo» es la dirección mayor de la pieza, y cuál es eso depende de cómo la
        // haya montado el modelo. Se guarda la medida sin eje y ya se emparejará con
        // la cota que de verdad mida más.
        val cotas = midan("una barra de 60 mm de largo")
        assertNull(cotas.eje)
        assertEquals(listOf(60f), cotas.libres)
    }

    @Test
    fun `una peticion sin medidas no inventa ninguna`() {
        assertNull(CotasPedidas.leer("Hazme un soporte para el móvil, que quede elegante"))
    }

    @Test
    fun `los numeros que no son cotas de la pieza no cuentan`() {
        // Ni la rosca, ni la cantidad, ni el grosor de pared son la medida de la pieza.
        assertNull(CotasPedidas.leer("una caja con 4 agujeros M3 y 2 mm de pared"))
    }

    @Test
    fun `un diametro no se toma por una cota del eje`() {
        // «40 mm de diámetro» gobierna dos ejes a la vez y no dice cuáles; tomarlo por
        // uno solo escalaría la pieza por la mitad de un dato.
        assertNull(CotasPedidas.leer("un disco de 40 mm de diámetro"))
    }
}
