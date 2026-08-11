package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.MallaImportada
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La cadena de la geometría traída de fuera, entera y contra disco de verdad.
 *
 * Yunkil exporta un STL, ese archivo se vuelve a importar como si lo hubiera bajado
 * alguien de internet, y sobre él se opera con las mismas herramientas que sobre una
 * caja: se le abre un agujero y se exporta certificado. Es la prueba que decide si
 * traer una malla sirve de algo o es un adorno: si al importar la pieza dejara de ser
 * operable, todo el horneado no valdría para nada.
 */
class ImportarMallaTest {

    private val temporal: File = Files.createTempDirectory("yunkil-importar").toFile()

    @AfterTest
    fun limpiar() {
        temporal.deleteRecursively()
    }

    /** Exporta una pieza paramétrica y devuelve la ruta del STL, como si viniera de fuera. */
    private fun stlDeFuera(): String {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"resumen":"Pieza de origen","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"c","nombre":"Cuerpo",
               "parametros":{"anchura":40,"altura":24,"profundidad":30,"redondeo":4}}
            ]}
            """.trimIndent()
        )
        val plan = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        assertTrue(editor.aplicarPlan(plan, null).exito)

        val ruta = File(temporal, "origen.stl").absolutePath
        assertNotNull(editor.exportarStl(ruta, 0.4f, null), "no se pudo preparar el archivo de partida")
        assertTrue(File(ruta).length() > 0)
        return ruta
    }

    @Test
    fun `un STL importado entra en el arbol y se puede medir`() {
        val ruta = stlDeFuera()
        val editor = Editor(Documento.vacio())

        assertTrue(editor.importarMalla(ruta), "no se importó: ${editor.ultimoError}")

        val fila = editor.filas().firstOrNull { it.tipo == "MALLA" }
        assertNotNull(fila, "la malla no aparece en el árbol")
        assertTrue(fila.nombre == "origen", "debería tomar el nombre del archivo: ${fila.nombre}")

        // Las cotas tienen que parecerse a las de la pieza original. El horneado añade
        // su margen, así que se admite holgura, pero no puede irse a otro tamaño.
        val ancho = editor.cotaMaxima[0] - editor.cotaMinima[0]
        assertTrue(ancho > 40f && ancho < 46f, "la pieza importada mide $ancho de ancho, se esperaba ~40")
    }

    @Test
    fun `una malla importada se puede taladrar y exportar certificada`() {
        // El caso que justifica todo: geometría que nadie puede escribir, editada con
        // las operaciones que sí se pueden escribir, y verificada al salir.
        val ruta = stlDeFuera()
        val editor = Editor(Documento.vacio())
        assertTrue(editor.importarMalla(ruta), "no se importó: ${editor.ultimoError}")

        val malla = assertNotNull(editor.seleccionado)
        assertTrue(editor.taladrar(malla, designacion = "M4"), "no se pudo taladrar: ${editor.ultimoError}")

        val salida = File(temporal, "editada.stl").absolutePath
        val certificado = assertNotNull(
            editor.exportarStl(salida, 0.4f, null),
            "la exportación no devolvió certificado: ${editor.ultimoError}",
        )
        assertTrue(certificado.apto, "la malla editada no es apta:\n${certificado.resumen()}")
        assertTrue(File(salida).length() > 0, "certificado apto pero sin archivo")
        println("MALLA IMPORTADA Y TALADRADA:\n${certificado.resumen()}")
    }

    @Test
    fun `un STL importado conserva topologia cuando la superficie coincide con la rejilla`() {
        val ruta = stlDeFuera()
        val editor = Editor(Documento.vacio())
        assertTrue(editor.importarMalla(ruta))
        assertTrue(editor.taladrar(assertNotNull(editor.seleccionado), designacion = "M4"))

        val certificado = assertNotNull(
            editor.exportarStl(File(temporal, "roto.stl").absolutePath, 0.5f, null),
        )
        assertTrue(certificado.apto, "sigue sin ser apta a 0,5 mm:\n${certificado.resumen()}")
    }

    @Test
    fun `al abrir un proyecto la malla se vuelve a hornear`() {
        // El campo no cabe en el JSON, así que al abrir llega vacío. Si no se
        // rehorneara, la pieza estaría en el árbol y no se vería nada: un documento
        // que parece corrupto sin estarlo.
        val ruta = stlDeFuera()
        val editor = Editor(Documento.vacio())
        assertTrue(editor.importarMalla(ruta))

        // Guardar y abrir es exactamente esto: el documento pasa por JSON, que es
        // donde el campo horneado se queda por el camino.
        val json = editor.aJson()
        val abierto = Editor(Documento.vacio())
        assertTrue(abierto.desdeJson(json), "no se pudo abrir: ${abierto.ultimoError}")
        assertTrue(abierto.estaVacio, "antes de rehornear la malla no puede compilar a nada")

        val perdidas = abierto.rehornearMallas()
        assertTrue(perdidas.isEmpty(), "se perdieron mallas: $perdidas")
        assertTrue(!abierto.estaVacio, "tras rehornear la pieza tiene que volver a existir")
    }

    @Test
    fun `si el archivo original ya no esta se dice cual falta`() {
        val ruta = stlDeFuera()
        val editor = Editor(Documento.vacio())
        assertTrue(editor.importarMalla(ruta))

        val json = editor.aJson()
        File(ruta).delete()

        val abierto = Editor(Documento.vacio())
        assertTrue(abierto.desdeJson(json))
        val perdidas = abierto.rehornearMallas()

        assertTrue(perdidas == listOf("origen"), "debería nombrar la que falta, y dio $perdidas")
    }

    @Test
    fun `hornear no toca el documento, que es lo que permite hacerlo en otro hilo`() {
        // La importación va partida en dos a propósito: hornear es lo caro —rasterizar
        // cada triángulo contra una rejilla— y es lo único que puede irse a otro hilo.
        // Para eso no puede tocar nada del editor, ni siquiera `ultimoError`, así que el
        // motivo del fallo viaja en el propio resultado.
        val ruta = stlDeFuera()
        val editor = Editor(Documento.vacio())
        val filasAntes = editor.filas().size

        val horneada = editor.hornearMallaDesde(ruta)

        assertTrue(horneada is MallaImportada.Lista, "no horneó: $horneada")
        assertEquals(filasAntes, editor.filas().size, "hornear ha metido algo en el árbol")
        assertTrue(editor.estaVacio, "hornear ha cambiado el documento")
        assertNull(editor.ultimoError, "hornear ha escrito en el estado compartido")

        assertTrue(editor.colocarMalla(horneada), "colocar falló: ${editor.ultimoError}")
        assertEquals(filasAntes + 1, editor.filas().size)
        assertEquals("origen", assertNotNull(editor.filas().firstOrNull { it.tipo == "MALLA" }).nombre)
    }

    @Test
    fun `hornear un archivo que no es un STL devuelve el motivo sin tocar el editor`() {
        val basura = File(temporal, "tampoco.stl")
        basura.writeText("esto tampoco es un STL")

        val editor = Editor(Documento.vacio())
        val horneada = editor.hornearMallaDesde(basura.absolutePath)

        assertTrue(horneada is MallaImportada.Fallo, "debería fallar: $horneada")
        assertTrue("STL" in horneada.motivo, "el motivo debería explicarlo: ${horneada.motivo}")
        assertNull(editor.ultimoError, "el fallo no se escribe en el editor, viaja en el resultado")
    }

    @Test
    fun `un archivo que no es un STL se rechaza con motivo`() {
        val basura = File(temporal, "basura.stl")
        basura.writeText("esto no es un STL por ningún lado")

        val editor = Editor(Documento.vacio())
        assertTrue(!editor.importarMalla(basura.absolutePath), "debería rechazarse")
        val error = assertNotNull(editor.ultimoError)
        assertTrue("STL" in error, "el motivo debería explicarlo: $error")
    }
}
