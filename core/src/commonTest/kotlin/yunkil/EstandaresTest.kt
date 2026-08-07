package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.TipoPieza
import yunkil.fabricacion.Estandares
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Los patrones de montaje, contrastados contra la norma cota a cota.
 *
 * Aquí la prueba **es** el producto. Una escuadra con el voladizo mal sale fea; un
 * panel de rack con los agujeros a la separación equivocada no entra en el bastidor,
 * y no hay forma de arreglarlo después. Por eso se comprueban los números exactos y
 * no un orden de magnitud.
 */
class EstandaresTest {

    @Test
    fun `una unidad de rack mide lo que dice EIA-310`() {
        // 1,75 pulgadas exactas. Redondear a 44,5 acumula 1,1 mm en 2U.
        assertEquals(44.45f, Estandares.UNIDAD_DE_RACK)
        assertEquals(482.6f, Estandares.ANCHO_DE_PANEL)
        assertEquals(465.1f, Estandares.SEPARACION_DE_CARRILES)

        // El panel lleva 0,79 mm menos que la cota nominal para no rozar con el de
        // arriba. Sin ese hueco, dos paneles contiguos no asientan.
        assertTrue(abs(Estandares.alturaDePanel(1) - 43.66f) < 0.01f, "1U: ${Estandares.alturaDePanel(1)}")
        assertTrue(abs(Estandares.alturaDePanel(2) - 88.11f) < 0.01f, "2U: ${Estandares.alturaDePanel(2)}")
    }

    @Test
    fun `el reparto vertical del rack no es regular`() {
        // Es el detalle que hace fallar a quien lo calcula de memoria: dentro de una
        // unidad los agujeros van a 12,7 – 15,875 – 15,875, con la media pulgada a
        // caballo entre dos unidades. Repartirlos por igual desplaza la fila entera.
        val patron = assertNotNull(Estandares.porNombre("RACK_19", unidades = 2))
        assertEquals(12, patron.puntos.size, "2U son 3 agujeros por unidad y por lado")

        val alturas = patron.puntos.map { it.y }.distinct().sorted()
        assertEquals(6, alturas.size, "deberían salir seis alturas distintas")

        val saltos = alturas.zipWithNext { a, b -> b - a }
        val esperados = listOf(15.875f, 15.875f, 12.7f, 15.875f, 15.875f)
        for ((i, salto) in saltos.withIndex()) {
            assertTrue(
                abs(salto - esperados[i]) < 0.001f,
                "salto ${i + 1}: $salto, la norma dice ${esperados[i]}",
            )
        }

        // Y la fila entera cabe justo en las dos unidades.
        val alto = 2 * Estandares.UNIDAD_DE_RACK
        assertTrue(abs(alturas.first() + alto * 0.5f - 6.35f) < 0.001f, "el primero va a 1/4\" del borde")
        assertTrue(abs(alturas.last() - alto * 0.5f + 6.35f) < 0.001f, "el último, simétrico")
    }

    @Test
    fun `las dos filas estan a la separacion de la norma`() {
        val patron = assertNotNull(Estandares.porNombre("RACK_19", unidades = 1))
        val xs = patron.puntos.map { it.x }.distinct().sorted()
        assertEquals(2, xs.size)
        assertTrue(abs((xs[1] - xs[0]) - 465.1f) < 0.001f, "separación ${xs[1] - xs[0]}")
        assertEquals("M6", patron.rosca)
    }

    @Test
    fun `los VESA son cuadrados del tamaño que dicen`() {
        for ((nombre, lado, rosca) in listOf(
            Triple("VESA_75", 75f, "M4"),
            Triple("VESA_100", 100f, "M4"),
            Triple("VESA_200", 200f, "M6"),
            Triple("VESA_400", 400f, "M8"),
        )) {
            val patron = assertNotNull(Estandares.porNombre(nombre), "falta $nombre")
            assertEquals(4, patron.puntos.size, "$nombre debería tener cuatro agujeros")
            assertEquals(rosca, patron.rosca, "$nombre lleva $rosca")

            val ancho = patron.puntos.maxOf { it.x } - patron.puntos.minOf { it.x }
            val alto = patron.puntos.maxOf { it.y } - patron.puntos.minOf { it.y }
            assertTrue(abs(ancho - lado) < 0.001f, "$nombre mide $ancho de ancho")
            assertTrue(abs(alto - lado) < 0.001f, "$nombre mide $alto de alto")
        }

        // El MIS-E es el único que no es cuadrado, y confundirlo con el de 200 es un
        // error fácil que deja el monitor colgando de dos tornillos.
        val mise = assertNotNull(Estandares.porNombre("VESA_200X100"))
        assertTrue(abs((mise.puntos.maxOf { it.x } - mise.puntos.minOf { it.x }) - 200f) < 0.001f)
        assertTrue(abs((mise.puntos.maxOf { it.y } - mise.puntos.minOf { it.y }) - 100f) < 0.001f)
    }

    @Test
    fun `las primitivas llegan a la cota que exige cada estandar`() {
        // Esto se escribió después de tropezar: la `CAJA` topaba en 400 mm y un panel
        // de rack mide 482,6. La anchura se recortaba **en silencio**, los agujeros
        // caían fuera de la pieza y salía una bandeja sin taladrar. Un catálogo de
        // estándares que las primitivas no pueden alcanzar no sirve para nada, así que
        // la relación entre las dos cosas queda atada aquí.
        val caja = TipoPieza.CAJA.parametros.associateBy { it.clave }
        val anchoMaximo = assertNotNull(caja["anchura"]).maximo

        assertTrue(
            anchoMaximo >= Estandares.ANCHO_DE_PANEL,
            "una CAJA llega a $anchoMaximo mm y un panel de rack mide ${Estandares.ANCHO_DE_PANEL}",
        )

        for (nombre in listOf(
            "RACK_19", "VESA_75", "VESA_100", "VESA_200X100", "VESA_200", "VESA_400",
            "RASPBERRY_PI", "RASPBERRY_PI_ZERO",
        )) {
            val patron = assertNotNull(Estandares.porNombre(nombre, unidades = 4), "falta $nombre")
            val ancho = patron.puntos.maxOf { it.x } - patron.puntos.minOf { it.x }
            val alto = patron.puntos.maxOf { it.y } - patron.puntos.minOf { it.y }
            assertTrue(
                ancho <= anchoMaximo && alto <= assertNotNull(caja["altura"]).maximo,
                "$nombre necesita ${ancho}×${alto} mm y la CAJA no llega",
            )
        }
    }

    @Test
    fun `un plan de rack sale con todos sus agujeros`() {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"resumen":"Bandeja de rack de 2U","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"panel","nombre":"Panel",
               "parametros":{"anchura":482.6,"altura":4,"profundidad":88.11,"redondeo":0}},
              {"op":"patron","objetivo":"panel","estandar":"RACK_19","unidades":2}
            ]}
            """.trimIndent()
        )
        val plan = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        val resultado = editor.aplicarPlan(plan, null)

        assertTrue(resultado.exito, "no se aplicó: ${resultado.resumen}")
        assertTrue(resultado.omitidas.isEmpty(), "se cayeron operaciones: ${resultado.omitidas}")

        val brocas = editor.filas().filter { it.nombre.startsWith("Taladro") }
        assertEquals(12, brocas.size, "2U son doce agujeros y salieron ${brocas.size}")

        // Y caen donde tienen que caer, no solo en la cantidad correcta.
        val xs = brocas.map { editor.transformDe(it.id)[0] }
        assertTrue(abs(xs.max() - xs.min() - 465.1f) < 0.05f, "separación real ${xs.max() - xs.min()}")
    }

    @Test
    fun `un estandar que no existe se rechaza nombrando los que si`() {
        val editor = Editor(Documento.vacio())
        editor.anadir("CAJA", editor.idDeLaRaiz)
        val caja = assertNotNull(editor.seleccionado)

        assertTrue(!editor.aplicarPatron(caja, "RACK_21"), "un estándar inventado debe rechazarse")
        val error = assertNotNull(editor.ultimoError)
        assertTrue("VESA_100" in error, "el rechazo debería listar los válidos: $error")
    }
}
