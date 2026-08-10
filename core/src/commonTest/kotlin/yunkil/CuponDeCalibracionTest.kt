package yunkil

import yunkil.doc.Documento
import yunkil.doc.aplanar
import yunkil.doc.compilar
import yunkil.fabricacion.AnalizadorFdm
import yunkil.fabricacion.CuponDeCalibracion
import yunkil.fabricacion.OrigenDelPerfil
import yunkil.fabricacion.PerfilFabricacion
import yunkil.fabricacion.Regla
import yunkil.fabricacion.Severidad
import yunkil.ia.cotasEnMundoDe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * El cupón de calibración: la probeta que convierte una promesa en un dato.
 *
 * Yunkil garantiza que la geometría exportada tiene la holgura declarada. Lo que **no**
 * puede garantizar es que esa holgura sea la buena para tu máquina, porque eso depende de
 * cuánto engorda tu impresora, y eso solo lo dice la impresora. `OrigenDelPerfil.CALIBRADO`
 * llevaba existiendo desde el principio sin que nada lo produjera: un estado inalcanzable.
 *
 * El cupón es una placa con agujeros pasantes a holguras escalonadas y un pasador de la
 * medida nominal. Se imprime, se prueba el pasador agujero por agujero, y **el primero en
 * el que entra** es la holgura real de esa máquina con ese material. Un solo número, que
 * es justo la única perilla que tiene el perfil.
 */
class CuponDeCalibracionTest {

    private val perfil = PerfilFabricacion.PREDETERMINADO

    @Test
    fun `las estaciones rodean la holgura que el perfil trae de fabrica`() {
        val estaciones = CuponDeCalibracion.estaciones(perfil)

        assertTrue(estaciones.size >= 6, "con menos de seis no hay dónde elegir")
        val holguras = estaciones.map { it.holgura }
        assertTrue(
            holguras.any { abs(it - perfil.holguraEncaje) < 1e-4f },
            "el valor de fábrica (${perfil.holguraEncaje}) no está entre $holguras",
        )
        assertTrue(holguras.all { it > 0f }, "una holgura de cero o negativa no es un agujero")
        assertEquals(holguras.sorted(), holguras, "las estaciones van de más apretada a más suelta")
        // Escalonadas de verdad: si dos estaciones se distinguen menos que la altura de
        // capa, el dedo no nota la diferencia y el cupón no mide nada.
        for (i in 1 until holguras.size) {
            assertTrue(
                holguras[i] - holguras[i - 1] >= perfil.alturaCapa * 0.2f,
                "las estaciones $i y ${i - 1} están demasiado juntas para notarlas",
            )
        }
    }

    @Test
    fun `cada agujero mide el nominal mas dos holguras`() {
        val estaciones = CuponDeCalibracion.estaciones(perfil)
        val documento = CuponDeCalibracion.documento(perfil)
        val piezas = documento.raiz.aplanar().map { it.first }

        for (estacion in estaciones) {
            val agujero = assertNotNull(
                piezas.firstOrNull { it.nombre == CuponDeCalibracion.nombreDeEstacion(estacion) },
                "falta el agujero de la estación ${estacion.indice}",
            )
            val diametro = agujero.parametro("radio") * 2f
            val esperado = CuponDeCalibracion.NOMINAL + 2f * estacion.holgura
            assertTrue(
                abs(diametro - esperado) < 1e-3f,
                "la estación ${estacion.indice} mide $diametro y debería medir $esperado",
            )
        }
    }

    @Test
    fun `el pasador mide el nominal clavado`() {
        // El pasador es la referencia: si llevara holgura, el cupón mediría dos cosas a la
        // vez y no se podría despejar ninguna.
        val documento = CuponDeCalibracion.documento(perfil)
        val vastago = assertNotNull(
            documento.raiz.aplanar().map { it.first }.firstOrNull { it.nombre == "Pasador" },
        )
        assertTrue(
            abs(vastago.parametro("radio") * 2f - CuponDeCalibracion.NOMINAL) < 1e-3f,
            "el pasador mide ${vastago.parametro("radio") * 2f}",
        )
    }

    @Test
    fun `el cupon cabe en la impresora del perfil`() {
        val cotas = assertNotNull(CuponDeCalibracion.documento(perfil).compilar()).cotas()
        val volumen = perfil.volumenDeImpresion
        assertTrue(cotas.size.x <= volumen.x, "mide ${cotas.size.x} de ancho y la máquina da ${volumen.x}")
        assertTrue(cotas.size.y <= volumen.y, "mide ${cotas.size.y} de alto")
        assertTrue(cotas.size.z <= volumen.z, "mide ${cotas.size.z} de fondo")
    }

    @Test
    fun `el cupon pasa el examen del perfil que viene a calibrar`() {
        // Es la prueba que de verdad importa: una probeta que el propio analizador
        // rechazaría mediría los defectos de la probeta, no los de la máquina.
        //
        // Se exceptúa la regla de la malla, y con motivo escrito: el cupón lleva un
        // escalón cóncavo —el vástago sobre su pie— y ahí el contorneado se cruza consigo
        // mismo a unas resoluciones sí y a otras no. Es un defecto del contorneado, no del
        // cupón: sale igual construyendo el escalón de dos maneras distintas. Ver
        // [ContorneadoEnAristasVivasTest]. Mientras siga, el cupón no se puede entregar.
        val nodo = assertNotNull(CuponDeCalibracion.documento(perfil).compilar())
        val informe = AnalizadorFdm(nodo, perfil, resolucion = 0.4f).analizar()
        val graves = informe.hallazgos
            .filter { it.severidad == Severidad.FALLARA && it.regla != Regla.MALLA_EXPORTABLE }
        assertTrue(graves.isEmpty(), "el cupón no es imprimible: ${graves.map { it.titulo }}")
    }

    @Test
    fun `las paredes entre estacion y estacion aguantan la boquilla del perfil`() {
        // Si dos agujeros se comen la pared que los separa, el cupón mide la pared y no
        // la holgura. Es la única cota del cupón que depende del perfil.
        val estaciones = CuponDeCalibracion.estaciones(perfil)
        val documento = CuponDeCalibracion.documento(perfil)
        val piezas = documento.raiz.aplanar().map { it.first }
        val centros = estaciones.map { e ->
            assertNotNull(piezas.firstOrNull { it.nombre == CuponDeCalibracion.nombreDeEstacion(e) })
                .transform.translation.x
        }
        for (i in 1 until estaciones.size) {
            val hueco = (centros[i] - centros[i - 1]) -
                (estaciones[i].diametro(CuponDeCalibracion.NOMINAL) +
                    estaciones[i - 1].diametro(CuponDeCalibracion.NOMINAL)) * 0.5f
            assertTrue(
                hueco >= perfil.grosorMinimoPared * 2f,
                "entre las estaciones ${i} y ${i + 1} quedan $hueco mm de pared",
            )
        }
    }

    // -------------------------------------------------------------- calibrar

    @Test
    fun `calibrar deja el numero medido y de donde sale`() {
        val estaciones = CuponDeCalibracion.estaciones(perfil)
        val entro = estaciones[2]

        val calibrado = perfil.calibradoCon(entro.holgura)

        assertTrue(
            abs(calibrado.holguraEncaje - entro.holgura) < 1e-4f,
            "no se quedó con la holgura que entró",
        )
        assertEquals(OrigenDelPerfil.CALIBRADO, calibrado.origen)
        // Y no toca nada más: el cupón mide una cosa, así que solo puede afirmar una.
        assertEquals(perfil.boquilla, calibrado.boquilla)
        assertEquals(perfil.anguloVoladizoMaximo, calibrado.anguloVoladizoMaximo)
        assertEquals(perfil.nombre, calibrado.nombre)
    }

    @Test
    fun `una holgura que no sale del cupon no se acepta`() {
        // Aceptar un número a mano y marcarlo «calibrado en tu máquina» sería mentir en la
        // etiqueta, que es lo único que hace útil a la etiqueta.
        assertTrue(runCatching { perfil.calibradoCon(0f) }.isFailure, "aceptó cero")
        assertTrue(runCatching { perfil.calibradoCon(-0.1f) }.isFailure, "aceptó una negativa")
        assertTrue(runCatching { perfil.calibradoCon(Float.NaN) }.isFailure, "aceptó NaN")
    }

    @Test
    fun `un perfil calibrado vuelve a derivar las piezas que encajan`() {
        val editor = yunkil.doc.Editor(Documento.vacio())
        editor.anadir(yunkil.doc.TipoPieza.CILINDRO.name, editor.seleccionado)
        val id = assertNotNull(editor.seleccionado)
        val medida = assertNotNull(editor.declararMedida("agujero", 20f))
        assertTrue(editor.declararEncaje(id, medida), editor.ultimoError ?: "")

        assertTrue(editor.usarPerfilCalibrado(perfil.calibradoCon(0.35f)), editor.ultimoError ?: "")

        val ancho = assertNotNull(editor.documentoActual.cotasEnMundoDe(id)).size.x
        assertTrue(abs(ancho - 19.3f) < 0.01f, "esperaba 19,3 mm con 0,35 de holgura y mide $ancho")
        assertTrue(
            "calibrado" in (editor.descripcionDeEncaje(id) ?: ""),
            "el encaje no dice que ahora la holgura está calibrada: ${editor.descripcionDeEncaje(id)}",
        )
    }
}
