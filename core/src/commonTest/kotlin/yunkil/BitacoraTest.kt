package yunkil

import yunkil.ia.Asiento
import yunkil.ia.Bitacora
import yunkil.ia.Desenlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pruebas del registro de lo que el usuario acepta y rechaza.
 *
 * Existe para el día que haya que afinar un modelo propio. Un plan válido no es un
 * plan bueno, y la única fuente de «bueno» que no se puede fabricar sintéticamente
 * es si la persona se quedó con la pieza o la deshizo a los dos segundos. Ese dato
 * solo se puede recoger *mientras pasa*: no hay forma de reconstruirlo después.
 */
class BitacoraTest {

    private fun asiento(id: String, desenlace: Desenlace) = Asiento(
        id = id,
        momento = 1_754_300_000L,
        peticion = "un soporte de móvil",
        plan = """{"operaciones":[]}""",
        perfil = "Bambu P1S · PLA · 0,4",
        rondas = 2,
        reparos = listOf("«Tapa» flota en el aire"),
        desenlace = desenlace,
    )

    @Test
    fun `una linea escrita se vuelve a leer igual`() {
        val original = asiento("a1", Desenlace.APLICADO)

        val leidos = Bitacora.consolidar(listOf(Bitacora.aLinea(original)))

        assertEquals(listOf(original), leidos)
    }

    @Test
    fun `deshacer despues de aplicar sustituye al asiento anterior`() {
        // Es el caso que da valor al registro: el usuario dijo que sí y a los dos
        // segundos se arrepintió. Contarlo como aceptado envenenaría el dataset.
        val lineas = listOf(
            Bitacora.aLinea(asiento("a1", Desenlace.APLICADO)),
            Bitacora.aLinea(asiento("a2", Desenlace.APLICADO)),
            Bitacora.aLinea(asiento("a1", Desenlace.DESHECHO)),
        )

        val leidos = Bitacora.consolidar(lineas)

        assertEquals(2, leidos.size, "cada petición debe dejar un solo asiento")
        assertEquals(Desenlace.DESHECHO, leidos.first { it.id == "a1" }.desenlace)
        assertEquals(Desenlace.APLICADO, leidos.first { it.id == "a2" }.desenlace)
    }

    @Test
    fun `una linea corrupta no tira el registro entero`() {
        // El fichero se abre a mano, se corta a mitad de una escritura o se edita.
        // Perder una línea es aceptable; perder el histórico entero no lo es.
        val lineas = listOf(
            Bitacora.aLinea(asiento("a1", Desenlace.APLICADO)),
            "{esto no es json",
            "",
            Bitacora.aLinea(asiento("a2", Desenlace.DESCARTADO)),
        )

        val leidos = Bitacora.consolidar(lineas)

        assertEquals(2, leidos.size, "una línea rota se llevó por delante las buenas")
    }

    @Test
    fun `la linea es de una sola linea para poder anadirla al vuelo`() {
        // El formato es JSONL a propósito: añadir es abrir, escribir al final y
        // cerrar, sin releer ni reescribir lo anterior. Un salto de línea dentro de
        // un asiento rompería esa propiedad y partiría el registro en dos.
        val linea = Bitacora.aLinea(
            asiento("a1", Desenlace.APLICADO).copy(peticion = "una caja\ncon tapa"),
        )

        assertTrue('\n' !in linea, "el asiento lleva un salto de línea dentro: $linea")
    }
}
