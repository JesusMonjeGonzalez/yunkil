package yunkil

import yunkil.doc.ModelosDemo
import yunkil.kernel.Caja
import yunkil.kernel.Vec3
import yunkil.malla.ContorneadoDual
import yunkil.malla.Malla
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La última comprobación que le faltaba al examen de una malla.
 *
 * Cerrada, bien orientada, sin degenerados y con la desviación medida: una malla
 * puede cumplir las cuatro y **atravesarse a sí misma**. Pasa en las paredes más
 * finas que una celda del contorneado, donde las dos caras de la pared caen en
 * celdas vecinas y sus triángulos se cruzan. El laminador reacciona a eso de una de
 * dos formas, las dos malas: rellena de más porque no sabe qué es dentro y qué es
 * fuera, o descarta la pieza entera.
 *
 * El caso de prueba no es una malla generada, sino dos triángulos escritos a mano
 * que se cruzan: una malla mala de verdad y comprobable a ojo. Contra una malla
 * generada nunca sabrías si la prueba pasa porque el detector funciona o porque no
 * había nada que detectar.
 */
class AutoInterseccionTest {

    /** Dos triángulos que se cruzan en cruz, sin compartir ningún vértice. */
    private fun cruz(): Malla = Malla(
        vertices = floatArrayOf(
            -10f, 0f, -10f, 10f, 0f, -10f, 0f, 0f, 10f, // horizontal, en y = 0
            0f, -10f, -5f, 0f, -10f, 5f, 0f, 10f, 0f, // vertical, cortando al anterior
        ),
        triangulos = intArrayOf(0, 1, 2, 3, 4, 5),
    )

    /** Los mismos dos triángulos, separados en Y para que no lleguen a tocarse. */
    private fun separados(): Malla = Malla(
        vertices = floatArrayOf(
            -10f, 0f, -10f, 10f, 0f, -10f, 0f, 0f, 10f,
            0f, 30f, -5f, 0f, 30f, 5f, 0f, 50f, 0f,
        ),
        triangulos = intArrayOf(0, 1, 2, 3, 4, 5),
    )

    @Test
    fun `dos triangulos cruzados se denuncian`() {
        assertEquals(1, cruz().autoIntersecciones(), "no vio el cruce")
    }

    @Test
    fun `dos triangulos separados no`() {
        assertEquals(0, separados().autoIntersecciones(), "denunció un cruce que no existe")
    }

    @Test
    fun `los triangulos vecinos no cuentan como cruce`() {
        // Es la mitad del trabajo del detector: en una malla cerrada cada triángulo
        // toca a tres vecinos por sus aristas y a unos cuantos más por sus vértices.
        // Un detector que contara esos contactos daría cientos de falsos positivos en
        // cualquier pieza correcta y el gate de exportación quedaría inservible.
        val cubo = ContorneadoDual(Caja(Vec3(10f, 10f, 10f), 0f), 2f).generar()
        assertTrue(cubo.numeroDeTriangulos > 20, "el cubo de prueba salió vacío")
        assertEquals(0, cubo.autoIntersecciones(), "una caja no se atraviesa a sí misma")
    }

    @Test
    fun `una chapa mas fina que la rejilla no se entrega, y se dice por que`() {
        // Medido: una caja de 0,3 mm de grueso mallada a 1 mm da cero triángulos. La
        // malla vacía pasa por «cerrada» y «bien orientada» de vacío —no hay ni una
        // arista que pueda estar suelta—, así que lo único que lo para es el volumen.
        // Y lo que el usuario tiene que leer no es «volumen 0», es qué hacer.
        val chapa = ContorneadoDual(Caja(Vec3(20f, 0.15f, 20f), 0f), 1f).generar()
        assertEquals(0, chapa.numeroDeTriangulos, "el caso de prueba ya no está vacío")

        val certificado = yunkil.malla.Exportador(Caja(Vec3(20f, 0.15f, 20f), 0f))
            .examinar(chapa, 1f, 0)

        assertTrue(!certificado.apto, "dio por buena una malla sin un solo triángulo")
        assertTrue(certificado.salioVacia)
        assertTrue(
            "más fino" in certificado.resumen() && "resolución" in certificado.resumen(),
            "el informe no explica por qué salió vacía:\n${certificado.resumen()}",
        )
    }

    @Test
    fun `una pieza normal sale limpia y su certificado lo dice`() {
        val editor = yunkil.doc.Editor(ModelosDemo.esferaSuelta())
        val certificado = editor.exportarStl("build/prueba-autointerseccion.stl", 1.5f)

        assertTrue(certificado != null)
        assertEquals(0, certificado!!.autoIntersecciones, certificado.resumen())
        assertTrue(certificado.apto, certificado.resumen())
    }
}
