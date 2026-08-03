package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.ModelosDemo
import yunkil.doc.TipoPieza
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorTest {

    @Test
    fun `anadir una pieza la deja dentro del padre y seleccionada`() {
        val editor = Editor()
        val raiz = editor.filas().first().id

        assertTrue(editor.anadir(TipoPieza.CAJA.name, raiz), "añadir debe forzar recompilación")

        val filas = editor.filas()
        assertEquals(2, filas.size)
        assertEquals("CAJA", filas[1].tipo)
        assertEquals(filas[1].id, editor.seleccionado)
    }

    @Test
    fun `anadir sobre una primitiva la coloca como hermana`() {
        // Una caja no admite hijos: negarse sin más solo obligaría a un paso extra.
        val editor = Editor()
        val raiz = editor.filas().first().id
        editor.anadir(TipoPieza.CAJA.name, raiz)
        val caja = editor.filas()[1].id

        editor.anadir(TipoPieza.ESFERA.name, caja)

        val filas = editor.filas()
        assertEquals(3, filas.size)
        assertEquals(1, filas[2].profundidad, "la esfera debería colgar de la raíz, no de la caja")
    }

    @Test
    fun `cambiar un parametro no recompila el shader`() {
        val editor = Editor(ModelosDemo.soporte())
        val caja = editor.filas().first { it.tipo == "CAJA" }.id
        val huellaAntes = editor.huellaTopologica

        val recompila = editor.fijarParametro(caja, "anchura", 120f)

        assertFalse(recompila, "mover un parámetro debe costar solo un buffer de uniforms")
        assertEquals(huellaAntes, editor.huellaTopologica)
        assertEquals(120f, editor.parametrosDe(caja).first { it.clave == "anchura" }.valor)
    }

    @Test
    fun `anadir o eliminar si recompila`() {
        val editor = Editor(ModelosDemo.soporte())
        val raiz = editor.filas().first().id
        assertTrue(editor.anadir(TipoPieza.TORO.name, raiz))

        val toro = editor.filas().first { it.tipo == "TORO" }.id
        assertTrue(editor.eliminar(toro))
    }

    @Test
    fun `un parametro fuera de rango se recorta en lugar de atascarse`() {
        val editor = Editor()
        val raiz = editor.filas().first().id
        editor.anadir(TipoPieza.ESFERA.name, raiz)
        val esfera = editor.filas()[1].id

        editor.fijarParametro(esfera, "radio", 99_999f)

        val radio = editor.parametrosDe(esfera).first { it.clave == "radio" }
        assertEquals(radio.maximo, radio.valor)
        assertNull(editor.ultimoError, "recortar no es un error")
    }

    @Test
    fun `un parametro que no existe se rechaza sin tocar el documento`() {
        val editor = Editor(ModelosDemo.soporte())
        val caja = editor.filas().first { it.tipo == "CAJA" }.id
        val antes = editor.aJson()

        editor.fijarParametro(caja, "diametro", 10f)

        assertNotNull(editor.ultimoError)
        assertEquals(antes, editor.aJson(), "el documento debía quedar intacto")
    }

    @Test
    fun `la raiz no se puede eliminar`() {
        val editor = Editor(ModelosDemo.soporte())
        val raiz = editor.filas().first().id

        assertFalse(editor.eliminar(raiz))
        assertNotNull(editor.ultimoError)
        assertTrue(editor.filas().isNotEmpty())
    }

    @Test
    fun `deshacer devuelve el documento al estado anterior`() {
        val editor = Editor(ModelosDemo.soporte())
        val antes = editor.aJson()
        val raiz = editor.filas().first().id

        editor.anadir(TipoPieza.TORO.name, raiz)
        assertTrue(editor.puedeDeshacer)

        assertTrue(editor.deshacer())
        assertEquals(antes, editor.aJson())

        assertTrue(editor.rehacer())
        assertTrue(editor.filas().any { it.tipo == "TORO" })
    }

    @Test
    fun `arrastrar un deslizador deja un solo punto de deshacer`() {
        val editor = Editor(ModelosDemo.soporte())
        val caja = editor.filas().first { it.tipo == "CAJA" }.id
        val original = editor.parametrosDe(caja).first { it.clave == "anchura" }.valor

        // Simula el arrastre: muchos valores intermedios, una sola confirmación.
        editor.confirmarEdicionContinua()
        for (v in 90..120) editor.fijarParametro(caja, "anchura", v.toFloat())

        editor.deshacer()
        assertEquals(original, editor.parametrosDe(caja).first { it.clave == "anchura" }.valor)
    }

    @Test
    fun `envolver sustituye la pieza por la operacion que la contiene`() {
        val editor = Editor()
        val raiz = editor.filas().first().id
        editor.anadir(TipoPieza.CAJA.name, raiz)
        val caja = editor.filas()[1].id

        assertTrue(editor.envolver(caja, TipoPieza.VACIADO.name))

        val filas = editor.filas()
        assertEquals("VACIADO", filas[1].tipo)
        assertEquals("CAJA", filas[2].tipo)
        assertEquals(2, filas[2].profundidad)
    }

    @Test
    fun `desplazar reordena y por tanto cambia que se resta a que`() {
        val editor = Editor()
        val raiz = editor.filas().first().id
        editor.anadir(TipoPieza.CAJA.name, raiz)
        editor.anadir(TipoPieza.ESFERA.name, raiz)

        assertEquals(listOf("CAJA", "ESFERA"), editor.filas().drop(1).map { it.tipo })

        val esfera = editor.filas()[2].id
        editor.desplazar(esfera, haciaArriba = true)

        assertEquals(listOf("ESFERA", "CAJA"), editor.filas().drop(1).map { it.tipo })
    }

    @Test
    fun `ocultar una pieza la retira del solido`() {
        val editor = Editor(ModelosDemo.esferaSuelta())
        val esfera = editor.filas().first { it.tipo == "ESFERA" }.id
        assertFalse(editor.estaVacio)

        editor.fijarVisible(esfera, false)

        assertTrue(editor.estaVacio, "sin piezas visibles el documento no tiene material")
    }

    @Test
    fun `un documento vacio sigue produciendo un shader valido`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.estaVacio)
        assertTrue(editor.fuenteMsl.contains("fragment float4 yk_fragment"))
        assertEquals(editor.numeroDeUniforms, editor.uniforms().size)
    }

    @Test
    fun `el documento sobrevive a una ida y vuelta por JSON`() {
        val editor = Editor(ModelosDemo.soporte())
        val json = editor.aJson()

        val otro = Editor(Documento.vacio())
        assertTrue(otro.desdeJson(json))

        assertEquals(editor.filas().map { it.nombre }, otro.filas().map { it.nombre })
        assertEquals(editor.uniforms(), otro.uniforms())
        assertEquals(editor.huellaTopologica, otro.huellaTopologica)
    }

    @Test
    fun `un archivo corrupto se rechaza y deja el documento intacto`() {
        val editor = Editor(ModelosDemo.soporte())
        val antes = editor.aJson()

        assertFalse(editor.desdeJson("{ esto no es un documento }"))

        assertNotNull(editor.ultimoError)
        assertEquals(antes, editor.aJson())
    }

    /**
     * El fallo que se vio en la aplicación: elegir otro modelo y tocar un parámetro
     * devolvía al modelo de partida, porque los controles estaban cableados a él en
     * vez de actuar sobre el documento abierto.
     */
    @Test
    fun `tocar un parametro no revierte al modelo anterior`() {
        val editor = Editor(ModelosDemo.soporte())
        editor.reemplazarDocumento(ModelosDemo.esferaSuelta())

        val esfera = editor.filas().first { it.tipo == "ESFERA" }.id
        editor.fijarParametro(esfera, "radio", 40f)

        val tipos = editor.filas().map { it.tipo }
        assertTrue(tipos.contains("ESFERA"), "debería seguir abierta la esfera")
        assertFalse(tipos.contains("VACIADO"), "el soporte no debería haber vuelto")
        assertEquals(40f, editor.parametrosDe(esfera).first { it.clave == "radio" }.valor)
    }

    @Test
    fun `todos los modelos de ejemplo compilan y tienen volumen`() {
        for ((nombre, doc) in listOf(
            "soporte" to ModelosDemo.soporte(),
            "esfera" to ModelosDemo.esferaSuelta(),
            "rejilla" to ModelosDemo.rejilla(),
        )) {
            val editor = Editor(doc)
            assertFalse(editor.estaVacio, "$nombre no produjo material")
            assertEquals(editor.numeroDeUniforms, editor.uniforms().size, "$nombre desalinea uniforms")
            val min = editor.cotaMinima
            val max = editor.cotaMaxima
            assertTrue(max[0] > min[0] && max[1] > min[1] && max[2] > min[2], "$nombre tiene cotas vacías")
        }
    }
}
