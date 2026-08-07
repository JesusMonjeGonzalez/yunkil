package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.Ejemplares
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Prueba que cada ejemplar de la biblioteca es un ejemplo de BUEN modelado.
 *
 * No basta con que el JSON sea válido: un ejemplar que se interpreta pero deja una
 * pieza flotando, o una resta que no corta, le enseñaría al modelo a copiar
 * exactamente el error que el resto del sistema existe para evitar. Por eso cada
 * ejemplar pasa las tres cribas reales por las que pasaría el plan de un modelo:
 * interpretar, aplicar y revisar sobre el campo compilado.
 */
class EjemplaresTest {

    @Test
    fun `el catalogo tiene entre 12 y 24 ejemplares`() {
        assertTrue(
            Ejemplares.CATALOGO.size in 12..24,
            "el catálogo tiene ${Ejemplares.CATALOGO.size} ejemplares, fuera del rango pedido",
        )
    }

    @Test
    fun `los nombres del catalogo son unicos`() {
        val nombres = Ejemplares.CATALOGO.map { it.nombre }
        assertEquals(nombres.size, nombres.toSet().size, "hay nombres de ejemplar repetidos: $nombres")
    }

    @Test
    fun `cada ejemplar se interpreta, se aplica sin omisiones y pasa la revision geometrica`() {
        val fallos = StringBuilder()

        for (ejemplar in Ejemplares.CATALOGO) {
            val editor = Editor(Documento.vacio())
            val interpretado = editor.interpretarPlan(ejemplar.plan)

            val plan = interpretado.plan
            if (plan == null) {
                fallos.append(
                    "\n«${ejemplar.nombre}»: el plan se rechazó al interpretar: " +
                        "${interpretado.motivoDelRechazo}",
                )
                continue
            }

            val resultado = editor.aplicarPlan(plan, null)
            if (!resultado.exito) {
                fallos.append("\n«${ejemplar.nombre}»: no se aplicó: ${resultado.resumen}")
                continue
            }
            if (resultado.omitidas.isNotEmpty()) {
                fallos.append("\n«${ejemplar.nombre}»: tiene operaciones omitidas: ${resultado.omitidas}")
                continue
            }

            // La revisión se hace sobre un editor nuevo con el mismo plan, tal y como
            // lo haría el bucle real del modelo: revisarPlan no debe depender de que
            // el documento ya esté aplicado en el editor que lo interpretó.
            val editorDeRevision = Editor(Documento.vacio())
            val planParaRevisar = assertNotNull(
                editorDeRevision.interpretarPlan(ejemplar.plan).plan,
                "«${ejemplar.nombre}»: no se pudo reinterpretar para revisar",
            )
            val revision = editorDeRevision.revisarPlan(planParaRevisar, null)
            if (!revision.aceptable) {
                fallos.append("\n«${ejemplar.nombre}»: la revisión lo rechaza: ${revision.informeParaModelo}")
            }
        }

        assertTrue(fallos.isEmpty(), "Ejemplares que no pasan la verificación:$fallos")
    }

    @Test
    fun `relevantes recupera la caja con tapa y no la brida para una peticion de caja`() {
        val resultado = Ejemplares.relevantes("necesito una caja con tapa para guardar tornillos", cuantos = 2)

        assertTrue(resultado.isNotEmpty(), "no devolvió ningún ejemplar")
        assertTrue(
            resultado.any { "caja" in it.etiquetas || "caja" in it.nombre.lowercase() },
            "no aparece un ejemplar de caja entre los recuperados: ${resultado.map { it.nombre }}",
        )
        assertTrue(
            resultado.none { "brida" in it.etiquetas },
            "una petición de caja recuperó la brida: ${resultado.map { it.nombre }}",
        )
    }

    @Test
    fun `relevantes recupera la brida para una peticion de brida con agujeros`() {
        val resultado = Ejemplares.relevantes("una brida circular con agujeros para atornillar a un motor", cuantos = 2)

        assertTrue(
            resultado.any { "brida" in it.etiquetas || "brida" in it.nombre.lowercase() },
            "no aparece la brida entre los recuperados: ${resultado.map { it.nombre }}",
        )
    }

    @Test
    fun `las peticiones normalizadas encuentran el ejemplar que usa patron`() {
        // Sin esto, `patron` está en el prompt pero el modelo no lo ve aplicado nunca,
        // y un modelo pequeño no echa mano de una operación que solo ha visto nombrada
        // en una lista. Que el ejemplar exista no basta: tiene que salir elegido.
        for ((peticion, esperado) in listOf(
            "necesito unas orejas para montar esto en el rack de 19 pulgadas" to "Oreja de rack",
            "un adaptador vesa para colgar el monitor" to "Adaptador VESA",
            "una base para atornillar la raspberry pi" to "Base para Raspberry Pi",
        )) {
            val nombres = Ejemplares.relevantes(peticion, cuantos = 2).map { it.nombre }
            assertTrue(esperado in nombres, "«$peticion» debería traer «$esperado» y trajo $nombres")
        }
    }

    @Test
    fun `relevantes con una peticion sin relacion no revienta y devuelve como mucho lo pedido`() {
        val resultado = Ejemplares.relevantes("xyzzy plugh algo que no tiene nada que ver con nada", cuantos = 3)

        assertTrue(resultado.size <= 3, "devolvió más ejemplares de los pedidos")
    }

    @Test
    fun `relevantes respeta el numero pedido`() {
        val resultado = Ejemplares.relevantes("una pieza cualquiera", cuantos = 1)
        assertEquals(1, resultado.size)
    }

    @Test
    fun `relevantes no revienta con una peticion vacia`() {
        val resultado = Ejemplares.relevantes("", cuantos = 2)
        assertTrue(resultado.size <= 2)
    }
}
