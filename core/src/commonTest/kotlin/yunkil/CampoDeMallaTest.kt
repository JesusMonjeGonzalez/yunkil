package yunkil

import yunkil.kernel.Aabb
import yunkil.kernel.Caja
import yunkil.kernel.CampoDeMalla
import yunkil.kernel.Esfera
import yunkil.kernel.Vec3
import yunkil.malla.ContorneadoDual
import yunkil.malla.LectorStl
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Traer geometría de fuera: malla → campo de distancias.
 *
 * La prueba que importa es de ida y vuelta contra una forma que sí tiene fórmula: se
 * malla una esfera exacta, se hornea esa malla como si viniera de un archivo, y se
 * compara el campo horneado con el analítico. Si el horneado estuviera mal, aquí se
 * ve en milímetros; comprobarlo contra un STL descargado no diría nada, porque no
 * habría con qué comparar.
 */
class CampoDeMallaTest {

    private val esfera = Esfera(20f)

    /** La esfera pasada por el mallador, que es lo más parecido a un STL de fuera. */
    private fun mallaDeEsfera() = ContorneadoDual(esfera, 0.6f).generar()

    @Test
    fun `el campo horneado reproduce la forma original`() {
        val malla = mallaDeEsfera()
        val campo = CampoDeMalla.hornear(malla.vertices, malla.triangulos, resolucionDeseada = 0.7f)

        // Se compara en la banda exacta alrededor de la superficie, que es donde el
        // campo tiene que ser fiel: es lo que leen el mallador y el analizador.
        var peor = 0f
        var donde = Vec3.ZERO
        val pasos = 14
        for (i in 0..pasos) for (j in 0..pasos) {
            val u = i.toFloat() / pasos * 2f - 1f
            val v = j.toFloat() / pasos * 2f - 1f
            for (radio in listOf(18f, 20f, 22f)) {
                val largo = kotlin.math.sqrt(u * u + v * v + 1f)
                val p = Vec3(u / largo * radio, v / largo * radio, 1f / largo * radio)
                val error = abs(campo.evaluar(p) - esfera.evaluar(p))
                if (error > peor) { peor = error; donde = p }
            }
        }

        // Dos celdas de tolerancia: el mallado ya introdujo su propia desviación y el
        // horneado interpola. Pedirle más a un campo discreto sería pedirle magia.
        assertTrue(peor < 1.4f, "el campo se desvía $peor mm en $donde")
    }

    @Test
    fun `el signo distingue dentro de fuera`() {
        val malla = mallaDeEsfera()
        val campo = CampoDeMalla.hornear(malla.vertices, malla.triangulos, resolucionDeseada = 0.9f)

        // Lejos de la superficie el valor se satura a ±banda: es una cota inferior de
        // la distancia real, que es lo que necesita el trazado, no la distancia exacta.
        // Lo que sí tiene que ser correcto siempre es el signo.
        assertTrue(campo.evaluar(Vec3.ZERO) < 0f, "el centro debería estar dentro")
        assertTrue(campo.evaluar(Vec3(0f, 0f, 60f)) > 0f, "lejos debería estar fuera")
        assertTrue(campo.evaluar(Vec3(0f, 30f, 0f)) > 0f, "justo fuera debería ser positivo")
        assertTrue(campo.evaluar(Vec3(0f, 10f, 0f)) < 0f, "a mitad de radio debería ser material")
    }

    @Test
    fun `una malla importada se puede volver a mallar y sale cerrada`() {
        // Es la razón de ser de todo esto: en cuanto la malla es campo, vuelve a
        // pasar por la misma cadena verificada que el resto de piezas.
        val malla = mallaDeEsfera()
        val campo = CampoDeMalla.hornear(malla.vertices, malla.triangulos, resolucionDeseada = 0.9f)
        val topologia = ContorneadoDual(campo, 0.8f).generar().revisarTopologia()

        assertTrue(topologia.esCerrada, "malla abierta: ${topologia.aristasAbiertas} aristas")
        assertTrue(topologia.estaBienOrientada, "${topologia.aristasInvertidas} aristas invertidas")
    }

    @Test
    fun `el campo compone con las primitivas de siempre`() {
        // Restarle una ranura a una pieza traída de fuera es exactamente el caso que
        // justifica el horneado: geometría que nadie puede escribir, editada con las
        // operaciones que sí se pueden escribir.
        val malla = mallaDeEsfera()
        val campo = CampoDeMalla.hornear(malla.vertices, malla.triangulos, resolucionDeseada = 0.9f)
        val ranura = Caja(Vec3(30f, 3f, 30f))

        val dentroDeLaRanura = Vec3(0f, 0f, 0f)
        val fueraDeLaRanura = Vec3(0f, 10f, 0f)

        // Diferencia a mano: max(a, −b). Si el campo importado no tuviera signo
        // correcto, esto daría material donde debería haber hueco.
        fun diferencia(p: Vec3) = maxOf(campo.evaluar(p), -ranura.evaluar(p))

        assertTrue(diferencia(dentroDeLaRanura) > 0f, "la ranura debería haber vaciado el centro")
        assertTrue(diferencia(fueraDeLaRanura) < 0f, "fuera de la ranura debería seguir habiendo material")
    }

    @Test
    fun `las cotas contienen todo el material`() {
        val malla = mallaDeEsfera()
        val campo = CampoDeMalla.hornear(malla.vertices, malla.triangulos, resolucionDeseada = 0.9f)
        val c: Aabb = campo.cotas()

        var peor = Float.POSITIVE_INFINITY
        val pasos = 12
        for (i in 0..pasos) for (j in 0..pasos) {
            val u = i.toFloat() / pasos
            val v = j.toFloat() / pasos
            val x = c.min.x + c.size.x * u
            val y = c.min.y + c.size.y * u
            val z = c.min.z + c.size.z * v
            val yv = c.min.y + c.size.y * v
            for (p in listOf(
                Vec3(c.min.x, y, z), Vec3(c.max.x, y, z),
                Vec3(x, c.min.y, z), Vec3(x, c.max.y, z),
                Vec3(x, yv, c.min.z), Vec3(x, yv, c.max.z),
            )) peor = minOf(peor, campo.evaluar(p))
        }
        assertTrue(peor >= 0f, "hay material fuera de las cotas: $peor mm")
    }

    // ------------------------------------------------------------------ lector

    /** Un STL binario de un solo triángulo, montado a mano byte a byte. */
    private fun stlBinarioDeUnTriangulo(): ByteArray {
        val bytes = ByteArray(84 + 50)
        // cuenta = 1, little endian
        bytes[80] = 1
        fun ponerFlotante(en: Int, v: Float) {
            val bits = v.toRawBits()
            bytes[en] = (bits and 0xFF).toByte()
            bytes[en + 1] = ((bits shr 8) and 0xFF).toByte()
            bytes[en + 2] = ((bits shr 16) and 0xFF).toByte()
            bytes[en + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        var p = 84 + 12 // saltando la normal
        for (v in listOf(
            Triple(0f, 0f, 0f), Triple(10f, 0f, 0f), Triple(0f, 10f, 0f),
        )) {
            ponerFlotante(p, v.first); ponerFlotante(p + 4, v.second); ponerFlotante(p + 8, v.third)
            p += 12
        }
        return bytes
    }

    @Test
    fun `lee un STL binario`() {
        val leido = LectorStl.leer(stlBinarioDeUnTriangulo())
        val resultado = leido as? LectorStl.Resultado.Leida
        assertTrue(resultado != null, "no se leyó: ${(leido as? LectorStl.Resultado.Fallo)?.motivo}")
        assertTrue(resultado.esBinario, "debería haberse detectado como binario")
        assertEquals(1, resultado.malla.numeroDeTriangulos)
        assertEquals(3, resultado.malla.numeroDeVertices)
    }

    @Test
    fun `lee un STL de texto y suelda los vertices repetidos`() {
        // Un STL repite cada vértice en cada triángulo que lo toca. Sin soldarlos,
        // las aristas se quedan sin pareja y una malla cerrada sale como abierta.
        val texto = """
            solid cuadrado
              facet normal 0 0 1
                outer loop
                  vertex 0 0 0
                  vertex 10 0 0
                  vertex 10 10 0
                endloop
              endfacet
              facet normal 0 0 1
                outer loop
                  vertex 0 0 0
                  vertex 10 10 0
                  vertex 0 10 0
                endloop
              endfacet
            endsolid cuadrado
        """.trimIndent()

        val leido = LectorStl.leer(texto.encodeToByteArray())
        val resultado = leido as? LectorStl.Resultado.Leida
        assertTrue(resultado != null, "no se leyó: ${(leido as? LectorStl.Resultado.Fallo)?.motivo}")
        assertTrue(!resultado.esBinario, "debería haberse detectado como texto")
        assertEquals(2, resultado.malla.numeroDeTriangulos)
        assertEquals(4, resultado.malla.numeroDeVertices, "seis vértices escritos, cuatro distintos")
    }

    @Test
    fun `un archivo que no es un STL se rechaza con motivo`() {
        val fallo = LectorStl.leer("esto no es un STL ni de lejos".encodeToByteArray())
        assertTrue(fallo is LectorStl.Resultado.Fallo, "debería rechazarse")
    }
}
