package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.organico.MotorOrganico
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CadenaOrganicaTest {
    @Test
    fun `contrato organico produce stl durable e importable`() {
        val respuesta = """
            {"esquema":"yunkil.organico.v1","nombre":"Mascota","unidades":"mm","fusionMm":2,
             "partes":[
              {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,5,0],"b":[0,30,0],"radio":12},
              {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,42,0],"radio":14,"unidoA":"cuerpo"},
              {"id":"ojo","rol":"OJO","forma":"ESFERA","centro":[0,45,-12],"radio":2,"unidoA":"cabeza"}
             ]}
        """.trimIndent()
        val contrato = assertNotNull(MotorOrganico.interpretar(respuesta).contratoCanonico)
        val ruta = Files.createTempFile("yunkil-organico-", ".stl")

        val generado = MotorOrganico.generarStl(contrato, ruta.toString(), 1.2f)
        assertTrue(generado.exito, generado.resumen)
        assertTrue(Files.size(ruta) > 84)

        val editor = Editor(Documento.vacio())
        assertTrue(editor.importarMalla(ruta.toString(), 1.2f, null), editor.ultimoError)
        assertTrue(!editor.estaVacio)
    }

    @Test
    fun `una figura con cola de curva llega a un stl certificado`() {
        // Que el nodo evalúe bien no basta: la cola pasa por el contorneado, el
        // certificado de cierre y el escritor de STL. Una punta que se afila hasta 0,4 mm
        // es justo la geometría que un mallador deja abierta si el campo miente cerca de
        // la superficie, y el certificado no dejaría escribir el archivo.
        //
        // Se malla a 0,5 mm y no a 0,8 a propósito. A 0,8 el certificado rechaza esta
        // figura por agujeros, y **no es cosa de la curva**: la misma cola hecha con la
        // cadena de cápsulas de siempre falla exactamente igual, con el mismo número de
        // triángulos y en las mismas resoluciones (0,8 y 0,4 sí; 0,5 y 0,3 no). Es un
        // fallo del contorneado con codos cerrados, anterior a este nodo, y está anotado
        // en los límites conocidos del README. Fijarlo aquí a 0,5 mide lo que esta prueba
        // dice medir —que la cola llega entera al STL— sin tapar aquello.
        val respuesta = """
            {"esquema":"yunkil.organico.v1","nombre":"Gato","unidades":"mm","fusionMm":2,
             "partes":[
              {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,12,0],"b":[0,34,0],"radio":12},
              {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,46,0],"radio":11,"unidoA":"cuerpo"},
              {"id":"cola","rol":"COLA","forma":"CURVA",
               "puntos":[0,26,6, 8,34,16, 6,44,22, -4,48,16],"radios":[5,3.5,2,0.4],
               "unidoA":"cuerpo"}
             ]}
        """.trimIndent()
        val contrato = assertNotNull(MotorOrganico.interpretar(respuesta).contratoCanonico)
        val ruta = Files.createTempFile("yunkil-curva-", ".stl")

        val generado = MotorOrganico.generarStl(contrato, ruta.toString(), 0.5f)
        assertTrue(generado.exito, generado.resumen)
        assertTrue(Files.size(ruta) > 84)

        val editor = Editor(Documento.vacio())
        assertTrue(editor.importarMalla(ruta.toString(), 0.5f, null), editor.ultimoError)
        // La cola se aleja bastante del cuerpo: si no hubiera salido en la malla, las
        // cotas de lo importado se quedarían en el bulto del cuerpo y la cabeza.
        val cotas = assertNotNull(editor.cotasDelModelo())
        assertTrue(cotas.max.z > 18f, "la cola no llegó a la malla: cotas $cotas")
    }
}
