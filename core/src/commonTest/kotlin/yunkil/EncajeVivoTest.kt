package yunkil

import yunkil.doc.ClaseDeAjuste
import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.FormatoYunkil
import yunkil.doc.medidaDe
import yunkil.doc.ProcedenciaDeMedida
import yunkil.doc.SentidoDeEncaje
import yunkil.doc.TipoPieza
import yunkil.ia.EjeNombrado
import yunkil.ia.cotasEnMundoDe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El encaje como **relación viva** del documento, no como un redimensionado de una vez.
 *
 * La operación `holgura` del DSL daba la cota buena y se olvidaba: cambiabas de perfil y
 * no se movía nada, arrastrabas el radio en el inspector y rompías el encaje en silencio,
 * y al abrir el archivo al día siguiente no había forma de saber que 19,6 no era un
 * capricho sino 20 medidos menos 0,2 de holgura.
 *
 * Aquí la pieza declara contra qué medida encaja y en qué sentido, y la cota se **deriva**.
 */
class EncajeVivoTest {

    private fun editorConCilindro(): Pair<Editor, String> {
        val editor = Editor(Documento.vacio())
        editor.anadir(TipoPieza.CILINDRO.name, editor.seleccionado)
        val id = assertNotNull(editor.seleccionado, "no se creó el cilindro")
        return editor to id
    }

    private fun anchoDe(editor: Editor, id: String): Float =
        assertNotNull(editor.documentoActual.cotasEnMundoDe(id), "no se puede medir $id").size.x

    @Test
    fun `un tapon declarado contra un agujero de 20 sale a 19,6`() {
        val (editor, id) = editorConCilindro()
        val medida = assertNotNull(
            editor.declararMedida("agujero del tubo", 20f, ProcedenciaDeMedida.CALIBRE),
            "no se pudo declarar la medida: ${editor.ultimoError}",
        )

        val ok = editor.declararEncaje(
            piezaId = id,
            medidaId = medida,
            eje = EjeNombrado.X,
            sentido = SentidoDeEncaje.ENTRA,
            clase = ClaseDeAjuste.DESLIZANTE,
        )
        assertTrue(ok, "no se declaró el encaje: ${editor.ultimoError}")

        // Perfil de partida: Bambu P1S · PLA, holgura de encaje 0,2 mm. Deslizante es
        // el factor 1, así que se descuenta una holgura por cada lado: 20 − 2 × 0,2.
        val ancho = anchoDe(editor, id)
        assertTrue(abs(ancho - 19.6f) < 0.01f, "esperaba 19,6 mm y mide $ancho")
    }

    /** Deja un cilindro atado a una medida y devuelve editor, pieza y medida. */
    private fun taponDe(
        nominal: Float,
        clase: ClaseDeAjuste = ClaseDeAjuste.DESLIZANTE,
        sentido: SentidoDeEncaje = SentidoDeEncaje.ENTRA,
    ): Triple<Editor, String, String> {
        val (editor, id) = editorConCilindro()
        val medida = assertNotNull(
            editor.declararMedida("agujero del tubo", nominal, ProcedenciaDeMedida.CALIBRE),
        )
        assertTrue(
            editor.declararEncaje(id, medida, EjeNombrado.X, sentido, clase),
            "no se declaró el encaje: ${editor.ultimoError}",
        )
        return Triple(editor, id, medida)
    }

    @Test
    fun `corregir la medida mueve la pieza sin tocar la pieza`() {
        val (editor, id, medida) = taponDe(20f)
        assertTrue(abs(anchoDe(editor, id) - 19.6f) < 0.01f)

        // El tubo no medía 20: lo hemos vuelto a medir y son 25.
        assertTrue(editor.fijarMedida(medida, 25f), "no se aceptó la medida: ${editor.ultimoError}")

        val ancho = anchoDe(editor, id)
        assertTrue(abs(ancho - 24.6f) < 0.01f, "esperaba 24,6 mm y mide $ancho")
    }

    @Test
    fun `cambiar de perfil vuelve a derivar la cota`() {
        val (editor, id, _) = taponDe(20f)
        assertTrue(abs(anchoDe(editor, id) - 19.6f) < 0.01f)

        // La genérica de 0,6 tabula 0,3 de holgura en vez de 0,2: 20 − 2 × 0,3.
        assertTrue(
            editor.usarPerfil("Genérica · 0,6 · pieza funcional"),
            "no se cambió de perfil: ${editor.ultimoError}",
        )

        val ancho = anchoDe(editor, id)
        assertTrue(abs(ancho - 19.4f) < 0.01f, "esperaba 19,4 mm y mide $ancho")
    }

    @Test
    fun `un pasador a presion sale a la medida clavada`() {
        // A presión es el factor cero: la pieza mide lo que mide el agujero, y es el
        // material el que cede. Si diera lo mismo que deslizante, la clase no diría nada.
        val (editor, id, _) = taponDe(20f, ClaseDeAjuste.PRESION)
        val ancho = anchoDe(editor, id)
        assertTrue(abs(ancho - 20f) < 0.01f, "esperaba 20 mm clavados y mide $ancho")
    }

    @Test
    fun `un paso de cable libre deja el doble de aire que uno deslizante`() {
        val (deslizante, idD, _) = taponDe(20f, ClaseDeAjuste.DESLIZANTE)
        val (libre, idL, _) = taponDe(20f, ClaseDeAjuste.LIBRE)
        val huecoDeslizante = 20f - anchoDe(deslizante, idD)
        val huecoLibre = 20f - anchoDe(libre, idL)
        assertTrue(
            abs(huecoLibre - 2f * huecoDeslizante) < 0.01f,
            "deslizante deja $huecoDeslizante y libre $huecoLibre",
        )
    }

    @Test
    fun `un hueco que recibe un eje de 8 sale por encima de 8`() {
        val (editor, id, _) = taponDe(8f, ClaseDeAjuste.DESLIZANTE, SentidoDeEncaje.RECIBE)
        val ancho = anchoDe(editor, id)
        assertTrue(abs(ancho - 8.4f) < 0.01f, "esperaba 8,4 mm y mide $ancho")
    }

    @Test
    fun `soltar el encaje deja la cota donde estaba y la desata`() {
        val (editor, id, medida) = taponDe(20f)
        val antes = anchoDe(editor, id)

        assertTrue(editor.soltarEncaje(id), "no se soltó: ${editor.ultimoError}")
        assertNull(editor.encajeDe(id), "el encaje sigue puesto")
        assertTrue(abs(anchoDe(editor, id) - antes) < 0.001f, "soltar movió la pieza")

        // Y desatada de verdad: corregir la medida ya no la arrastra.
        assertTrue(editor.fijarMedida(medida, 40f), editor.ultimoError ?: "")
        assertTrue(abs(anchoDe(editor, id) - antes) < 0.001f, "sigue atada a la medida")
    }

    @Test
    fun `el encaje se lee con su medida, su procedencia y su holgura`() {
        val (editor, id, _) = taponDe(20f)
        val texto = assertNotNull(editor.descripcionDeEncaje(id), "no hay descripción")
        // Sin esto, 19,6 es un número raro en un inspector. Con esto es una cota justificada.
        for (trozo in listOf("entra en", "agujero del tubo", "20", "con calibre", "0.2", "deslizante")) {
            assertTrue(trozo in texto, "falta «$trozo» en «$texto»")
        }
    }

    // ---------------------------------------------------------------- desde la IA

    @Test
    fun `la operacion holgura del DSL deja una relacion, no un tamano`() {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"resumen":"Tapón","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CILINDRO","alias":"t","nombre":"Tapón",
               "parametros":{"radio":15,"altura":10}},
              {"op":"holgura","objetivo":"t","eje":"X","medida":20,"encaje":"ENTRA",
               "ajuste":"AJUSTADO","nombreDeLaMedida":"boca del tubo"}
            ]}
            """.trimIndent(),
        )
        val plan = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        val resultado = editor.aplicarPlan(plan, null)
        assertTrue(resultado.exito, "no se aplicó: ${resultado.resumen}")

        val id = assertNotNull(editor.idPorNombre("Tapón"))
        // Ajustado es medio factor: 20 − 2 × 0,1.
        assertTrue(abs(anchoDe(editor, id) - 19.8f) < 0.01f, "mide ${anchoDe(editor, id)}")

        // Y lo que importa: quedó **atado**, con la medida a la vista y con nombre.
        val encaje = assertNotNull(editor.encajeDe(id), "el plan redimensionó y se olvidó")
        val medida = assertNotNull(editor.medidas().firstOrNull { it.id == encaje.medida })
        assertEquals("boca del tubo", medida.nombre)
        assertEquals(20f, medida.valor)
        assertEquals(ClaseDeAjuste.AJUSTADO, encaje.clase)

        assertTrue(editor.usarPerfil("Genérica · 0,6 · pieza funcional"), editor.ultimoError ?: "")
        assertTrue(abs(anchoDe(editor, id) - 19.7f) < 0.01f, "no siguió al perfil nuevo")
    }

    // ------------------------------------------------------- la cota gobernada

    @Test
    fun `la cota gobernada no se edita a mano`() {
        val (editor, id, _) = taponDe(20f)

        // El radio es lo que mide el tapón en X, y en X manda el encaje. Dejar pasar el
        // arrastre y recolocar por detrás sería peor que negarse: el deslizador haría
        // una cosa distinta de la que enseña.
        assertFalse(editor.fijarParametro(id, "radio", 20f), "dejó tocar la cota del encaje")
        val motivo = assertNotNull(editor.ultimoError)
        assertTrue("encaj" in motivo, "el motivo no habla del encaje: $motivo")
        assertTrue("suelt" in motivo.lowercase(), "no dice cómo salir: $motivo")
        assertTrue(abs(anchoDe(editor, id) - 19.6f) < 0.01f, "y encima lo movió")
    }

    @Test
    fun `la interfaz puede saber que cota esta gobernada antes de ofrecerla`() {
        val (editor, id, _) = taponDe(20f)
        // Sin esto, el inspector enseña un deslizador que se rechaza al soltarlo. Un
        // control que no hace nada es peor que un control que no está.
        assertTrue(editor.gobernadaPorEncaje(id, "radio"), "no reconoce la cota gobernada")
        assertFalse(editor.gobernadaPorEncaje(id, "altura"), "bloquea una cota libre")

        val (suelto, idSuelto) = editorConCilindro()
        assertFalse(suelto.gobernadaPorEncaje(idSuelto, "radio"), "bloquea una pieza sin encaje")
    }

    @Test
    fun `las cotas que no gobierna el encaje se siguen editando`() {
        val (editor, id, _) = taponDe(20f)
        // La altura de un cilindro no toca su extensión en X: el encaje no tiene nada
        // que decir. Un candado que bloquee la pieza entera sobraría.
        // (El booleano de `fijarParametro` dice si hay que recompilar, no si salió bien.)
        editor.fijarParametro(id, "altura", 30f)
        assertNull(editor.ultimoError, "rechazó una cota que el encaje no gobierna")
        assertTrue(abs(anchoDe(editor, id) - 19.6f) < 0.01f, "cambiar la altura movió el encaje")
        val alto = assertNotNull(editor.documentoActual.cotasEnMundoDe(id)).size.y
        assertTrue(alto > 0f, "la altura no llegó a aplicarse")
    }

    // ------------------------------------------------------------ persistencia

    @Test
    fun `el encaje y su medida sobreviven a guardar y abrir`() {
        val (editor, id, _) = taponDe(20f)

        val abierto = FormatoYunkil.decodificar(FormatoYunkil.codificar(editor.documentoActual))

        val encaje = assertNotNull(abierto.buscar(id)?.encajes?.firstOrNull(), "el encaje no sobrevivió")
        val medida = assertNotNull(abierto.medidaDe(encaje.medida), "la medida no sobrevivió")
        assertEquals(20f, medida.valor)
        assertEquals(ProcedenciaDeMedida.CALIBRE, medida.procedencia)
        assertEquals(SentidoDeEncaje.ENTRA, encaje.sentido)
        assertEquals(ClaseDeAjuste.DESLIZANTE, encaje.clase)
    }

    @Test
    fun `un encaje contra una medida que no esta se rechaza al abrir`() {
        val (editor, _, _) = taponDe(20f)
        // Un archivo al que le falta la medida: el encaje sigue escrito y ya no se puede
        // justificar ni una sola cota de la pieza. Abrirlo callando dejaría una pieza que
        // dice encajar con algo que nadie sabe cuánto mide.
        val texto = FormatoYunkil.codificar(editor.documentoActual.copy(medidas = emptyList()))

        val fallo = assertFails { FormatoYunkil.decodificar(texto) }
        assertTrue(
            "medida" in (fallo.message ?: ""),
            "el motivo no nombra la medida que falta: ${fallo.message}",
        )
    }

    @Test
    fun `dos medidas con el mismo identificador se rechazan al abrir`() {
        val (editor, _, medida) = taponDe(20f)
        val doc = editor.documentoActual
        val repetida = assertNotNull(doc.medidaDe(medida)).copy(nombre = "otra cosa", valor = 33f)
        val texto = FormatoYunkil.codificar(doc.copy(medidas = doc.medidas + repetida))

        // Con dos, `medidaDe` acierta a la primera que encuentre y la pieza encajaría
        // contra una medida o contra la otra según el orden del archivo.
        val fallo = assertFails { FormatoYunkil.decodificar(texto) }
        assertTrue("repetid" in (fallo.message ?: ""), "motivo raro: ${fallo.message}")
    }

    @Test
    fun `una medida que dejaria la pieza en nada se rechaza y no cambia el documento`() {
        val (editor, id, medida) = taponDe(20f)
        val antes = anchoDe(editor, id)

        // 0,3 mm de agujero menos 0,4 de holgura es una pieza de cota negativa.
        assertFalse(editor.fijarMedida(medida, 0.3f), "aceptó una medida imposible")
        assertTrue(abs(anchoDe(editor, id) - antes) < 0.001f, "la pieza se movió pese al rechazo")
        assertEquals(20f, assertNotNull(editor.medidas().firstOrNull { it.id == medida }).valor)
        assertNotNull(editor.ultimoError, "rechazó sin decir por qué")
    }
}
