package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.organico.MotorOrganico
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las garantías del flujo orgánico de IA, ahora iguales a las del paramétrico:
 * fantasma sin tocar el documento, aplicación ligada a la revisión que vio la
 * IA y corrección de vuelta al modelo.
 */
class FlujoOrganicoIATest {

    private val mascota = """
        {"esquema":"yunkil.organico.v1","nombre":"Mascota","unidades":"mm","fusionMm":2,
         "partes":[
          {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,5,0],"b":[0,30,0],"radio":12},
          {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,42,0],"radio":14,"unidoA":"cuerpo"},
          {"id":"ojo","rol":"OJO","forma":"ESFERA","centro":[0,45,-12],"radio":2,"unidoA":"cabeza"}
         ]}
    """.trimIndent()

    private val variante = mascota.replace("Mascota", "Mascota II")

    private fun contratoValido(): String =
        assertNotNull(MotorOrganico.interpretar(mascota).contratoCanonico)

    @Test
    fun `el fantasma organico se pone y se quita sin tocar el documento`() {
        val editor = Editor(Documento.vacio())
        val version = editor.versionDocumento
        val uniformsSinFantasma = editor.uniforms().size

        assertTrue(editor.previsualizarEscultura(contratoValido()), editor.ultimoError)
        assertTrue(editor.hayFantasma)
        assertEquals(version, editor.versionDocumento, "mirar la propuesta no puede tocar el documento")
        assertTrue(editor.uniforms().size > uniformsSinFantasma, "el fantasma debe traer sus uniforms")

        assertTrue(editor.previsualizarEscultura(null))
        assertFalse(editor.hayFantasma)
        assertEquals(uniformsSinFantasma, editor.uniforms().size)
        // Quitar el fantasma tampoco es una edición.
        assertEquals(version, editor.versionDocumento)
    }

    @Test
    fun `un contrato que no compila quita el fantasma en vez de dejar el anterior`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.previsualizarEscultura(contratoValido()))
        assertTrue(editor.hayFantasma)

        val roto = mascota.replace("fusionMm\":2", "fusionMm\":99")
        editor.previsualizarEscultura(roto)
        assertFalse(
            editor.hayFantasma,
            "enseñar la figura anterior cuando la propuesta ya no es esa es mentir en el momento de decidir",
        )
    }

    @Test
    fun `anadir en version vencida se rechaza y no toca el documento`() {
        val editor = Editor(Documento.vacio())
        val versionDeLaPropuesta = editor.versionDocumento

        // Edición ajena a la propuesta: la revisión que vio la IA ya no es la actual.
        editor.anadirEscultura(contratoValido())
        assertTrue(!editor.estaVacio)
        val filasAntes = editor.filas().size

        val contratoSegundo = assertNotNull(MotorOrganico.interpretar(variante).contratoCanonico)
        assertFalse(editor.anadirEsculturaEnVersion(contratoSegundo, versionDeLaPropuesta))
        assertTrue(
            editor.ultimoError?.contains("documento cambió") == true,
            "el motivo debe ser el reconocible por la aplicación: ${editor.ultimoError}",
        )
        assertEquals(filasAntes, editor.filas().size, "el rechazo no puede dejar nada a medias")
    }

    @Test
    fun `anadir en version vigente aplica como una sola transaccion deshacible`() {
        val editor = Editor(Documento.vacio())
        val version = editor.versionDocumento

        assertTrue(editor.anadirEsculturaEnVersion(contratoValido(), version), editor.ultimoError)
        assertTrue(!editor.estaVacio)

        assertTrue(editor.deshacer())
        assertTrue(editor.estaVacio, "la aceptación orgánica es un punto de deshacer, no dos")
    }

    @Test
    fun `reemplazar en version vencida deja la escultura como estaba`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadirEscultura(contratoValido()), editor.ultimoError)
        val seleccionada = assertNotNull(editor.seleccionado)
        val contratoOriginal = assertNotNull(editor.contratoDeEscultura(seleccionada))
        val versionDeLaPropuesta = editor.versionDocumento

        // Una edición manual después de que la IA viera el documento. Renombrar
        // devuelve si hay que recompilar el shader, no si funcionó; lo que importa
        // aquí es que la revisión del documento avanzó.
        val versionTrasEdicion = editor.versionDocumento + 1
        editor.renombrar(seleccionada, "Renombrada")
        assertEquals(versionTrasEdicion, editor.versionDocumento)

        val contratoNuevo = assertNotNull(MotorOrganico.interpretar(variante).contratoCanonico)
        assertFalse(editor.reemplazarEsculturaEnVersion(seleccionada, contratoNuevo, versionDeLaPropuesta))
        assertEquals(contratoOriginal, editor.contratoDeEscultura(seleccionada))

        // El reemplazo aplica aunque el booleano diga que no hizo falta recompilar el
        // shader —la variante no cambia topología—: el fallo real se mira en
        // `ultimoError`, que es donde también lo mira la aplicación.
        editor.reemplazarEsculturaEnVersion(seleccionada, contratoNuevo, editor.versionDocumento)
        assertNull(editor.ultimoError)
        assertEquals(contratoNuevo, editor.contratoDeEscultura(seleccionada))
    }

    @Test
    fun `la correccion organica nombra el motivo y devuelve al esquema`() {
        val respuesta = """{"esquema":"yunkil.organico.v1","nombre":"X"}"""
        val rechazo = MotorOrganico.interpretar(respuesta)
        assertFalse(rechazo.aceptado)
        val mensaje = MotorOrganico.correccionParaModelo(
            motivo = rechazo.motivo ?: "sin motivo",
            respuestaAnterior = respuesta,
        )
        assertTrue(rechazo.motivo!! in mensaje, "el modelo tiene que saber qué le falló")
        assertTrue("COMPLETO" in mensaje, "parches y diferencias no valen: el contrato va entero")
        assertTrue(respuesta in mensaje, "la respuesta rechazada vuelve recortada pero vuelve")
    }

    @Test
    fun `revisar el contrato mide la figura sin tocar el documento del usuario`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CAJA", null), editor.ultimoError)
        val version = editor.versionDocumento
        val filas = editor.filas().size

        val revision = editor.revisarContrato(contratoValido())
        assertTrue(revision.aceptable, "una mascota sana no tiene nada que corregir: ${revision.motivos}")
        assertEquals(version, editor.versionDocumento, "revisar no gasta un punto de deshacer")
        assertEquals(filas, editor.filas().size, "la figura del banco no puede aparecer en el árbol")
    }

    @Test
    fun `una parte demasiado fina vuelve al modelo como motivo medido`() {
        val palillo = """
            {"esquema":"yunkil.organico.v1","nombre":"Palillo","unidades":"mm","fusionMm":1,
             "partes":[
              {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,2,0],"b":[0,40,0],"radio":0.4},
              {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,41,0],"radio":1.5,"unidoA":"cuerpo"},
              {"id":"ojo","rol":"OJO","forma":"ESFERA","centro":[0,41.5,-1.2],"radio":0.5,"unidoA":"cabeza"}
             ]}
        """.trimIndent()
        val contrato = assertNotNull(MotorOrganico.interpretar(palillo).contratoCanonico)

        // El validador del contrato lo acepta: el esquema está bien, las partes se
        // tocan y el gradiente es sano. Es exactamente el fallo que solo aparece
        // midiendo la geometría compilada.
        val revision = Editor(Documento.vacio()).revisarContrato(contrato)
        assertFalse(revision.aceptable, "una figura de 0,8 mm de grueso no se imprime")

        val mensaje = MotorOrganico.revisionParaModelo(revision.motivos, palillo)
        assertTrue(revision.motivos.first() in mensaje)
        assertTrue("radio" in mensaje, "al modelo se le dice cómo engordar, no que escale")
    }

    @Test
    fun `la figura que no mide lo pedido se corrige antes que la fabricacion`() {
        val editor = Editor(Documento.vacio())
        val revision = editor.revisarContrato(contratoValido(), peticion = "una mascota de 200 mm de alto")

        assertFalse(revision.aceptable, "la petición decía una altura y la figura no la tiene")
        assertEquals(1, revision.motivos.size, "primero el tamaño; lo demás puede desaparecer al corregirlo")
        val motivo = revision.motivos.first()
        assertTrue("200" in motivo, "el modelo necesita saber cuánto se pidió: $motivo")
        assertTrue("mide" in motivo, "y cuánto mide de verdad: $motivo")

        // Sin cota en la petición no hay nada que comprobar y no se inventa un fallo.
        assertTrue(editor.revisarContrato(contratoValido(), peticion = "una mascota").aceptable)
    }
}
