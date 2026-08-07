package yunkil

import yunkil.doc.ModelosDemo
import yunkil.kernel.Caja
import yunkil.kernel.Cilindro
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.SdfNode
import yunkil.kernel.Vec3
import yunkil.malla.ContorneadoDual
import yunkil.malla.Malla
import yunkil.malla.Stl
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun mallar(nodo: SdfNode, resolucion: Float): Malla =
    ContorneadoDual(nodo, resolucion).generar()

/** Mayor distancia de un vértice de la malla a la superficie real del campo. */
private fun desviacionMaxima(malla: Malla, nodo: SdfNode): Float {
    var peor = 0f
    for (i in 0 until malla.numeroDeVertices) {
        val d = abs(nodo.evaluar(malla.vertice(i)))
        if (d > peor) peor = d
    }
    return peor
}

class ContorneadoTest {

    @Test
    fun `la malla de una esfera es cerrada y esta bien orientada`() {
        val malla = mallar(Esfera(20f), 1.2f)
        val topologia = malla.revisarTopologia()

        assertTrue(malla.numeroDeTriangulos > 500, "malla sospechosamente pequeña")
        assertEquals(0, topologia.aristasAbiertas, "la superficie tiene agujeros")
        assertEquals(0, topologia.aristasInvertidas, "hay caras del revés")
        assertEquals(0, topologia.triangulosDegenerados)
        assertTrue(topologia.esImprimible)
    }

    @Test
    fun `las normales apuntan hacia fuera`() {
        // Con las normales invertidas el volumen sale negativo, y el laminador
        // imprimiría el negativo de la pieza.
        assertTrue(mallar(Esfera(20f), 1.2f).volumen() > 0f)
    }

    @Test
    fun `el volumen de la malla se acerca al volumen analitico`() {
        val radio = 20f
        val esperado = (4.0 / 3.0 * PI * radio * radio * radio).toFloat()
        val obtenido = mallar(Esfera(radio), 0.8f).volumen()

        val error = abs(obtenido - esperado) / esperado
        assertTrue(error < 0.02f, "error de volumen ${error * 100}% (esperado $esperado, obtenido $obtenido)")
    }

    @Test
    fun `los vertices se apoyan en la superficie real`() {
        val nodo = Esfera(20f)
        val resolucion = 1f
        val desviacion = desviacionMaxima(mallar(nodo, resolucion), nodo)

        // El contorneado dual coloca el vértice dentro de su celda, así que la
        // desviación está acotada por la propia resolución.
        assertTrue(desviacion < resolucion, "desviación $desviacion con resolución $resolucion")
    }

    /**
     * La razón de usar contorneado dual en vez de marching cubes: una caja tiene
     * que salir con las aristas vivas, no redondeadas.
     */
    @Test
    fun `una caja conserva sus aristas vivas`() {
        val semilado = 15f
        val nodo = Caja(Vec3.splat(semilado))
        val malla = mallar(nodo, 1.5f)

        assertTrue(malla.revisarTopologia().esImprimible)

        val esperado = (semilado * 2f) * (semilado * 2f) * (semilado * 2f)
        val error = abs(malla.volumen() - esperado) / esperado
        // Marching cubes se dejaría los ocho vértices y las doce aristas por el
        // camino, y el error de volumen se dispararía muy por encima de esto.
        assertTrue(error < 0.02f, "error de volumen ${error * 100}%")
    }

    @Test
    fun `una pieza con taladros pasantes sigue siendo cerrada`() {
        val nodo = Diferencia(
            Caja(Vec3(30f, 8f, 20f), redondeo = 2f),
            Cilindro(radio = 5f, altura = 60f),
        )
        val topologia = mallar(nodo, 0.7f).revisarTopologia()

        assertEquals(0, topologia.aristasAbiertas, "el taladro dejó la malla abierta")
        assertEquals(0, topologia.aristasInvertidas)
    }

    @Test
    fun `el modelo de ejemplo completo produce una malla imprimible`() {
        val nodo = ModelosDemo.soporte().compilar()!!
        val malla = mallar(nodo, 1.2f)

        assertTrue(malla.revisarTopologia().esImprimible, "el soporte no salió imprimible")
        assertTrue(malla.volumen() > 0f)
        assertTrue(malla.area() > 0f)
    }

    @Test
    fun `saltar el vacio no cambia el resultado`() {
        // El salto por distancia es una optimización: si alterase la malla sería un
        // fallo silencioso, así que se comprueba contra la geometría analítica.
        val nodo = Esfera(25f)
        val malla = mallar(nodo, 1f)
        assertTrue(desviacionMaxima(malla, nodo) < 1f)
        assertTrue(malla.revisarTopologia().esImprimible)
    }
}

class StlTest {

    @Test
    fun `el STL binario tiene el tamano y la cuenta correctos`() {
        val malla = mallar(Esfera(10f), 2f)
        val bytes = Stl.binario(malla)

        assertEquals(84 + malla.numeroDeTriangulos * 50, bytes.size)

        // La cuenta de triángulos va en little-endian justo tras los 80 de cabecera.
        val cuenta = (bytes[80].toInt() and 0xFF) or
            ((bytes[81].toInt() and 0xFF) shl 8) or
            ((bytes[82].toInt() and 0xFF) shl 16) or
            ((bytes[83].toInt() and 0xFF) shl 24)
        assertEquals(malla.numeroDeTriangulos, cuenta)
    }

    @Test
    fun `la cabecera identifica el archivo`() {
        val bytes = Stl.binario(mallar(Esfera(10f), 3f), "Yunkil 0.1")
        val cabecera = bytes.copyOfRange(0, 10).decodeToString()
        assertEquals("Yunkil 0.1", cabecera)
    }

    @Test
    fun `ninguna normal sale nula`() {
        // Hay laminadores que se fían de la normal del STL; una nula los descoloca.
        val malla = mallar(Esfera(12f), 2f)
        val bytes = Stl.binario(malla)

        for (t in 0 until malla.numeroDeTriangulos) {
            val base = 84 + t * 50
            var nula = true
            for (b in 0 until 12) if (bytes[base + b] != 0.toByte()) nula = false
            assertTrue(!nula, "el triángulo $t salió con normal nula")
        }
    }
}

class CertificadoTest {

    @Test
    fun `el certificado aprueba una pieza sana y cuadra los dos volumenes`() {
        val nodo = ModelosDemo.soporte().compilar()!!
        val exportador = yunkil.malla.Exportador(nodo)
        val malla = ContorneadoDual(nodo, 1.0f).generar()

        val certificado = exportador.examinar(malla, 1.0f, 0)

        assertTrue(certificado.apto, certificado.resumen())
        assertTrue(certificado.desviacionMaxima < 1.5f, "desviación ${certificado.desviacionMaxima}")
        // El volumen por muestreo del campo y el de la malla son medidas
        // independientes: si no cuadran, la malla no representa el modelo.
        assertTrue(certificado.errorDeVolumen < 0.05f, "error de volumen ${certificado.errorDeVolumen}")
    }

    @Test
    fun `una malla rota se marca como no apta`() {
        val nodo = Esfera(10f)
        // Se le quita un triángulo a mano: la superficie deja de ser cerrada.
        val completa = ContorneadoDual(nodo, 2f).generar()
        val rota = Malla(completa.vertices, completa.triangulos.copyOf(completa.triangulos.size - 3))

        val certificado = yunkil.malla.Exportador(nodo).examinar(rota, 2f, 0)

        assertTrue(!certificado.cerrada)
        assertTrue(!certificado.apto)
        assertTrue(certificado.resumen().contains("NO APTA"))
    }

    @Test
    fun `exportar escribe el archivo y certifica el resultado`() {
        val ruta = "build/prueba-export.stl"
        val editor = yunkil.doc.Editor(ModelosDemo.esferaSuelta())

        val certificado = editor.exportarStl(ruta, 1.5f)

        assertTrue(certificado != null)
        assertTrue(certificado!!.apto, certificado.resumen())
        assertTrue(certificado.bytes > 84, "no se escribió el archivo")
        assertEquals(84 + certificado.triangulos * 50, certificado.bytes)
    }

    @Test
    fun `el 3MF pasa por el mismo examen y se escribe entero`() {
        val ruta = "build/prueba-export.3mf"
        val editor = yunkil.doc.Editor(ModelosDemo.esferaSuelta())

        val certificado = editor.exportarPieza(ruta, 1.5f)

        assertTrue(certificado != null)
        // El examen es el mismo a propósito: el formato del archivo no cambia nada si
        // el sólido está roto, y un 3MF con una malla abierta es tan inservible como
        // un STL con una malla abierta.
        assertTrue(certificado!!.apto, certificado.resumen())
        assertTrue(certificado.bytes > 1000, "no se escribió el paquete: ${certificado.bytes} bytes")
    }

    @Test
    fun `un documento vacio no exporta nada`() {
        val editor = yunkil.doc.Editor(yunkil.doc.Documento.vacio())
        assertEquals(null, editor.exportarStl("build/no-deberia-existir.stl", 1f))
        assertTrue(editor.ultimoError != null)
    }
}
