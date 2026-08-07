package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * La cadena entera, de la frase del usuario al archivo que se manda a imprimir.
 *
 * Los tests del núcleo cubren cada eslabón por separado, y aun así el que importa es
 * este: que un plan como los que emite un modelo acabe en un STL estanco. Un plan
 * válido que produce una malla con agujeros no vale para nada, y esa combinación no
 * la detecta ningún test de unidad porque el fallo vive justo en la costura entre el
 * campo y el mallado.
 *
 * Vive en `jvmTest` y no en `commonTest` porque escribe en disco de verdad. Exportar
 * a un archivo que luego no se comprueba sería medio test.
 */
class CadenaCompletaTest {

    private val temporal: File = Files.createTempDirectory("yunkil-cadena").toFile()

    @AfterTest
    fun limpiar() {
        temporal.deleteRecursively()
    }

    private fun exportarYCertificar(plan: String, resolucion: Float): String {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(plan.trimIndent())
        val interpretado = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")

        // Se revisa antes de exportar, igual que hace la aplicación: si la geometría
        // no se sostiene, el STL sobra.
        val revision = editor.revisarPlan(interpretado, null)
        assertTrue(revision.aceptable, "la revisión geométrica lo rechaza: ${revision.informeParaModelo}")

        val resultado = editor.aplicarPlan(interpretado, null)
        assertTrue(resultado.exito, "no se aplicó: ${resultado.resumen}")
        assertTrue(resultado.omitidas.isEmpty(), "se cayeron operaciones: ${resultado.omitidas}")

        val ruta = File(temporal, "pieza.stl").absolutePath
        val certificado = assertNotNull(
            editor.exportarStl(ruta, resolucion, null),
            "la exportación no devolvió certificado: ${editor.ultimoError}",
        )
        // El certificado va antes que el tamaño del archivo: el exportador no entrega
        // archivo si la malla no es apta, así que comprobar primero el archivo
        // escondería el motivo real detrás de un «salió vacío» que no explica nada.
        assertTrue(certificado.apto, "malla no apta:\n${certificado.resumen()}")
        assertTrue(File(ruta).length() > 0, "certificado apto pero sin archivo")
        return certificado.resumen()
    }

    @Test
    fun `una carcasa hueca con agujeros llega a STL estanco`() {
        // Es la pieza más común que se pide a una herramienta así, y la que más
        // castiga al mallador: pared fina, esquinas redondeadas y cuatro agujeros
        // que atraviesan dos paredes cada uno.
        val resumen = exportarYCertificar(
            """
            {"resumen":"Carcasa con agujeros de montaje","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"cuerpo","nombre":"Cuerpo",
               "parametros":{"anchura":60,"altura":30,"profundidad":45,"redondeo":3}},
              {"op":"pared","objetivo":"cuerpo","grosor":2.4},
              {"op":"taladro","objetivo":"seleccion","designacion":"M3","desplazamiento":[22,16]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M3","desplazamiento":[-22,16]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M3","desplazamiento":[22,-16]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M3","desplazamiento":[-22,-16]},
              {"op":"asentar"}
            ]}
            """,
            resolucion = 0.4f,
        )
        println("CARCASA:\n$resumen")
    }

    @Test
    fun `una placa extruida con su taladro llega a STL estanco`() {
        // La otra mitad del catálogo: un contorno acotado levantado un grosor, que es
        // como el prompt le dice al modelo que modele chapa, bridas y escuadras.
        val resumen = exportarYCertificar(
            """
            {"resumen":"Placa con agujero","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"EXTRUSION","alias":"placa","nombre":"Placa",
               "parametros":{"altura":8}},
              {"op":"perfil","objetivo":"placa","forma":"RECTANGULO",
               "parametros":{"anchoPerfil":60,"altoPerfil":40}},
              {"op":"taladro","objetivo":"placa","designacion":"M4"},
              {"op":"asentar"}
            ]}
            """,
            resolucion = 0.35f,
        )
        println("PLACA:\n$resumen")
    }

    @Test
    fun `una escuadra en L llega a STL estanco`() {
        val resumen = exportarYCertificar(
            """
            {"resumen":"Escuadra de montaje","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"EXTRUSION","alias":"escuadra","nombre":"Escuadra",
               "parametros":{"altura":30}},
              {"op":"perfil","objetivo":"escuadra","forma":"ELE",
               "parametros":{"anchoPerfil":50,"altoPerfil":50,"grosorPerfil":10}},
              {"op":"asentar"}
            ]}
            """,
            resolucion = 0.35f,
        )
        println("ESCUADRA:\n$resumen")
    }

    @Test
    fun `una escuadra con aristas redondeadas cierra a resolución fina`() {
        // El redondeo de arista tenía el signo cambiado y hacía la pieza más ancha
        // que sus propias cotas, así que el mallado la cortaba por la caja. Solo se
        // veía cuando `2 · resolución` bajaba del radio, que es justo el régimen del
        // analizador FDM: a 0,35 mm cerraba y a 0,2 mm salía agujereada.
        val resumen = exportarYCertificar(
            """
            {"resumen":"Escuadra de montaje con cantos matados","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"EXTRUSION","alias":"escuadra","nombre":"Escuadra",
               "parametros":{"altura":6,"redondeo":1.5}},
              {"op":"perfil","objetivo":"escuadra","forma":"ELE",
               "parametros":{"anchoPerfil":50,"altoPerfil":50,"grosorPerfil":10}},
              {"op":"asentar"}
            ]}
            """,
            resolucion = 0.2f,
        )
        println("ESCUADRA REDONDEADA:\n$resumen")
    }
}
