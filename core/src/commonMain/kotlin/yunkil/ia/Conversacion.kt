package yunkil.ia

import kotlinx.serialization.Serializable

@Serializable
enum class Rol { PERSONA, YUNKIL }

/**
 * Un turno del hilo: lo que se pidió, o lo que Yunkil hizo con ello.
 *
 * Guarda **intención**, no geometría. Las cotas viven en el `Contexto`, que se
 * recalcula cada vez leyendo el documento de verdad. Si el hilo también las
 * describiera, las dos versiones se separarían en cuanto el usuario moviera algo a
 * mano, y el modelo creería la vieja: acabaría corrigiendo una pieza que ya no
 * existe con las medidas de otra que tampoco.
 */
@Serializable
data class Turno(
    val rol: Rol,
    val texto: String,
    /** Qué acabó pasando con lo que se propuso en este turno. */
    val desenlace: Desenlace? = null,
    /** Las operaciones que se aplicaron de verdad, ya contadas en español. */
    val aplicadas: List<String> = emptyList(),
    /** Las que el usuario desmarcó. Es la corrección más clara que existe. */
    val rechazadas: List<String> = emptyList(),
)

/**
 * El hilo de la conversación con el modelo.
 *
 * Antes cada petición nacía sin memoria: para decir «más grueso» había que
 * redescribir la pieza entera, porque el modelo no tenía forma de saber a qué se
 * refería «más». Con el hilo, «no, más grueso» es una frase completa.
 *
 * Se guarda en el documento, junto a los planes aplicados, porque una memoria que
 * se pierde al cerrar la ventana no es memoria.
 */
@Serializable
data class Conversacion(val turnos: List<Turno> = emptyList()) {

    fun con(turno: Turno): Conversacion = Conversacion(turnos + turno)

    /** Lo último que se pidió, para saber a qué se refiere un «más grueso». */
    val ultimaPeticion: String? get() = turnos.lastOrNull { it.rol == Rol.PERSONA }?.texto

    val vacia: Boolean get() = turnos.isEmpty()

    /**
     * El hilo tal y como se le cuenta al modelo, dentro de un presupuesto de letras.
     *
     * Por qué hay presupuesto y no se manda entero: el mensaje de sistema ya tiene un
     * tope duro de 14.000 caracteres con una prueba que lo hace fallar, y el modelo
     * local trabaja con 16K de contexto. Cada carácter que gasta el hilo es uno que el
     * modelo no puede gastar razonando, y ya pasó una vez que el plan salía entero y
     * correcto en el razonamiento y se cortaba justo antes del JSON.
     *
     * El reparto: los dos últimos turnos enteros —que son a los que se refiere una
     * corrección— y los anteriores colapsados a una línea. Si aun así no cabe, caen los
     * más viejos primero, porque un hilo recortado por el final perdería justo lo que
     * se está corrigiendo.
     *
     * [tocadoAMano] no es un adorno. Si el usuario movió una pieza entre dos turnos y
     * no se dice, el modelo corrige de memoria una geometría que ya no existe.
     */
    fun paraModelo(presupuesto: Int = 1200, tocadoAMano: Boolean = false): String {
        if (turnos.isEmpty() && !tocadoAMano) return ""

        val recientes = turnos.takeLast(TURNOS_ENTEROS)
        val viejos = turnos.dropLast(recientes.size)

        val lineas = ArrayList<String>()
        for (t in viejos) lineas.add(resumido(t))
        for (t in recientes) lineas.add(entero(t))

        // Caen los viejos hasta que quepa. Contar sobre las líneas ya formadas y no
        // estimar: el que decide si esto entra en el contexto es el número real.
        while (lineas.isNotEmpty() && lineas.sumOf { it.length + 1 } > presupuesto) {
            lineas.removeAt(0)
        }

        return buildString {
            if (lineas.isNotEmpty()) {
                append("Lo que se ha hablado antes en esta pieza:\n")
                for (l in lineas) append(l).append('\n')
            }
            if (tocadoAMano) {
                append(
                    "Aviso: desde el último turno la persona ha editado el modelo a mano. " +
                        "Fíate del documento de arriba, no de lo que se dijo antes.\n",
                )
            }
        }
    }

    private fun entero(t: Turno): String = when (t.rol) {
        Rol.PERSONA -> "· Pidió: ${t.texto}"
        Rol.YUNKIL -> buildString {
            append("· Se aplicó: ")
            append(t.aplicadas.joinToString("; ").ifEmpty { t.texto })
            // Lo que el usuario quitó es la señal más limpia que hay sobre lo que sobra,
            // y se le devuelve al modelo en sus propias palabras: si descartó el chaflán,
            // no conviene que lo vuelva a proponer en el turno siguiente.
            if (t.rechazadas.isNotEmpty()) {
                append(" · La persona descartó: ").append(t.rechazadas.joinToString("; "))
            }
        }
    }

    private fun resumido(t: Turno): String = when (t.rol) {
        Rol.PERSONA -> "· Pidió: ${t.texto.take(90)}"
        Rol.YUNKIL -> "· Se aplicaron ${t.aplicadas.size} operaciones" +
            if (t.rechazadas.isEmpty()) "" else " (${t.rechazadas.size} descartadas)"
    }

    companion object {
        /**
         * Cuántos turnos van sin resumir.
         *
         * Dos: la última petición y lo que se hizo con ella. Es a lo que se refiere una
         * corrección —«más grueso» habla de lo que se acaba de aplicar— y subirlo gasta
         * el presupuesto en turnos que ya no se están corrigiendo.
         */
        const val TURNOS_ENTEROS = 2

        val VACIA = Conversacion()
    }
}
