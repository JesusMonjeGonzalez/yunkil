package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.fabricacion.CatalogoDePerfiles
import yunkil.fabricacion.OrigenDelPerfil
import yunkil.fabricacion.PerfilFabricacion
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Calibrar la máquina y que siga calibrada mañana.
 *
 * Faltaba el último tramo de la cadena: el cupón se generaba, la holgura medida producía un
 * perfil calibrado, y ese perfil vivía en memoria y moría al cerrar. Peor todavía: como el
 * perfil activo viaja por su **nombre** —el informe se pide con él, y el editor aislado que
 * analiza en otro hilo lo resuelve por su cuenta—, un perfil que no estuviera en la lista
 * de fábrica se resolvía en silencio al de partida. Se calibraba, y se seguía analizando
 * con los números del fabricante sin que nada lo dijera.
 *
 * Vive en `jvmTest` porque escribe en disco de verdad: un almacén que no se comprueba
 * contra un archivo no es un almacén.
 */
class PerfilCalibradoTest {

    private val temporal: File = Files.createTempDirectory("yunkil-perfiles").toFile()
    private val archivo: String get() = File(temporal, "perfiles.json").absolutePath

    @BeforeTest
    fun limpiarCatalogo() = CatalogoDePerfiles.vaciar()

    @AfterTest
    fun limpiar() {
        CatalogoDePerfiles.vaciar()
        temporal.deleteRecursively()
    }

    private val deFabrica = PerfilFabricacion.PREDETERMINADO.nombre

    @Test
    fun `un perfil calibrado se resuelve por su nombre desde cualquier editor`() {
        // Esta es la prueba que importa: el análisis corre en un editor aparte, en otro
        // hilo, y solo recibe el nombre. Si el nombre no se resuelve, el examen contesta
        // sobre el perfil de fábrica y el usuario cree que ha calibrado algo.
        val editor = Editor(Documento.vacio())
        assertNull(
            editor.guardarPerfilCalibrado(deFabrica, "P1S de casa · PLA", 0.26f, archivo),
            "no se pudo guardar",
        )

        val otro = Editor(Documento.vacio())
        val perfil = assertNotNull(
            PerfilFabricacion.porNombre("P1S de casa · PLA"),
            "el perfil calibrado no existe fuera del editor que lo guardó",
        )
        assertEquals(0.26f, perfil.holguraEncaje, 1e-4f)
        assertEquals(OrigenDelPerfil.CALIBRADO, perfil.origen)
        assertTrue("P1S de casa · PLA" in otro.perfilesDeFabricacion(), "no sale en la lista")
    }

    @Test
    fun `guardar lo deja activo y calibrado`() {
        val editor = Editor(Documento.vacio())
        editor.guardarPerfilCalibrado(deFabrica, "Mi P1S", 0.24f, archivo)

        assertEquals("Mi P1S", editor.perfilDeTrabajo())
        assertEquals(OrigenDelPerfil.CALIBRADO.etiqueta, editor.origenDelPerfil())
        assertEquals(0.24f, editor.holguraDelPerfil(), 1e-4f)
    }

    @Test
    fun `al abrir otra vez la aplicacion el perfil sigue ahi`() {
        Editor(Documento.vacio()).guardarPerfilCalibrado(deFabrica, "Mi A1", 0.31f, archivo)
        assertTrue(File(archivo).length() > 0, "no se escribió el archivo")

        // Arrancar de cero es exactamente esto: catálogo vacío y una lectura del archivo.
        CatalogoDePerfiles.vaciar()
        assertNull(PerfilFabricacion.porNombre("Mi A1"), "el catálogo no se vació")

        val recien = Editor(Documento.vacio())
        assertEquals(1, recien.cargarPerfiles(archivo))
        assertEquals(0.31f, assertNotNull(PerfilFabricacion.porNombre("Mi A1")).holguraEncaje, 1e-4f)
    }

    @Test
    fun `un archivo que no existe todavia no es un error`() {
        val recien = Editor(Documento.vacio())
        assertEquals(0, recien.cargarPerfiles(File(temporal, "no-existe.json").absolutePath))
        assertEquals(PerfilFabricacion.VERIFICADOS.size, recien.perfilesDeFabricacion().size)
    }

    @Test
    fun `un archivo roto no borra la calibracion de nadie`() {
        val roto = File(temporal, "roto.json")
        roto.writeText("{ esto no es la lista de perfiles")

        val editor = Editor(Documento.vacio())
        assertEquals(-1, editor.cargarPerfiles(roto.absolutePath), "debería decir que está roto")
        assertTrue(roto.length() > 0, "y no tocar el archivo")
        assertEquals(PerfilFabricacion.VERIFICADOS.size, editor.perfilesDeFabricacion().size)
    }

    @Test
    fun `no se puede tapar un perfil de fabrica con uno propio`() {
        // Dos perfiles con el mismo nombre y distintos números convertirían «lo tengo
        // calibrado» en una lotería.
        val editor = Editor(Documento.vacio())
        val motivo = assertNotNull(
            editor.guardarPerfilCalibrado(deFabrica, deFabrica, 0.25f, archivo),
            "debería rechazarse",
        )
        assertTrue("fábrica" in motivo, "el motivo debería explicarlo: $motivo")
        assertEquals(0.2f, assertNotNull(PerfilFabricacion.porNombre(deFabrica)).holguraEncaje, 1e-4f)
    }

    @Test
    fun `recalibrar reemplaza el mismo perfil en vez de duplicarlo`() {
        val editor = Editor(Documento.vacio())
        editor.guardarPerfilCalibrado(deFabrica, "Mi P1S", 0.24f, archivo)
        editor.guardarPerfilCalibrado(deFabrica, "Mi P1S", 0.28f, archivo)

        assertEquals(listOf("Mi P1S"), editor.perfilesPropios())
        assertEquals(0.28f, assertNotNull(PerfilFabricacion.porNombre("Mi P1S")).holguraEncaje, 1e-4f)
    }

    @Test
    fun `olvidar un perfil lo quita del disco y devuelve al perfil del que salio`() {
        // Y al del que salió, no al primero de la lista: quien calibró una A1 con PETG y
        // borra su calibración sigue teniendo una A1 delante.
        val editor = Editor(Documento.vacio())
        val base = PerfilFabricacion.VERIFICADOS[1].nombre
        assertTrue(base != PerfilFabricacion.PREDETERMINADO.nombre, "hace falta que no sea el de partida")
        editor.guardarPerfilCalibrado(base, "Mi A1", 0.34f, archivo)
        assertEquals("Mi A1", editor.perfilDeTrabajo())

        assertNull(editor.olvidarPerfil("Mi A1", archivo))
        assertTrue(editor.perfilesPropios().isEmpty())
        assertEquals(base, editor.perfilDeTrabajo(), "al borrar el activo hay que volver al que lo engendró")

        CatalogoDePerfiles.vaciar()
        assertEquals(0, Editor(Documento.vacio()).cargarPerfiles(archivo), "seguía en el archivo")
    }

    @Test
    fun `el cupon entra en el documento con una estacion por holgura`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.estaVacio, "el documento de partida no estaba vacío")

        assertTrue(editor.cargarCuponDeCalibracion(deFabrica), editor.ultimoError ?: "")

        assertTrue(!editor.estaVacio, "el cupón no llegó al documento")
        val holguras = editor.holgurasDelCupon(deFabrica)
        assertEquals(8, holguras.size)
        assertTrue(holguras.zipWithNext().all { (a, b) -> b > a }, "las holguras deberían ir en orden: $holguras")
        // La estación que se elige es la que entra, así que hay que poder mirarla con el
        // calibre: el diámetro impreso es el nominal más dos holguras.
        val diametros = editor.diametrosDelCupon(deFabrica)
        assertEquals(holguras.size, diametros.size)
        assertEquals(8f + 2f * holguras[0], diametros[0], 1e-4f)

        // Y se puede deshacer: el cupón es una probeta, no el trabajo de nadie.
        assertTrue(editor.deshacer())
        assertTrue(editor.estaVacio)
    }
}
