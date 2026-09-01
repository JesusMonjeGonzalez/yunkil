package yunkil

import yunkil.doc.ClaseDeAjuste
import yunkil.doc.Documento
import yunkil.doc.Encaje
import yunkil.doc.Medida
import yunkil.doc.Pieza
import yunkil.doc.ProcedenciaDeMedida
import yunkil.doc.SentidoDeEncaje
import yunkil.doc.TipoPieza
import yunkil.doc.compilar
import yunkil.doc.resolverEncajes
import yunkil.fabricacion.AnalizadorFdm
import yunkil.fabricacion.InformeDeFabricacion
import yunkil.fabricacion.PerfilFabricacion
import yunkil.fabricacion.Regla
import yunkil.fabricacion.Severidad
import yunkil.ia.EjeNombrado
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * La holgura declarada, **medida sobre la geometría final**.
 *
 * Declarar un encaje no demuestra nada. Entre la declaración y el STL hay booleanas,
 * vaciados, simetrías y brochas que pueden habérsela comido, y una pieza que dice
 * encajar y no encaja es peor que una que no dice nada. Así que el analizador construye
 * la pieza entera y mide.
 *
 * Con una malla esto sería intersección de triángulos. Con un campo de distancias es una
 * evaluación por punto, que es literalmente para lo que sirve un campo de distancias.
 *
 * Y **falla cerrado**, al revés que el crítico visual: si no puede medir, lo dice y no
 * aprueba. El crítico visual es un revisor de más y puede caerse sin tumbar nada; esto es
 * una comprobación exacta, de la familia del certificado de malla, y una que se cae en
 * silencio es peor que no tenerla.
 */
class VerificacionDeEncajeTest {

    private val perfil = PerfilFabricacion.PREDETERMINADO

    private fun pieza(tipo: TipoPieza, nombre: String, vararg cotas: Pair<String, Float>) =
        Pieza.nueva(tipo, nombre).let { it.copy(parametros = it.parametros + cotas.toMap()) }

    /** Documento con una medida llamada `agujero` y las piezas que se le pasen. */
    private fun documentoCon(nominal: Float, vararg hijos: Pieza): Documento {
        val raiz = Pieza.nueva(TipoPieza.UNION, "Modelo").copy(hijos = hijos.toList())
        return Documento(
            raiz = raiz,
            medidas = listOf(Medida("medida-1", "agujero", nominal, ProcedenciaDeMedida.CALIBRE)),
        ).resolverEncajes(perfil)
    }

    private fun informeDe(documento: Documento): InformeDeFabricacion {
        val nodo = assertNotNull(documento.compilar(), "el documento no compila")
        return AnalizadorFdm(nodo, perfil, documento, resolucion = 0.4f).analizar()
    }

    private fun encaje(sentido: SentidoDeEncaje) =
        Encaje("medida-1", EjeNombrado.X, sentido, ClaseDeAjuste.DESLIZANTE)

    // -------------------------------------------------------------- se cumple

    @Test
    fun `un tapon limpio mide la holgura que declaro`() {
        val tapon = pieza(TipoPieza.CILINDRO, "Tapón", "radio" to 15f, "altura" to 20f)
            .copy(encajes = listOf(encaje(SentidoDeEncaje.ENTRA)))

        val informe = informeDe(documentoCon(20f, tapon))

        // El número va al informe aunque no dispare ningún aviso: es la diferencia entre
        // «lo he mirado y está bien» y «no he podido mirarlo», que es justo lo que no se
        // distingue hoy en la crítica visual.
        val medido = assertNotNull(
            informe.encajes.firstOrNull { it.piezaNombre == "Tapón" },
            "el informe no dice nada del encaje: ${informe.encajes}",
        )
        assertTrue(medido.cumple, "dice que no cumple: ${medido.motivo}")
        assertTrue(
            abs(medido.holguraMedida - 0.2f) < 0.1f,
            "declaró 0,2 y midió ${medido.holguraMedida}",
        )
        assertTrue(
            informe.hallazgos.none { it.regla == Regla.ENCAJE_DECLARADO },
            "avisó de un encaje que se cumple",
        )
    }

    @Test
    fun `un taladro limpio que recibe un eje de 8 mide su holgura`() {
        val bloque = pieza(
            TipoPieza.CAJA, "Bloque",
            "anchura" to 40f, "altura" to 40f, "profundidad" to 40f, "redondeo" to 0f,
        )
        val taladro = pieza(TipoPieza.CILINDRO, "Taladro", "radio" to 5f, "altura" to 60f)
            .copy(encajes = listOf(encaje(SentidoDeEncaje.RECIBE)))
        val cuerpo = Pieza.nueva(TipoPieza.DIFERENCIA, "Cuerpo").copy(hijos = listOf(bloque, taladro))

        val informe = informeDe(documentoCon(8f, cuerpo))

        val medido = assertNotNull(informe.encajes.firstOrNull { it.piezaNombre == "Taladro" })
        assertTrue(medido.cumple, "dice que no cumple: ${medido.motivo}")
        assertTrue(
            abs(medido.holguraMedida - 0.2f) < 0.15f,
            "declaró 0,2 y midió ${medido.holguraMedida}",
        )
    }

    // ------------------------------------------------------------ no se cumple

    @Test
    fun `material pegado al tapon lo saca de la medida y el informe lo dice`() {
        // El encaje se declaró sobre el cilindro y se cumplió. Después alguien le fusionó
        // una pestaña que sobresale, y la pieza dejó de entrar por donde tenía que entrar.
        // Este es el fallo que ningún STL sabe contar: el plan era correcto y el resultado no.
        val tapon = pieza(TipoPieza.CILINDRO, "Tapón", "radio" to 15f, "altura" to 20f)
            .copy(encajes = listOf(encaje(SentidoDeEncaje.ENTRA)))
        val pestana = pieza(
            TipoPieza.CAJA, "Pestaña",
            "anchura" to 21.6f, "altura" to 4f, "profundidad" to 4f, "redondeo" to 0f,
        )

        val informe = informeDe(documentoCon(20f, tapon, pestana))

        val medido = assertNotNull(informe.encajes.firstOrNull { it.piezaNombre == "Tapón" })
        assertTrue(!medido.cumple, "dio por bueno un tapón que mide de más")
        assertTrue(medido.holguraMedida < 0f, "midió ${medido.holguraMedida} y debería ser negativa")

        val aviso = assertNotNull(
            informe.hallazgos.firstOrNull { it.regla == Regla.ENCAJE_DECLARADO },
            "midió mal y no avisó",
        )
        assertEquals(Severidad.FALLARA, aviso.severidad, "una pieza que no entra no es «mejorable»")
        assertTrue("Tapón" in aviso.titulo, "el aviso no nombra la pieza: ${aviso.titulo}")
    }

    @Test
    fun `algo metido dentro del taladro se ve aunque deje los bordes libres`() {
        // Un pasador en el centro del agujero. Los extremos del agujero siguen despejados,
        // así que medir de borde a borde no lo vería: hay que medir lo que **cabe**.
        val bloque = pieza(
            TipoPieza.CAJA, "Bloque",
            "anchura" to 40f, "altura" to 40f, "profundidad" to 40f, "redondeo" to 0f,
        )
        val taladro = pieza(TipoPieza.CILINDRO, "Taladro", "radio" to 5f, "altura" to 60f)
            .copy(encajes = listOf(encaje(SentidoDeEncaje.RECIBE)))
        val cuerpo = Pieza.nueva(TipoPieza.DIFERENCIA, "Cuerpo").copy(hijos = listOf(bloque, taladro))
        val pasador = pieza(TipoPieza.CILINDRO, "Pasador", "radio" to 2f, "altura" to 50f)

        val informe = informeDe(documentoCon(8f, cuerpo, pasador))

        val medido = assertNotNull(informe.encajes.firstOrNull { it.piezaNombre == "Taladro" })
        assertTrue(!medido.cumple, "dio por bueno un agujero con algo dentro")
        assertTrue(
            medido.holguraMedida < 0.2f,
            "midió ${medido.holguraMedida} de holgura en un agujero obstruido",
        )
        assertNotNull(
            informe.hallazgos.firstOrNull { it.regla == Regla.ENCAJE_DECLARADO },
            "midió mal y no avisó",
        )
    }

    // ---------------------------------------------------------- falla cerrado

    @Test
    fun `un encaje que no se puede medir se dice y no se aprueba`() {
        // La pieza está oculta: no aporta material, así que no hay nada que medir. Callar
        // aquí sería indistinguible de haberla medido y aprobado.
        val tapon = pieza(TipoPieza.CILINDRO, "Tapón", "radio" to 15f, "altura" to 20f)
            .copy(encajes = listOf(encaje(SentidoDeEncaje.ENTRA)), visible = false)
        val soporte = pieza(
            TipoPieza.CAJA, "Soporte",
            "anchura" to 30f, "altura" to 10f, "profundidad" to 30f, "redondeo" to 0f,
        )

        val informe = informeDe(documentoCon(20f, tapon, soporte))

        val medido = assertNotNull(
            informe.encajes.firstOrNull { it.piezaNombre == "Tapón" },
            "el encaje desapareció del informe en vez de decir que no se pudo medir",
        )
        assertTrue(!medido.cumple, "aprobó un encaje que no llegó a medir")
        assertNotNull(medido.motivo, "no dice por qué no pudo medirlo")
    }
}
