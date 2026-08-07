package yunkil.ia

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Qué hizo la persona con la propuesta. Es la etiqueta de calidad. */
@Serializable
enum class Desenlace {
    /** Se aplicó y ahí sigue. */
    APLICADO,

    /** Se rechazó en el diálogo de confirmación. */
    DESCARTADO,

    /**
     * Se aceptó una parte y se descartó el resto.
     *
     * Es un desenlace propio y no un `APLICADO` con matices, porque dice algo que los
     * otros tres no pueden decir: el modelo no acertó ni falló, acertó **a medias**, y
     * las operaciones concretas que sobraron están en [Asiento.rechazadas]. Contarlo
     * como aplicado inflaría el acierto; contarlo como descartado tiraría el trabajo
     * bueno. Es la señal más fina que produce el uso normal de la herramienta.
     */
    PARCIAL,

    /** Se aplicó y se deshizo acto seguido, que es un no más rotundo que el anterior. */
    DESHECHO,
}

/**
 * Una propuesta de la IA con lo que acabó pasando con ella.
 *
 * Guarda la petición y el plan crudo, no el documento resultante: para entrenar
 * hace falta el par «lo que se pidió → lo que se emitió», y el documento se puede
 * reconstruir aplicando el plan cuando haga falta.
 *
 * [momento] lo pone quien registra y no el núcleo. Meter un reloj aquí obligaría a
 * una dependencia de fechas en `commonMain` para un dato que la aplicación ya tiene
 * a mano, y volvería estas funciones imposibles de probar sin trucos.
 */
@Serializable
data class Asiento(
    val id: String,
    val momento: Long,
    val peticion: String = "",
    val plan: String = "",
    val perfil: String = "",
    /** Cuántas vueltas de corrección hicieron falta. Una es a la primera. */
    val rondas: Int = 0,
    /** Lo que la revisión geométrica seguía reprochando al final. */
    val reparos: List<String> = emptyList(),
    val desenlace: Desenlace,
    /**
     * Las operaciones que el usuario desmarcó, ya contadas en español.
     *
     * Es el dato que no se puede fabricar sintéticamente ni deducir del documento
     * final: qué propuso el modelo que a una persona le pareció mal, con la propuesta
     * delante y sin que nadie se lo preguntara. Se guarda la frase de
     * [Explicacion] y no el índice porque un índice no significa nada fuera de su
     * plan, y este archivo se lee meses después.
     */
    val rechazadas: List<String> = emptyList(),
)

/**
 * Registro append-only de propuestas y desenlaces, en JSONL.
 *
 * Un plan que valida no es un plan bueno. La distancia entre las dos cosas no la
 * puede medir el analizador ni ningún revisor geométrico: la marca la persona, al
 * quedarse la pieza o al deshacerla. Ese dato hay que recogerlo **mientras pasa**,
 * porque no se reconstruye a posteriori, y es lo que el día de mañana convierte un
 * afinado de modelo en algo con criterio y no solo con formato.
 *
 * El formato es una línea por asiento para que añadir sea abrir, escribir al final
 * y cerrar. Un registro que hay que releer entero para crecer se corrompe el día
 * que la aplicación se cierra a mitad.
 */
object Bitacora {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun aLinea(asiento: Asiento): String = json.encodeToString(Asiento.serializer(), asiento)

    /**
     * Reduce las líneas a un asiento por propuesta, quedándose con el último.
     *
     * El desenlace se corrige después de escribirlo —se aplica y se deshace— así que
     * la última palabra sobre un id es la que vale. Las líneas ilegibles se saltan
     * en silencio: el registro es un instrumento de medida, no una fuente de fallos,
     * y tirar la aplicación abajo porque alguien abrió el fichero sería absurdo.
     */
    fun consolidar(lineas: List<String>): List<Asiento> {
        val porId = LinkedHashMap<String, Asiento>()
        for (linea in lineas) {
            val limpia = linea.trim()
            if (limpia.isEmpty()) continue
            val asiento = try {
                json.decodeFromString(Asiento.serializer(), limpia)
            } catch (e: Exception) {
                continue
            }
            porId[asiento.id] = asiento
        }
        return porId.values.toList()
    }

    /** Cuenta cuántas propuestas acabaron en cada desenlace. */
    fun recuento(asientos: List<Asiento>): Map<Desenlace, Int> =
        Desenlace.entries.associateWith { d -> asientos.count { it.desenlace == d } }
}
