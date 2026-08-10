package yunkil

import yunkil.ia.Desbocamiento
import yunkil.ia.Interprete
import yunkil.ia.ResultadoDeInterpretacion
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El modelo pequeño se desboca: entra en bucle repitiendo la misma frase y sigue
 * hasta agotar el presupuesto de tokens. Dos peticiones de cuatro minutos por caso
 * no compran nada, y desde fuera se leen igual que «no supo hacerlo».
 *
 * Distinguir las dos cosas es lo que permite decírselo en la ronda de corrección con
 * palabras que puede usar, en vez de repetirle la petición y que se vuelva a desbocar.
 */
class DesbocamientoTest {

    @Test
    fun `una respuesta normal no se toma por desbocada`() {
        val texto = """
            Voy a hacer una base de 60 × 40 × 8 mm con cuatro agujeros M3 en las esquinas,
            separados 5 mm del borde. Primero la caja, luego el patrón de taladros y al
            final un chaflán suave para que la primera capa despegue bien.
            {"resumen":"Base con cuatro M3","operaciones":[{"op":"crear","tipo":"CAJA"}]}
        """.trimIndent()
        assertNull(Desbocamiento.detectar(texto))
    }

    @Test
    fun `una frase repetida hasta el corte se reconoce`() {
        val texto = "Voy a taladrar la base. " + "y el agujero va en el centro, ".repeat(30)
        val bucle = assertNotNull(Desbocamiento.detectar(texto), "esto es un bucle de manual")
        assertTrue("el agujero" in bucle, "debería citar lo que repite: «$bucle»")
    }

    @Test
    fun `una linea repetida decenas de veces se reconoce`() {
        val texto = "Pensando:\n" + "- comprobar las cotas\n".repeat(40)
        assertNotNull(Desbocamiento.detectar(texto))
    }

    @Test
    fun `un texto corto no basta para hablar de bucle`() {
        // Tres palabras repetidas son una muletilla, no un desbocamiento, y llamarlo
        // bucle mandaría al modelo un reproche que no le sirve de nada.
        assertNull(Desbocamiento.detectar("sí, sí, sí"))
    }

    @Test
    fun `una respuesta desbocada se rechaza diciendo que se repitio`() {
        val respuesta = "Vale, lo pienso. " + "primero mido la pieza y luego la mido otra vez, ".repeat(25)
        val rechazo = assertNotNull(
            Interprete.interpretar(respuesta) as? ResultadoDeInterpretacion.Rechazado,
        )
        assertTrue(
            "repit" in rechazo.motivo || "bucle" in rechazo.motivo,
            "el motivo debería nombrar el bucle en vez de decir solo que falta el JSON: ${rechazo.motivo}",
        )
    }
}
