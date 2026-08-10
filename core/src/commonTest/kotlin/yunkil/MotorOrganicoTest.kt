package yunkil

import yunkil.kernel.Vec3
import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.compilar
import yunkil.organico.MotorOrganico
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotorOrganicoTest {
    private val figura = """
        {"esquema":"yunkil.organico.v1","nombre":"Mascota","unidades":"mm","fusionMm":2,
         "partes":[
          {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,5,0],"b":[0,30,0],"radio":12},
          {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,42,0],"radio":14,"unidoA":"cuerpo"},
          {"id":"ojo","rol":"OJO","forma":"ESFERA","centro":[0,45,-12],"radio":2,"unidoA":"cabeza"}
         ]}
    """.trimIndent()

    @Test
    fun `un contrato organico estricto compila a un unico campo suave`() {
        val interpretado = MotorOrganico.interpretar(figura)
        assertTrue(interpretado.aceptado, interpretado.motivo)
        val canonico = assertNotNull(interpretado.contratoCanonico)
        val contrato = kotlinx.serialization.json.Json.decodeFromString<yunkil.organico.ContratoOrganico>(canonico)
        val nodo = MotorOrganico.compilar(contrato)

        assertTrue(nodo.evaluar(Vec3(0f, 15f, 0f)) < 0f)
        assertTrue(nodo.evaluar(Vec3(0f, 42f, 0f)) < 0f)
        assertTrue(nodo.evaluar(Vec3(100f, 100f, 100f)) > 0f)
    }

    @Test
    fun `el contrato rechaza capacidad ejecutable y partes flotantes`() {
        assertFalse(MotorOrganico.interpretar(figura.dropLast(1) + ",\"script\":\"x\"}").aceptado)
        val flotante = figura.replace(",\"unidoA\":\"cabeza\"", "")
        val resultado = MotorOrganico.interpretar(flotante)
        assertFalse(resultado.aceptado)
        assertTrue(resultado.motivo?.contains("flotando") == true)
    }

    @Test
    fun `la escultura persiste nativa y admite brochas simetricas con deshacer`() {
        val contrato = assertNotNull(MotorOrganico.interpretar(figura).contratoCanonico)
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadirEscultura(contrato))
        val id = assertNotNull(editor.seleccionado)
        assertTrue(editor.esEscultura(id))
        editor.confirmarEdicionContinua()
        editor.fijarFusionDeEscultura(id, 3.5f)
        assertTrue(editor.ultimoError == null)
        assertEquals(3.5f, editor.fusionDeEscultura(id))
        assertTrue(editor.deshacer())
        assertEquals(2f, editor.fusionDeEscultura(id))
        val antes = assertNotNull(editor.documentoActual.compilar())
        assertTrue(antes.evaluar(Vec3(15f, 15f, 0f)) > 0f)

        assertTrue(editor.aplicarBrochaOrganica(id, "AGREGAR", 12f, 15f, 0f, 4f, true))
        val agregado = assertNotNull(editor.documentoActual.compilar())
        assertTrue(agregado.evaluar(Vec3(15f, 15f, 0f)) < 0f)
        assertTrue(agregado.evaluar(Vec3(-15f, 15f, 0f)) < 0f)
        assertTrue(editor.deshacer())
        assertTrue(assertNotNull(editor.documentoActual.compilar()).evaluar(Vec3(15f, 15f, 0f)) > 0f)

        assertTrue(editor.aplicarBrochaOrganica(id, "QUITAR", 0f, 15f, -12f, 3f, false))
        assertTrue(assertNotNull(editor.documentoActual.compilar()).evaluar(Vec3(0f, 15f, -12f)) > 0f)

        val guardado = editor.aJson()
        val reabierto = Editor(Documento.vacio())
        assertTrue(reabierto.desdeJson(guardado))
        assertTrue(assertNotNull(reabierto.documentoActual.compilar()).evaluar(Vec3(0f, 15f, 0f)) < 0f)
    }
}
