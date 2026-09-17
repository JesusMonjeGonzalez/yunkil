package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.organico.ExplicacionOrganica
import yunkil.organico.MotorOrganico
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Aceptar media figura tiene que dejar exactamente la media que se aceptó.
 *
 * Es el mismo riesgo que en el plan paramétrico, con otra cara: aquí lo que se queda
 * colgando no es una operación que se omite en silencio, es una mano unida a un brazo
 * que ya no existe. O flota, o el compilador la rechaza a destiempo. Se decide antes.
 */
class AceptarParcialOrganicoTest {

    /** Cuerpo, cabeza, brazo unido al cuerpo, mano unida al brazo y una cola. */
    private val bicho = """
        {"esquema":"yunkil.organico.v1","nombre":"Bicho","unidades":"mm","fusionMm":2,
         "partes":[
          {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,10,0],"b":[0,34,0],"radio":10},
          {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,44,0],"radio":9,"unidoA":"cuerpo"},
          {"id":"brazo","rol":"EXTREMIDAD","forma":"CAPSULA","a":[8,28,0],"b":[20,20,0],"radio":3,"unidoA":"cuerpo"},
          {"id":"mano","rol":"EXTREMIDAD","forma":"ESFERA","centro":[22,18,0],"radio":4,"unidoA":"brazo"},
          {"id":"cola","rol":"COLA","forma":"CAPSULA","a":[0,12,-8],"b":[0,6,-20],"radio":3,"unidoA":"cuerpo"}
         ]}
    """.trimIndent()

    private fun contrato(): String = assertNotNull(MotorOrganico.interpretar(bicho).contratoCanonico)

    private fun idsDe(canonico: String): List<String> =
        MotorOrganico.explicar(canonico).map { it.id }

    @Test
    fun `la figura se cuenta parte por parte con lo que cuelga de cada una`() {
        val lineas = MotorOrganico.explicar(contrato())
        assertEquals(5, lineas.size)
        assertEquals(listOf("cuerpo", "cabeza", "brazo", "mano", "cola"), lineas.map { it.id })

        assertContains(lineas[0].texto, "Cuerpo")
        assertContains(lineas[0].texto, "cápsula")
        assertContains(lineas[1].texto, "esfera de 9 mm de radio")
        assertEquals(emptyList(), lineas[0].depende, "el cuerpo no cuelga de nada")
        assertEquals(listOf(2), lineas[3].depende, "la mano cuelga del brazo")

        // Sin cuerpo y sin cabeza no hay figura a medias: hay contrato inválido.
        assertTrue(lineas[0].esencial && lineas[1].esencial)
        assertFalse(lineas[2].esencial || lineas[3].esencial || lineas[4].esencial)
    }

    @Test
    fun `desmarcar el brazo se lleva la mano y marcar la mano trae el brazo`() {
        val c = contrato()
        // Todo menos el brazo: la mano no puede quedarse unida a lo que no existe.
        val podada = MotorOrganico.podarSeleccion(c, listOf(0, 1, 3, 4))
        assertEquals(listOf(0, 1, 4), podada)

        // Y al revés: marcar la mano pide el brazo del que cuelga.
        assertEquals(listOf(0, 1, 2, 3), MotorOrganico.completarSeleccion(c, listOf(3)))

        // Las esenciales entran siempre, se marquen o no.
        assertEquals(listOf(0, 1, 4), MotorOrganico.podarSeleccion(c, listOf(4)))
    }

    @Test
    fun `el contrato podado conserva solo lo marcado y sigue siendo valido`() {
        val c = contrato()
        val sinBrazo = assertNotNull(MotorOrganico.conPartes(c, listOf(0, 1, 4)).contratoCanonico)
        assertEquals(listOf("cuerpo", "cabeza", "cola"), idsDe(sinBrazo))

        // Podado o no, lo que sale tiene que compilar: es la misma figura, con menos.
        assertNotNull(MotorOrganico.nodoDeContrato(sinBrazo))

        // Aceptarlo todo devuelve el contrato tal cual, sin volver a serializar.
        assertEquals(c, MotorOrganico.conPartes(c, (0..4).toList()).contratoCanonico)
    }

    @Test
    fun `una seleccion que deja la figura suelta se rechaza con su motivo`() {
        // La mano marcada sin el brazo ya no ocurre —`podar` la quita—, pero una
        // figura que se queda en cuerpo y cabeza sí llega hasta el validador: el
        // contrato exige tres partes, y ese motivo tiene que llegar a la interfaz.
        val soloEsenciales = MotorOrganico.conPartes(contrato(), listOf(0, 1))
        assertFalse(soloEsenciales.aceptado)
        assertNotNull(soloEsenciales.motivo)
        assertContains(soloEsenciales.motivo!!, "partes")
        assertNull(soloEsenciales.contratoCanonico, "un rechazo no puede devolver figura")
    }

    @Test
    fun `anadir solo las partes marcadas es una edicion deshacible en un paso`() {
        val editor = Editor(Documento.vacio())
        val version = editor.versionDocumento
        val c = contrato()

        assertTrue(editor.anadirParteDeEscultura(c, listOf(0, 1, 4), version), editor.ultimoError)
        val id = assertNotNull(editor.seleccionado)
        val guardado = assertNotNull(editor.contratoDeEscultura(id))
        assertEquals(
            listOf("cuerpo", "cabeza", "cola"),
            idsDe(guardado),
            "lo que queda en el documento es lo que se marcó, no la propuesta entera",
        )

        assertTrue(editor.deshacer())
        assertTrue(editor.estaVacio, "aceptar media figura es un punto de deshacer, no dos")
    }

    @Test
    fun `una seleccion que no compila no toca el documento y deja el motivo`() {
        val editor = Editor(Documento.vacio())
        val version = editor.versionDocumento
        assertFalse(editor.anadirParteDeEscultura(contrato(), listOf(0, 1), version))
        assertTrue(editor.estaVacio, "el rechazo no puede dejar nada a medias")
        assertNotNull(editor.ultimoError)
    }

    @Test
    fun `anadir parte en version vencida se rechaza antes de podar`() {
        val editor = Editor(Documento.vacio())
        val versionDeLaPropuesta = editor.versionDocumento
        editor.anadirEscultura(contrato())

        assertFalse(editor.anadirParteDeEscultura(contrato(), listOf(0, 1, 4), versionDeLaPropuesta))
        assertContains(editor.ultimoError ?: "", "documento cambió")
    }

    @Test
    fun `el fantasma ensena solo las partes marcadas`() {
        val editor = Editor(Documento.vacio())
        val c = contrato()

        editor.previsualizarEscultura(c, listOf(0, 1, 2, 3, 4))
        assertTrue(editor.hayFantasma)
        val enteras = editor.uniforms().size

        editor.previsualizarEscultura(c, listOf(0, 1, 4))
        assertTrue(editor.hayFantasma)
        assertTrue(
            editor.uniforms().size < enteras,
            "desmarcar partes tiene que cambiar lo que se ve, o el fantasma miente al decidir",
        )

        // Una selección que no compila —aquí, menos de las tres partes que exige el
        // contrato— quita el fantasma en vez de dejar puesto el anterior.
        editor.previsualizarEscultura(c, listOf(0, 1))
        assertFalse(editor.hayFantasma)
    }

    @Test
    fun `podar sobre un contrato ilegible no inventa una figura`() {
        assertEquals(emptyList(), MotorOrganico.explicar("{no soy json"))
        assertEquals(emptyList(), MotorOrganico.podarSeleccion("{no soy json", listOf(0)))
        assertFalse(MotorOrganico.conPartes("{no soy json", listOf(0)).aceptado)
    }

    @Test
    fun `explicar no toca el contrato ni el documento`() {
        val editor = Editor(Documento.vacio())
        val c = contrato()
        val version = editor.versionDocumento

        val primera = editor.explicarContrato(c)
        val segunda = editor.explicarContrato(c)
        assertEquals(primera.map { it.texto }, segunda.map { it.texto }, "es una función pura")
        assertEquals(version, editor.versionDocumento)
        assertTrue(editor.estaVacio)
        assertContains(ExplicacionOrganica.texto(assertNotNull(contratoLeido(c))), "cola")
    }

    /** El contrato como objeto, para probar la explicación sin pasar por el puente. */
    private fun contratoLeido(canonico: String) = MotorOrganico.leer(canonico)
}
