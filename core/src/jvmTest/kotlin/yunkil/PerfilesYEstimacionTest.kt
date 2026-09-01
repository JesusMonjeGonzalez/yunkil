package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.Encaje
import yunkil.doc.SentidoDeEncaje
import yunkil.doc.holguraCon
import yunkil.doc.holguraEfectiva
import yunkil.fabricacion.CatalogoDePerfiles
import yunkil.fabricacion.EstimacionDeImpresion
import yunkil.fabricacion.OrigenDelPerfil
import yunkil.fabricacion.PerfilFabricacion
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Perfiles con versiones, estimación de material y holgura proporcional: las tres
 * piezas que convierten «creo que entrará» en un número que se puede justificar.
 */
class PerfilesYEstimacionTest {

    // --------------------------------------------------------- versionado de perfiles

    @Test
    fun `recalibrar archiva la version anterior y se puede volver`() {
        val ruta = Files.createTempFile("yunkil-perfiles-", ".json").toString()
        val rutaHistorial = Files.createTempFile("yunkil-perfiles-hist-", ".json").toString()
        CatalogoDePerfiles.vaciar()
        try {
            val base = PerfilFabricacion.VERIFICADOS.first()
            val primera = base.copy(nombre = "Mi P1S", origen = OrigenDelPerfil.EDITADO)
                .copy(holguraEncaje = 0.15f)
            assertNull(CatalogoDePerfiles.guardar(primera, ruta, rutaHistorial))
            assertNull(CatalogoDePerfiles.guardar(primera.copy(holguraEncaje = 0.22f), ruta, rutaHistorial))

            // La recalibración pisó la primera, pero no la perdió.
            val historial = CatalogoDePerfiles.historialDe("Mi P1S")
            assertEquals(1, historial.size)
            assertEquals(0.15f, historial.single().holguraEncaje)

            assertNull(CatalogoDePerfiles.restaurar("Mi P1S", 0, ruta, rutaHistorial))
            assertEquals(0.15f, CatalogoDePerfiles.porNombre("Mi P1S")?.holguraEncaje)
            // Restaurar también es editar: la que estaba se archiva.
            assertEquals(0.22f, CatalogoDePerfiles.historialDe("Mi P1S").single().holguraEncaje)

            // Y el historial sobrevive al cierre de la aplicación.
            CatalogoDePerfiles.vaciar()
            assertEquals(1, CatalogoDePerfiles.cargarHistorialDesde(rutaHistorial))
        } finally {
            CatalogoDePerfiles.vaciar()
            Files.deleteIfExists(java.nio.file.Path.of(ruta))
            Files.deleteIfExists(java.nio.file.Path.of(rutaHistorial))
        }
    }

    @Test
    fun `un perfil de fabrica no se pisa y no tiene historial`() {
        val ruta = Files.createTempFile("yunkil-perfiles-verif-", ".json").toString()
        CatalogoDePerfiles.vaciar()
        try {
            val fabrica = PerfilFabricacion.VERIFICADOS.first()
            assertNotNull(
                CatalogoDePerfiles.guardar(fabrica.copy(holguraEncaje = 9f), ruta),
                "guardar sobre un nombre de fábrica se rechaza con motivo",
            )
            assertTrue(CatalogoDePerfiles.historialDe(fabrica.nombre).isEmpty())
        } finally {
            CatalogoDePerfiles.vaciar()
        }
    }

    // ------------------------------------------------------- estimación de impresión

    @Test
    fun `la estimacion convierte volumen en gramos, metros y coste`() {
        val perfil = PerfilFabricacion.VERIFICADOS.first()
        // 4.000 mm³ macizos de PLA: 4 cm³ × 1,24 g/cm³ ≈ 4,96 g.
        val estimacion = assertNotNull(EstimacionDeImpresion.desde(4000f, perfil))
        assertEquals(4.96f, estimacion.gramos, 0.01f)
        // 4.000 mm³ a sección de 1,75 mm son ~1,66 m de filamento.
        assertTrue(estimacion.metrosDeFilamento > 1.4f && estimacion.metrosDeFilamento < 1.9f)
        assertTrue(estimacion.coste > 0f && estimacion.coste < 1f)
        assertTrue(estimacion.descripcion().contains("sin relleno ni soportes"))
    }

    @Test
    fun `un volumen sin sentido no produce una estimacion`() {
        val perfil = PerfilFabricacion.VERIFICADOS.first()
        assertNull(EstimacionDeImpresion.desde(0f, perfil))
        assertNull(EstimacionDeImpresion.desde(-3f, perfil))
        assertNull(EstimacionDeImpresion.desde(Float.NaN, perfil))
    }

    // ------------------------------------------------------ holgura proporcional

    @Test
    fun `la holgura proporcional afloja en diametros grandes y no toca los pequenos`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("CILINDRO", null), editor.ultimoError)
        val tapon = assertNotNull(editor.seleccionado)
        val medida = assertNotNull(editor.declararMedida("agujero de 120", 120f))
        assertTrue(editor.declararEncaje(tapon, medida), editor.ultimoError)
        val perfil = PerfilFabricacion.VERIFICADOS.first()

        val encaje = assertNotNull(editor.pieza(tapon)?.encajes?.firstOrNull())
        // Con la marca apagada, todo como siempre: el piso fijo del perfil.
        assertEquals(encaje.holguraCon(perfil), encaje.holguraEfectiva(120f, perfil))

        editor.fijarHolguraProporcional(tapon, true)
        val proporcional = assertNotNull(editor.pieza(tapon)?.encajes?.firstOrNull())
        assertTrue(proporcional.holguraProporcional, "la marca queda activada en el documento")
        // 0,5 % de 120 mm = 0,6 mm por lado: por encima del piso de 0,2.
        assertTrue(
            proporcional.holguraEfectiva(120f, perfil) > proporcional.holguraCon(perfil),
            "en un agujero de 120 mm, la fracción del diámetro manda",
        )
        // En un agujero pequeño, el piso de la máquina manda: la marca no aprieta.
        assertEquals(
            proporcional.holguraCon(perfil),
            proporcional.holguraEfectiva(10f, perfil),
        )

        // La pieza se agrandó al activarlo: la cota se vuelve a derivar. Se mide
        // contra la cota sin la marca, no contra la ya derivada.
        val cotaSinProporcional = editor.cotasDe(tapon)?.size?.x ?: 0f
        editor.fijarHolguraProporcional(tapon, false)
        val cotaBase = editor.cotasDe(tapon)?.size?.x ?: 0f
        editor.fijarHolguraProporcional(tapon, true)
        val cotaConProporcional = editor.cotasDe(tapon)?.size?.x ?: 0f
        assertEquals(cotaSinProporcional, cotaConProporcional, "activar dos veces da lo mismo")
        // ENTRA: más holgura por lado es una pieza **menor**; RECIBE sería al revés.
        assertTrue(cotaConProporcional < cotaBase, "más holgura por lado afloja el ajuste")

        // Y un encaje RECIBE con la marca se verifica contra la holgura efectiva, no
        // contra el piso fijo, que sería un «cumple» falso por defecto.
        val encajeRecibe = Encaje(
            medida = "m",
            sentido = SentidoDeEncaje.RECIBE,
            holguraProporcional = true,
        )
        assertTrue(encajeRecibe.holguraEfectiva(120f, perfil) > encajeRecibe.holguraCon(perfil))
    }
}
