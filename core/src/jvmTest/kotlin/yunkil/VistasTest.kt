package yunkil

import yunkil.imagen.Png
import yunkil.imagen.Vistas
import yunkil.kernel.Caja
import yunkil.kernel.Esfera
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vec3
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Las vistas son lo que va a mirar un modelo de visión para decir si la pieza es lo
 * que se pidió, así que no basta con que salga un archivo: tiene que **abrirlo un
 * decodificador de verdad** y tiene que enseñar la forma correcta.
 *
 * Esta prueba vive en `jvmTest` porque usa `ImageIO`, que es un juez independiente del
 * codificador propio. Es la misma precaución que ya se tomó con el ZIP del 3MF:
 * comprobar un archivo escrito a mano con el mismo código que lo escribe no comprueba
 * nada.
 */
class VistasTest {

    @Test
    fun `el PNG lo abre el decodificador del sistema`() {
        val png = Vistas.cuatroVistas(Esfera(20f), lado = 96)
        val imagen = ImageIO.read(ByteArrayInputStream(png))
        assertNotNull(imagen, "ImageIO no supo leer el PNG")
        assertEquals(192, imagen.width)
        assertEquals(192, imagen.height)
    }

    @Test
    fun `la vista de frente conserva las medidas de la pieza`() {
        // La comprobación que hace que estas imágenes valgan para juzgar proporciones:
        // una caja de 60 × 40 vista de frente ocupa 60 y 40 en la escala del cuadro.
        // Medido en milímetros, no a ojo.
        //
        // Ya cazó un encuadre malo: se usaba `Aabb.radius` como semilado, que es el
        // semilado real por raíz de tres, y todo salía al 58 % de su tamaño. Compilaba,
        // se veía bien y regalaba casi la mitad de los píxeles que va a mirar el modelo.
        val lado = 200
        val caja = Caja(Vec3(30f, 20f, 10f)) // 60 × 40 × 20 mm
        val encuadre = caja.cotas()
        val semilado = Vistas.semiladoPara(encuadre)
        val pixeles = Vistas.dibujar(caja, encuadre, Vistas.Angulo.FRENTE, lado, semilado)

        val mmPorPixel = (2f * semilado) / lado
        val pintado = { i: Int -> (pixeles[i].toInt() and 0xFF) > 40 }
        val ancho = (0 until lado).count { x -> (0 until lado).any { y -> pintado(y * lado + x) } }
        val alto = (0 until lado).count { y -> (0 until lado).any { x -> pintado(y * lado + x) } }

        assertEquals(60f, ancho * mmPorPixel, 2f * mmPorPixel, "ancho medido")
        assertEquals(40f, alto * mmPorPixel, 2f * mmPorPixel, "alto medido")
    }

    @Test
    fun `una esfera sale redonda y del tamano que le toca`() {
        // El cuadro lo fija la peor de las cuatro vistas, que para una caja envolvente
        // cúbica es la isométrica; por eso una esfera no llena el cuadro y no es un
        // fallo. Lo que se comprueba es que su área sea exactamente la del círculo de su
        // radio en la escala del cuadro.
        val lado = 160
        val esfera = Esfera(15f)
        val semilado = Vistas.semiladoPara(esfera.cotas())
        val pixeles = Vistas.dibujar(esfera, esfera.cotas(), Vistas.Angulo.FRENTE, lado, semilado)

        val fraccion = pixeles.count { (it.toInt() and 0xFF) > 40 }.toFloat() / (lado * lado)
        val esperado = (PI.toFloat() * 15f * 15f) / ((2f * semilado) * (2f * semilado))
        assertTrue(
            abs(fraccion - esperado) < 0.02f,
            "la esfera ocupa $fraccion y la cuenta dice $esperado",
        )
    }

    @Test
    fun `las cuatro vistas son distintas entre si`() {
        // Una pieza asimétrica tiene que verse distinta desde cada ángulo. Si dos vistas
        // salieran iguales, la cámara no estaría donde dice estar y las cuatro imágenes
        // costarían cuatro veces lo que informa una.
        val pieza = Union(
            Caja(Vec3(30f, 5f, 20f)),
            Transformado(Caja(Vec3(4f, 20f, 4f)), Transform(translation = Vec3(20f, 20f, 0f))),
        )
        val encuadre = pieza.cotas()
        val vistas = Vistas.Angulo.entries.map {
            Vistas.dibujar(pieza, encuadre, it, 64)
        }
        for (i in vistas.indices) {
            for (j in i + 1 until vistas.size) {
                val iguales = vistas[i].indices.count { vistas[i][it] == vistas[j][it] }
                assertTrue(
                    iguales < vistas[i].size * 95 / 100,
                    "las vistas ${Vistas.Angulo.entries[i]} y ${Vistas.Angulo.entries[j]} " +
                        "coinciden en el ${iguales * 100 / vistas[i].size} % de los píxeles",
                )
            }
        }
    }

    @Test
    fun `la planta de una caja plana la ve ancha y el frente la ve fina`() {
        // Una losa de 60 × 6 × 40: desde arriba se ve casi todo el cuadro, de frente se ve
        // una franja. Es la comprobación de que las direcciones no están cambiadas, que es
        // el fallo silencioso de este código —sale una imagen bonita de la pieza
        // equivocada—.
        val losa = Caja(Vec3(30f, 3f, 20f))
        val encuadre = losa.cotas()
        val planta = Vistas.dibujar(losa, encuadre, Vistas.Angulo.PLANTA, 96)
        val frente = Vistas.dibujar(losa, encuadre, Vistas.Angulo.FRENTE, 96)

        val enPlanta = planta.count { (it.toInt() and 0xFF) > 40 }
        val enFrente = frente.count { (it.toInt() and 0xFF) > 40 }
        assertTrue(
            enPlanta > enFrente * 2,
            "desde arriba se ve $enPlanta y de frente $enFrente: las direcciones están cruzadas",
        )
    }

    @Test
    fun `un modelo vacio da una imagen de fondo y no revienta`() {
        // El campo de un documento vacío es un sólido lejanísimo o nada. Que esto se
        // dibuje sin excepción importa porque la miniatura se pide al abrir, antes de
        // que haya nada.
        val png = Vistas.cuatroVistas(Esfera(0.5f), lado = 32)
        assertNotNull(ImageIO.read(ByteArrayInputStream(png)))
    }

    @Test
    fun `el gris que se escribe es el gris que se lee`() {
        // Comprobado contra ImageIO, no contra el decodificador de la otra prueba: si el
        // codificador y el comprobador comparten un error de signo, los dos coinciden.
        val pixeles = ByteArray(64) { ((it * 4) % 256).toByte() }
        val imagen = ImageIO.read(ByteArrayInputStream(Png.gris(8, 8, pixeles)))
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val leido = imagen.raster.getSample(x, y, 0)
                assertEquals(pixeles[y * 8 + x].toInt() and 0xFF, leido, "píxel ($x, $y)")
            }
        }
    }

    /**
     * Deja las cuatro vistas en `build/vistas.png` para poder mirarlas.
     *
     * No es una prueba de nada y no afirma nada: es la única forma de que alguien vea
     * lo que ve el modelo, y sin eso las decisiones sobre iluminación y encuadre se
     * toman a ciegas.
     */
    @Test
    fun `deja una muestra en disco para poder mirarla`() {
        val pieza = Union(
            Caja(Vec3(30f, 5f, 20f)),
            Transformado(Caja(Vec3(4f, 18f, 4f)), Transform(translation = Vec3(22f, 18f, 12f))),
        )
        val destino = File("build/vistas.png")
        destino.parentFile.mkdirs()
        destino.writeBytes(Vistas.cuatroVistas(pieza, lado = 240))
        assertTrue(destino.length() > 0)
    }
}
