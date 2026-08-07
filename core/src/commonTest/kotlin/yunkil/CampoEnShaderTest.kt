package yunkil

import yunkil.kernel.Caja
import yunkil.kernel.CampoDeMalla
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.Union
import yunkil.kernel.Vec3
import yunkil.msl.MslGenerator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El viewport tenía que pintar la envolvente de una malla importada porque el shader
 * no sabía leer un campo horneado. Era la función bandera del producto —coge un STL
 * cualquiera y devuélvelo imprimible— presentada como una caja gris.
 *
 * Esto comprueba las dos mitades: que el shader emite el muestreo cuando hay campo, y
 * —lo que de verdad protege el resto del sistema— que **no cambia nada** cuando no lo
 * hay.
 */
class CampoEnShaderTest {

    /** Una malla horneada de verdad: un cubo de 20 mm mallado a 2 mm. */
    private fun campoDeCubo(): CampoDeMalla {
        val h = 10f
        val v = ArrayList<Float>()
        val t = ArrayList<Int>()
        // Ocho vértices, doce triángulos. Escrito a mano para no depender del lector.
        for (i in 0 until 8) {
            v.add(if (i and 1 == 0) -h else h)
            v.add(if (i and 2 == 0) -h else h)
            v.add(if (i and 4 == 0) -h else h)
        }
        val caras = listOf(
            0, 2, 1, 1, 2, 3, // -Z
            4, 5, 6, 5, 7, 6, // +Z
            0, 1, 4, 1, 5, 4, // -Y
            2, 6, 3, 3, 6, 7, // +Y
            0, 4, 2, 2, 4, 6, // -X
            1, 3, 5, 3, 7, 5, // +X
        )
        t.addAll(caras)
        return CampoDeMalla.hornear(v.toFloatArray(), t.toIntArray(), 2f, origen = "cubo")
    }

    // ------------------------------------------------------------------ sin campos

    @Test
    fun `un arbol sin malla emite exactamente el shader de siempre`() {
        // Es la prueba que hace segura toda esta función. Si el shader de un árbol
        // normal cambiara aunque fuera en un espacio, los 24 casos del arnés de
        // paridad estarían midiendo otra cosa y no habría forma de saber si la
        // divergencia viene de aquí.
        val arbol = Diferencia(Caja(Vec3(20f, 10f, 15f)), Esfera(8f))
        val fuente = MslGenerator().generar(arbol).fuente

        assertFalse(fuente.contains("yk_campos"), "se coló el parámetro de texturas")
        assertFalse(fuente.contains("texture3d"), "se coló una textura")
        assertContains(fuente, "float yk_map(float3 p, constant float *u) {")
        assertEquals(emptyList(), MslGenerator().generar(arbol).campos)
    }

    // ------------------------------------------------------------------ con campos

    @Test
    fun `una malla emite el muestreo de textura y no una caja`() {
        val generado = MslGenerator().generar(campoDeCubo())
        // Lecturas sin filtrar y trilineal a mano: el filtro del muestreador de Metal
        // interpola con pesos de precisión reducida y el arnés midió 3,8 µm de desvío
        // contra la CPU. Se descartó para que la paridad siga siendo exacta.
        assertContains(generado.fuente, "yk_campos[0].read(")
        assertFalse(generado.fuente.contains("sample("), "volvió el muestreo filtrado")
        assertEquals(1, generado.campos.size)
    }

    @Test
    fun `la firma lleva el array de texturas en todas las funciones que lo necesitan`() {
        val fuente = MslGenerator().generar(campoDeCubo()).fuente
        for (firma in listOf(
            "float yk_map(float3 p, constant float *u, array<texture3d<float>, 1> yk_campos)",
            "float yk_marcha(float3 p, constant float *u, array<texture3d<float>, 1> yk_campos)",
        )) {
            assertContains(fuente, firma)
        }
        // Y la entrada del fragmento, que es la única que declara el enlace real.
        assertContains(fuente, "yk_campos [[texture(0)]]")
        // Ninguna llamada puede quedarse sin pasar el array: eso no compilaría en
        // Metal, pero aquí se ve sin necesidad del toolchain.
        assertFalse(
            fuente.contains("yk_map(p, u)") || fuente.contains("yk_marcha(p, u)"),
            "quedó una llamada sin el array de campos",
        )
    }

    @Test
    fun `el campo que viaja al renderizador es el mismo que horneo el nucleo`() {
        val campo = campoDeCubo()
        val enviado = MslGenerator().generar(campo).campos.single()
        assertEquals(campo.anchoEnCeldas, enviado.nx)
        assertEquals(campo.altoEnCeldas, enviado.ny)
        assertEquals(campo.fondoEnCeldas, enviado.nz)
        assertTrue(campo.muestras === enviado.muestras, "se copiaron 7 millones de floats")
        assertEquals(campo.celdas, enviado.muestras.size)
    }

    @Test
    fun `dos mallas se numeran por preorden y cada una lee su textura`() {
        // El fallo que esto caza: si cada cuerpo —el exacto y el podado— numerara los
        // campos por su cuenta, el podado saltaría ramas y asignaría índices distintos,
        // y una malla se pintaría con los datos de la otra sin error de compilación.
        val dos = Union(campoDeCubo(), campoDeCubo())
        val generado = MslGenerator().generar(dos)
        assertEquals(2, generado.campos.size)
        assertContains(generado.fuente, "yk_campos[0].read(")
        assertContains(generado.fuente, "yk_campos[1].read(")
        assertContains(generado.fuente, "array<texture3d<float>, 2>")

        // Y cada índice aparece las mismas veces en los dos cuerpos.
        val cero = Regex("yk_campos\\[0\\]").findAll(generado.fuente).count()
        val uno = Regex("yk_campos\\[1\\]").findAll(generado.fuente).count()
        assertEquals(cero, uno, "los dos campos no se emiten el mismo número de veces")
    }

    @Test
    fun `el tamano de la rejilla entra en la huella`() {
        // Las dimensiones van literales en el muestreo, así que dos rejillas distintas
        // necesitan shaders distintos. Sin esto, cambiar de STL a otro de otro tamaño
        // reutilizaría el shader viejo y la pieza saldría deformada.
        val fino = CampoDeMalla.hornear(verticesDeCubo(), trianglesDeCubo(), 1f)
        val grueso = CampoDeMalla.hornear(verticesDeCubo(), trianglesDeCubo(), 4f)
        val a = MslGenerator().generar(fino).huellaTopologica
        val b = MslGenerator().generar(grueso).huellaTopologica
        assertTrue(a != b, "dos rejillas distintas comparten huella: $a")
    }

    // ------------------------------------------------------- lo que dice el campo

    @Test
    fun `la trilineal del shader calca la de la CPU, esquina a esquina`() {
        // Ocho lecturas y siete mezclas, en el mismo orden que `CampoDeMalla.evaluar`.
        // El orden no es cosmético: en punto flotante la suma no es asociativa, y
        // reordenar las mezclas separaría el resultado del de la CPU justo en los
        // decimales que compara el arnés de paridad.
        val fuente = MslGenerator().generar(campoDeCubo()).fuente
        assertEquals(8, Regex("yk_campos\\[0\\]\\.read\\(").findAll(fuente).count() / 2,
            "no son ocho esquinas por cuerpo")
        assertContains(fuente, "uint3(1, 1, 1)")
        assertContains(fuente, "min(floor(")
    }

    @Test
    fun `fuera de la caja el shader devuelve distancia mas banda, como la CPU`() {
        // La igualdad exacta con la GPU la comprueba `tools/paridad`. Aquí se comprueba
        // que el código emitido dice lo mismo que hace `CampoDeMalla.evaluar`: fuera de
        // la rejilla, distancia a la caja **más la banda**. Sin el sumando, el trazado
        // se quedaría corto y la pieza aparecería a trozos.
        val campo = campoDeCubo()
        assertContains(MslGenerator().generar(campo).fuente, "+ ${campo.bandaDelCampo}f;")

        // Y que ese es de verdad el valor que da la CPU en un punto de fuera. La cota se
        // saca de la caja horneada y no del cubo original: `hornear` la ensancha para
        // que la banda quepa dentro, y escribir aquí el número a mano mediría mi
        // aritmética en vez del código.
        val lejos = Vec3(100f, 0f, 0f)
        val esperado = (lejos.x - campo.cotas().max.x) + campo.bandaDelCampo
        assertTrue(
            abs(campo.evaluar(lejos) - esperado) < 0.01f,
            "la CPU da ${campo.evaluar(lejos)} y se esperaba $esperado",
        )
    }

    private fun verticesDeCubo(): FloatArray {
        val h = 10f
        val v = ArrayList<Float>()
        for (i in 0 until 8) {
            v.add(if (i and 1 == 0) -h else h)
            v.add(if (i and 2 == 0) -h else h)
            v.add(if (i and 4 == 0) -h else h)
        }
        return v.toFloatArray()
    }

    private fun trianglesDeCubo() = intArrayOf(
        0, 2, 1, 1, 2, 3, 4, 5, 6, 5, 7, 6, 0, 1, 4, 1, 5, 4,
        2, 6, 3, 3, 6, 7, 0, 4, 2, 2, 4, 6, 1, 3, 5, 3, 7, 5,
    )
}
