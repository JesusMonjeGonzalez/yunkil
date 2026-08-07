package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El hilo es lo que permite decir «más grueso» sin redescribir la pieza. Lo que se
 * comprueba aquí es que quepa en el contexto y que no mienta.
 */
class ConversacionTest {

    private fun hiloDe(vueltas: Int): Conversacion {
        var c = Conversacion.VACIA
        for (i in 1..vueltas) {
            c = c.con(Turno(Rol.PERSONA, "una caja de $i cm con tapa y agujeros para tornillos"))
            c = c.con(
                Turno(
                    rol = Rol.YUNKIL,
                    texto = "",
                    desenlace = Desenlace.APLICADO,
                    aplicadas = listOf("Crea Base de $i × $i × $i mm", "Taladra Base: agujero M3 de paso"),
                ),
            )
        }
        return c
    }

    @Test
    fun `un hilo vacio no dice nada`() {
        assertEquals("", Conversacion.VACIA.paraModelo())
    }

    @Test
    fun `los dos ultimos turnos van enteros`() {
        val texto = hiloDe(3).paraModelo(presupuesto = 4000)
        // La última petición y lo que se aplicó con ella, con sus cotas.
        assertContains(texto, "una caja de 3 cm")
        assertContains(texto, "Crea Base de 3 × 3 × 3 mm")
    }

    @Test
    fun `los turnos viejos se colapsan a una linea`() {
        val texto = hiloDe(5).paraModelo(presupuesto = 4000)
        // El primero está, pero resumido: no aparecen sus operaciones una a una.
        assertContains(texto, "Pidió: una caja de 1 cm")
        assertFalse(
            texto.contains("Crea Base de 1 × 1 × 1 mm"),
            "un turno viejo no debería traer sus operaciones enteras:\n$texto",
        )
    }

    /**
     * El límite duro del mensaje de sistema son 14.000 caracteres y el modelo local
     * tiene 16K de contexto. Un hilo que crece sin tope se come el sitio donde el
     * modelo tenía que razonar, y eso no falla ruidosamente: falla con un plan que
     * sale entero y correcto en el razonamiento y se corta antes del JSON.
     */
    @Test
    fun `con veinte vueltas el hilo sigue dentro del presupuesto`() {
        val texto = hiloDe(20).paraModelo(presupuesto = 1200)
        assertTrue(texto.length <= 1400, "el hilo se fue a ${texto.length} caracteres")
        // Y lo que sobrevive es lo último, que es a lo que se refiere una corrección.
        assertContains(texto, "una caja de 20 cm")
    }

    @Test
    fun `lo que el usuario descarto viaja al modelo`() {
        val c = Conversacion.VACIA
            .con(Turno(Rol.PERSONA, "un soporte"))
            .con(
                Turno(
                    rol = Rol.YUNKIL,
                    texto = "",
                    desenlace = Desenlace.PARCIAL,
                    aplicadas = listOf("Crea Base de 40 × 20 × 30 mm"),
                    rechazadas = listOf("Redondea todos los cantos de Base con radio 2 mm"),
                ),
            )
        val texto = c.paraModelo()
        assertContains(texto, "La persona descartó")
        assertContains(texto, "Redondea todos los cantos")
    }

    @Test
    fun `una edicion a mano se declara`() {
        val texto = hiloDe(1).paraModelo(tocadoAMano = true)
        assertContains(texto, "ha editado el modelo a mano")
    }

    @Test
    fun `sin edicion a mano no se dice nada de ediciones`() {
        assertFalse(hiloDe(1).paraModelo(tocadoAMano = false).contains("a mano"))
    }

    // ---------------------------------------------------------------- el Editor

    @Test
    fun `el editor sabe si la persona toco el modelo entre dos turnos`() {
        val e = Editor(Documento.vacio())
        e.anotarPeticion("una caja")
        assertFalse(e.tocadoAManoDesdeElUltimoTurno)

        // Aplicar un plan NO cuenta como edición a mano: lo hace el modelo.
        e.aplicarPlan(PlanDeModelado(operaciones = listOf(Crear(tipo = "CAJA", nombre = "Base"))))
        e.anotarRespuesta("APLICADO", listOf("Crea Base"), emptyList())
        assertFalse(e.tocadoAManoDesdeElUltimoTurno, "aplicar un plan no es editar a mano")

        // Mover la pieza con el inspector sí.
        val id = e.filas().first { it.nombre == "Base" }.id
        e.mover(id = id, x = 5f, y = 0f, z = 0f, absoluto = true)
        assertTrue(e.tocadoAManoDesdeElUltimoTurno, "mover una pieza a mano debería contar")
    }

    @Test
    fun `el contexto va antes que el hilo`() {
        val e = Editor(Documento.vacio())
        e.aplicarPlan(PlanDeModelado(operaciones = listOf(Crear(tipo = "CAJA", nombre = "Base"))))
        e.anotarPeticion("una caja")
        e.anotarRespuesta("APLICADO", listOf("Crea Base de 40 × 20 × 30 mm"), emptyList())

        val texto = e.contextoConHilo()
        val dondeElHilo = texto.indexOf("Lo que se ha hablado antes")
        assertTrue(dondeElHilo > 0, "el hilo no aparece:\n$texto")
        // Los hechos medidos primero, la intención después: si el hilo dijera una cota
        // vieja y el contexto la nueva, el modelo no tiene forma de saber cuál creer.
        assertContains(texto.substring(0, dondeElHilo), "Base")
    }

    @Test
    fun `el hilo sobrevive al guardado y a la apertura`() {
        val e = Editor(Documento.vacio())
        e.anotarPeticion("un soporte de móvil inclinado")
        e.anotarRespuesta("APLICADO", listOf("Crea Base de 80 × 10 × 60 mm"), emptyList())

        val otro = Editor(Documento.vacio())
        assertTrue(otro.desdeJson(e.aJson()))
        assertEquals("un soporte de móvil inclinado", otro.conversacion.ultimaPeticion)
    }
}
