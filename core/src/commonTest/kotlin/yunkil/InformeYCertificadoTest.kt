package yunkil

import yunkil.fabricacion.AnalizadorFdm
import yunkil.fabricacion.PerfilFabricacion
import yunkil.fabricacion.Regla
import yunkil.fabricacion.Severidad
import yunkil.kernel.Caja
import yunkil.kernel.Cilindro
import yunkil.kernel.Cordon
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.SdfNode
import yunkil.kernel.Union
import yunkil.kernel.Vec3
import yunkil.malla.ContorneadoDual
import yunkil.malla.Exportador
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El informe y el certificado no pueden decir cosas distintas de la misma pieza.
 *
 * Este era un hueco de los que hacen perder la confianza en una herramienta, y no era
 * teórico: el analizador medía el campo —paredes, voladizos, esbeltez— y declaraba
 * `aptoParaImprimir` mirando solo sus propias reglas. La validez de la **malla** no la
 * miraba nadie hasta el momento de exportar, y ahí el certificado podía negarse a
 * escribir por agujeros, caras del revés, auto-intersecciones o desviación de volumen.
 * El usuario leía «apta para imprimir», pulsaba exportar y no obtenía archivo, por una
 * razón que el informe jamás había mencionado.
 *
 * Lo que se ata aquí es una implicación en un solo sentido:
 *
 *     informe.aptoParaImprimir  ⟹  el certificado aprueba a esa misma resolución
 *
 * El sentido contrario no se promete y no debe prometerse: exportar más grueso que el
 * análisis puede abrir agujeros que aquí no estaban. Por eso el aviso dice a qué
 * resolución se ha medido en vez de garantizar que cualquier exportación saldrá.
 */
class InformeYCertificadoTest {

    private val perfil: PerfilFabricacion = PerfilFabricacion.PREDETERMINADO

    private fun modelos(): List<Pair<String, SdfNode>> = listOf(
        "caja" to Caja(Vec3(20f, 10f, 15f)),
        "caja_redondeada" to Caja(Vec3(18f, 12f, 12f), redondeo = 3f),
        "esfera" to Esfera(14f),
        "cilindro" to Cilindro(8f, 30f),
        "taladrada" to Diferencia(Caja(Vec3(20f, 10f, 20f)), Cilindro(5f, 60f)),
        "torre_fina" to Cilindro(1.5f, 60f),
        "chapa_fina" to Caja(Vec3(25f, 0.15f, 25f)),
        "figura" to Union(
            Esfera(12f),
            Cordon(
                listOf(Vec3(8f, 4f, 0f), Vec3(20f, 12f, 4f), Vec3(26f, 24f, -2f)),
                listOf(4f, 2.5f, 0.8f),
            ),
            fusion = 3f,
        ),
    )

    @Test
    fun `si el informe dice que es apta, el certificado la aprueba`() {
        var comprobadas = 0
        for ((nombre, nodo) in modelos()) {
            val informe = AnalizadorFdm(nodo, perfil).analizar()
            if (!informe.aptoParaImprimir) continue
            comprobadas++

            // Se rehace la malla con la resolución que el propio informe declara haber
            // usado, que es lo que hace comparable una cosa con la otra.
            val res = informe.metricas.resolucionDeAnalisis
            val malla = ContorneadoDual(nodo, res).generar()
            val certificado = Exportador(nodo).examinar(malla, res, 0)

            assertTrue(
                certificado.apto,
                "«$nombre» se declaró apta y el certificado la rechaza:\n${certificado.resumen()}",
            )
        }
        // Si ninguna pieza llegara a declararse apta, la prueba pasaría sin comprobar
        // nada y el invariante quedaría sin vigilar justo cuando más falta hace.
        assertTrue(comprobadas >= 3, "solo $comprobadas piezas se declararon aptas: prueba vacía")
    }

    @Test
    fun `una malla que no saldria del contorneado se avisa en el informe`() {
        // Una chapa de 0,3 mm mallada a 1 mm no produce ni un triángulo. Antes el
        // informe hablaba de paredes finas —que es cierto— pero nunca decía lo único
        // que impedía terminar: que de ahí no sale archivo.
        val chapa = Caja(Vec3(25f, 0.15f, 25f))
        val informe = AnalizadorFdm(chapa, perfil, resolucion = 1f).analizar()

        val aviso = informe.hallazgos.firstOrNull { it.regla == Regla.MALLA_EXPORTABLE }
        assertTrue(aviso != null, "no se avisó de que la malla no sale")
        assertTrue(aviso.severidad == Severidad.FALLARA, "un archivo que no se escribe es un fallo")
        assertFalse(informe.aptoParaImprimir, "no puede declararse apta una pieza que no se exporta")
        assertTrue(
            aviso.detalle.contains("triángulo"),
            "el aviso debe decir qué pasa, no solo que pasa: ${aviso.detalle}",
        )
    }

    @Test
    fun `un codo cerrado mallado grueso se avisa en vez de sorprender al exportar`() {
        // Es el caso real anotado en los límites del README: el contorneado deja
        // agujeros en codos cerrados a ciertas resoluciones. Mientras eso siga abierto,
        // lo que no puede pasar es que el informe calle y el certificado hable.
        val cola = Cordon(
            listOf(
                Vec3(0f, 26f, 6f),
                Vec3(8f, 34f, 16f),
                Vec3(6f, 44f, 22f),
                Vec3(-4f, 48f, 16f),
            ),
            listOf(4f, 4f, 4f, 4f),
        )
        val informe = AnalizadorFdm(cola, perfil, resolucion = 0.4f).analizar()

        val malla = ContorneadoDual(cola, 0.4f).generar()
        val certificado = Exportador(cola).examinar(malla, 0.4f, 0)
        // El caso solo prueba algo si de verdad falla el certificado; si algún día el
        // contorneado se arregla, esta prueba deja de aplicar y hay que decirlo.
        if (certificado.apto) return

        assertTrue(
            informe.hallazgos.any { it.regla == Regla.MALLA_EXPORTABLE },
            "el certificado rechaza esta cola y el informe no dijo nada",
        )
        assertFalse(informe.aptoParaImprimir)
    }

    @Test
    fun `una pieza sana no gana avisos por esta regla`() {
        // La otra mitad del trato: la regla no puede volverse ruido. Una caja sencilla
        // no tiene por qué llevar nunca un aviso de malla.
        val informe = AnalizadorFdm(Caja(Vec3(20f, 10f, 15f)), perfil).analizar()
        assertTrue(
            informe.hallazgos.none { it.regla == Regla.MALLA_EXPORTABLE },
            "una caja sana no debería llevar aviso de malla",
        )
    }
}
