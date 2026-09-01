package yunkil

import yunkil.doc.Documento
import yunkil.fabricacion.compararPerfiles
import yunkil.doc.Editor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La placa como concepto del núcleo —empaquetar, separar un ensamblaje, medir la
 * distancia entre dos piezas— y las herramientas que responden sin el análisis
 * completo: comparador de perfiles, advertencias rápidas y hitos de deshacer.
 */
class PlacaHitosDetalleTest {

    @Test
    fun `empaquetar coloca las piezas en el plato sin solaparse`() {
        val editor = Editor(Documento.vacio())
        val ids = mutableListOf<String>()
        for (i in 1..3) {
            assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
            ids.add(assertNotNull(editor.seleccionado))
        }
        assertTrue(editor.empaquetarEnPlaca(ids), editor.ultimoError)

        val plantas = ids.mapNotNull { id -> editor.cotasDe(id)?.let { it to id } }
        assertEquals(3, plantas.size)
        // Todas apoyadas: la base toca el plato.
        for ((cotas, _) in plantas) {
            assertTrue(cotas.min.y < 0.01f, "la pieza debe estar apoyada: min.y=${cotas.min.y}")
        }
        // Y ninguna planta toca a la anterior.
        for (i in 0 until plantas.size) {
            for (j in 0 until plantas.size) {
                if (i == j) continue
                val (a, _) = plantas[i]
                val (b, _) = plantas[j]
                val separadasEnX = a.max.x <= b.min.x + 0.01f || b.max.x <= a.min.x + 0.01f
                val separadasEnZ = a.max.z <= b.min.z + 0.01f || b.max.z <= a.min.z + 0.01f
                assertTrue(separadasEnX || separadasEnZ, "las plantas $i y $j se solapan")
            }
        }
    }

    @Test
    fun `empaquetar una pieza mas ancha que el plato se rechaza con su motivo`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val caja = assertNotNull(editor.seleccionado)
        editor.fijarParametro(caja, "anchura", 600f)
        assertEquals(600f, editor.parametro(caja, "anchura"))
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val otra = assertNotNull(editor.seleccionado)
        // 600 mm de ancho frente a los 256 de una P1S: no entra, y no se disfraza.
        assertFalse(editor.empaquetarEnPlaca(listOf(caja, otra)))
        assertTrue(
            editor.ultimoError?.contains("no cabe") == true ||
                editor.ultimoError?.contains("más ancha") == true,
            editor.ultimoError,
        )
    }

    @Test
    fun `separar un ensamblaje deja los cuerpos sin tocarse`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val a = assertNotNull(editor.seleccionado)
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val b = assertNotNull(editor.seleccionado)
        editor.declararEnsamblaje("Caja y tapa", listOf(a, b))
        assertNull(editor.ultimoError, editor.ultimoError)
        val ensamblaje = editor.ensamblajes().single()

        editor.separarEnsamblaje(ensamblaje.id, holguraDeMontaje = 3f)
        assertNull(editor.ultimoError, "separar no puede fallar: ${editor.ultimoError}")
        val medida = editor.distanciaEntre(a, b)
        assertFalse(medida.interfieren, "los cuerpos deben quedar separados")
        assertNotNull(medida.holguraMinima)
    }

    @Test
    fun `la distancia entre dos piezas separadas se mide y se queda corta`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val a = assertNotNull(editor.seleccionado)
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val b = assertNotNull(editor.seleccionado)
        assertTrue(editor.fijarTransform(b, 60f, 0f, 0f, 0f, 0f, 0f, 1f))
        // 20 mm de aire entre las cajas; la medida muestral debe rondarlo por abajo.
        val medida = editor.distanciaEntre(a, b, paso = 1f)
        assertFalse(medida.interfieren)
        val holgura = assertNotNull(medida.holguraMinima)
        assertTrue(holgura in 15f..20.5f, "holgura $holgura")
    }

    @Test
    fun `el comparador de perfiles responde apto y puntuacion por perfil`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val filas = editor.compararPerfiles(
            listOf(
                "Bambu P1S · PLA · 0,4",
                "Bambu A1 · PETG · 0,4",
            ),
        )
        assertEquals(2, filas.size)
        for (fila in filas) {
            assertTrue(fila.apto, "una caja de 40 mm es apta en ${fila.perfil}: ${fila.resumen}")
            assertTrue(fila.puntuacion in 0..100)
        }
        // Ordenadas de mejor a peor, para leer primero la candidata.
        assertTrue(filas[0].puntuacion >= filas[1].puntuacion)
    }

    @Test
    fun `las advertencias rapidas ven un radio imposible y una pieza que no cabe`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("EXTRUSION", null), editor.ultimoError)
        val fino = assertNotNull(editor.seleccionado)
        // La altura mínima de una extrusión es 0,2: sí se puede pedir una lámina más
        // fina que la boquilla, y la advertencia debe verlo.
        editor.fijarParametro(fino, "altura", 0.3f)
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val grande = assertNotNull(editor.seleccionado)
        editor.fijarParametro(grande, "anchura", 600f)

        val avisos = editor.advertenciasRapidas()
        assertTrue(
            avisos.any { it.contains("Extrusión") && it.contains("detalle mínimo") },
            "una lámina de 0,3 mm no llega a existir con una boquilla de 0,4: $avisos",
        )
        assertTrue(
            avisos.any { it.contains("Caja") && it.contains("no cabe") },
            "600 mm no caben en una P1S: $avisos",
        )
    }

    @Test
    fun `un hito con nombre devuelve al documento de entonces`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        assertTrue(editor.marcarHito("antes de los agujeros"))
        val pieza = assertNotNull(editor.seleccionado)
        assertTrue(editor.anadir("CILINDRO", null), editor.ultimoError)
        assertTrue(editor.anadir("TORO", null), editor.ultimoError)
        assertEquals(3, editor.filas().count { !it.esOperacion })

        assertTrue(editor.deshacerHasta("antes de los agujeros"), editor.ultimoError)
        assertEquals(1, editor.filas().count { !it.esOperacion })
        assertNotNull(editor.pieza(pieza), "la caja sigue ahí: el hito la incluye")

        // Y el cursor anda en los dos sentidos mientras el camino exista.
        assertTrue(editor.rehacer())
        assertTrue(editor.deshacerHasta("antes de los agujeros"))
        assertEquals(1, editor.filas().count { !it.esOperacion })
    }

    @Test
    fun `un hito inalcanzable se dice y no se finge`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        assertTrue(editor.marcarHito("m"))
        assertTrue(editor.anadir("CILINDRO", null), editor.ultimoError)
        // Deshacer más allá del hito y editar: el camino hacia adelante se pierde.
        assertTrue(editor.deshacer())
        assertTrue(editor.deshacer())
        assertTrue(editor.anadir("TORO", null), editor.ultimoError)
        assertFalse(editor.deshacerHasta("m"))
        assertTrue(
            editor.ultimoError?.contains("no se puede alcanzar") == true,
            editor.ultimoError,
        )
    }
}
