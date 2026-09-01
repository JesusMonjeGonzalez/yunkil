package yunkil.fabricacion

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import yunkil.kernel.Vec3
import yunkil.malla.escribirArchivo
import yunkil.malla.leerArchivo

/**
 * De dónde salen los umbrales de un perfil. Es lo primero que hay que poder
 * responder cuando un aviso resulta molesto: si el umbral lo puso el fabricante,
 * el usuario o su propia impresora midiendo un cupón.
 *
 * Un aviso sin procedencia es una opinión. Con procedencia es un dato.
 */
@Serializable
enum class OrigenDelPerfil(val etiqueta: String) {
    /** Valores de partida del propio Yunkil, tomados de la documentación del fabricante. */
    VERIFICADO("de fábrica"),

    /** El usuario cambió algún umbral a mano. */
    EDITADO("editado"),

    /** Ajustado midiendo un cupón de calibración impreso en la máquina real. */
    CALIBRADO("calibrado en tu máquina"),
}

/**
 * Los umbrales de una impresora concreta con un material concreto.
 *
 * Todas las medidas están en milímetros y grados. El analizador no tiene ninguna
 * constante propia: cada regla toma su umbral de aquí y lo cita en el aviso, de
 * modo que cambiar de boquilla cambia los avisos sin tocar código.
 */
@Serializable
data class PerfilFabricacion(
    val nombre: String,
    val impresora: String,
    val material: String,

    /** Diámetro de boquilla. Nada más fino que esto llega a existir en la pieza. */
    val boquilla: Float,

    val alturaCapa: Float,

    /** Perímetros por pared. Con menos de estos la pared sale hueca o rayada. */
    val perimetros: Int,

    /**
     * Voladizo máximo medido desde la pared vertical: 0° es un muro, 90° es un techo
     * plano. Por encima de este ángulo la capa siguiente se apoya en aire.
     */
    val anguloVoladizoMaximo: Float,

    /** Área de primera capa por debajo de la cual la pieza se despega. */
    val areaBaseMinima: Float,

    /** Altura dividida por el radio de la base a partir de la cual la pieza vuelca. */
    val esbeltezMaxima: Float,

    /** Holgura que hay que dejar entre dos superficies que deben encajar. */
    val holguraEncaje: Float,

    /** Volumen de impresión útil de la máquina. */
    val volumenDeImpresion: Vec3,

    val origen: OrigenDelPerfil = OrigenDelPerfil.VERIFICADO,

    /**
     * De qué perfil salió este, si salió de otro.
     *
     * Un perfil calibrado es el de fábrica de una máquina concreta con la holgura medida en
     * ella, y saber cuál era importa dos veces: para poder decir «calibrado sobre Bambu A1 ·
     * PETG» en vez de dejar un nombre suelto, y para saber a dónde volver si se borra. Sin
     * esto, olvidar la calibración de una A1 devolvía a la P1S, que es otra impresora.
     */
    val derivadoDe: String? = null,

    /**
     * Densidad del material en g/cm³, para convertir volumen en peso.
     *
     * Cero significa «no la sé»: la estimación la deduce de la tabla por nombre de
     * material y dice que es un valor de tabla, no del usuario.
     */
    val densidadMaterial: Float = 0f,

    /** Precio del material por kilogramo, en la moneda de quien compra el filamento. */
    val costePorKg: Float = 0f,
) {
    init {
        require(boquilla > 0f && boquilla.isFinite()) { "La boquilla debe ser positiva" }
        require(alturaCapa > 0f && alturaCapa <= boquilla) {
            "La altura de capa debe ser positiva y no mayor que la boquilla"
        }
        require(perimetros >= 1) { "Hace falta al menos un perímetro" }
        require(anguloVoladizoMaximo in 0f..90f) { "El voladizo máximo va de 0° a 90°" }
    }

    /** Pared más fina que la impresora puede llenar de verdad. */
    val grosorMinimoPared: Float get() = boquilla * perimetros

    /**
     * Detalle más pequeño que llega a materializarse. Por debajo, el laminador ni
     * siquiera genera recorrido: la geometría desaparece en silencio, que es el
     * fallo más desconcertante de la impresión 3D.
     */
    val detalleMinimo: Float get() = boquilla

    /** Copia con un umbral distinto, marcada como tocada por el usuario. */
    fun editado(cambio: PerfilFabricacion.() -> PerfilFabricacion): PerfilFabricacion =
        cambio().copy(origen = OrigenDelPerfil.EDITADO)

    /**
     * El mismo perfil con la holgura que ha medido un cupón impreso en esta máquina.
     *
     * Toca **una sola cosa**, porque el cupón mide una sola cosa: qué agujero se traga el
     * pasador. Arrastrar con ella la boquilla o el voladizo sería afirmar cosas que nadie
     * ha comprobado bajo una etiqueta que dice «calibrado en tu máquina», y esa etiqueta
     * solo vale mientras se pueda creer.
     *
     * Ver [CuponDeCalibracion].
     */
    fun calibradoCon(holguraMedida: Float): PerfilFabricacion {
        require(holguraMedida.isFinite() && holguraMedida > 0f) {
            "La holgura medida tiene que ser un número positivo, y llegó $holguraMedida"
        }
        return copy(holguraEncaje = holguraMedida, origen = OrigenDelPerfil.CALIBRADO)
    }

    companion object {

        /**
         * Perfiles de partida. Deliberadamente conservadores: un aviso de más
         * molesta, un aviso de menos rompe la confianza en la herramienta para
         * siempre.
         */
        val VERIFICADOS: List<PerfilFabricacion> = listOf(
            PerfilFabricacion(
                nombre = "Bambu P1S · PLA · 0,4",
                impresora = "Bambu Lab P1S",
                material = "PLA",
                boquilla = 0.4f,
                alturaCapa = 0.2f,
                perimetros = 2,
                anguloVoladizoMaximo = 50f,
                areaBaseMinima = 80f,
                esbeltezMaxima = 6f,
                holguraEncaje = 0.2f,
                volumenDeImpresion = Vec3(256f, 256f, 256f),
            ),
            PerfilFabricacion(
                nombre = "Bambu A1 · PETG · 0,4",
                impresora = "Bambu Lab A1",
                material = "PETG",
                boquilla = 0.4f,
                alturaCapa = 0.2f,
                perimetros = 3,
                // El PETG cuelga peor que el PLA: se cae antes.
                anguloVoladizoMaximo = 45f,
                areaBaseMinima = 100f,
                esbeltezMaxima = 5f,
                holguraEncaje = 0.3f,
                volumenDeImpresion = Vec3(256f, 256f, 256f),
            ),
            PerfilFabricacion(
                nombre = "Prusa MK4 · PLA · 0,4",
                impresora = "Prusa MK4",
                material = "PLA",
                boquilla = 0.4f,
                alturaCapa = 0.2f,
                perimetros = 2,
                anguloVoladizoMaximo = 55f,
                areaBaseMinima = 80f,
                esbeltezMaxima = 6f,
                holguraEncaje = 0.2f,
                volumenDeImpresion = Vec3(250f, 210f, 220f),
            ),
            PerfilFabricacion(
                nombre = "Genérica · 0,6 · pieza funcional",
                impresora = "Genérica FDM",
                material = "PLA/PETG",
                boquilla = 0.6f,
                alturaCapa = 0.3f,
                perimetros = 3,
                anguloVoladizoMaximo = 45f,
                areaBaseMinima = 120f,
                esbeltezMaxima = 5f,
                holguraEncaje = 0.3f,
                volumenDeImpresion = Vec3(220f, 220f, 250f),
            ),
        )

        val PREDETERMINADO: PerfilFabricacion get() = VERIFICADOS.first()

        /**
         * Busca entre **todos** los perfiles, no solo entre los de fábrica.
         *
         * Que pase por el catálogo es lo que hace que un perfil calibrado exista de
         * verdad: el nombre del perfil viaja como texto por media aplicación —el informe
         * se pide con él, el analizador aislado lo resuelve en otro hilo— y mientras esta
         * función solo mirara la lista de fábrica, calibrar la máquina y pedir el examen
         * devolvía en silencio el análisis del perfil de partida.
         */
        fun porNombre(nombre: String): PerfilFabricacion? = CatalogoDePerfiles.porNombre(nombre)
    }
}

/**
 * Los perfiles que existen ahora mismo: los de fábrica más los que haya guardado quien
 * calibró su máquina con un cupón.
 *
 * Es estado global, y lo es a propósito. El perfil activo se pasa por su **nombre** a
 * través de toda la aplicación, incluido el editor aislado con el que se analiza en otro
 * hilo; si el catálogo viviera dentro de un editor, ese editor de al lado no sabría
 * resolver el nombre y contestaría con el perfil de fábrica sin decir nada. Se escribe al
 * arrancar y al guardar una calibración, las dos veces desde el hilo de la interfaz.
 *
 * El archivo lo elige quien llama: dónde va la configuración de un usuario es cosa de cada
 * plataforma, y el núcleo no tiene por qué opinar.
 */
object CatalogoDePerfiles {

    private var guardados: List<PerfilFabricacion> = emptyList()

    /**
     * Las versiones anteriores de cada perfil propio, la más reciente primero.
     *
     * Calibrar **pisa**: reemplazar un perfil propio es lo normal al recalibrar, y
     * antes la holgura de hace un mes se perdía para siempre. Como la calibración es
     * el único dato que dice «esta pieza entra en esta máquina», perderlo equivale a
     * volver a empezar. Un perfil de fábrica no tiene historial porque nadie puede
     * cambiarlo, solo copiarlo.
     */
    private var historial: Map<String, List<PerfilFabricacion>> = emptyMap()

    private val formato = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Todos, con los de fábrica primero. */
    val todos: List<PerfilFabricacion> get() = PerfilFabricacion.VERIFICADOS + guardados

    /** Solo los que ha guardado el usuario. */
    val propios: List<PerfilFabricacion> get() = guardados

    fun porNombre(nombre: String): PerfilFabricacion? = todos.firstOrNull { it.nombre == nombre }

    /** Las versiones anteriores de un perfil propio, la más reciente primero. */
    fun historialDe(nombre: String): List<PerfilFabricacion> =
        historial[nombre] ?: emptyList()

    /**
     * Lee el archivo de perfiles propios y devuelve cuántos había.
     *
     * Un archivo que no existe todavía no es un error: es la primera vez que se abre la
     * aplicación. Uno corrupto sí se dice, y no se pierde: se deja como está y se sigue con
     * los de fábrica, porque borrar la calibración de alguien por un carácter de más sería
     * mucho peor que arrancar sin ella.
     */
    fun cargarDesde(ruta: String): Int {
        val texto = leerArchivo(ruta)?.decodeToString() ?: return 0
        guardados = try {
            formato.decodeFromString(ListSerializer(PerfilFabricacion.serializer()), texto)
        } catch (e: Exception) {
            return -1
        }
        return guardados.size
    }

    /**
     * Guarda [perfil] y **archiva el que pisaba**, si lo había.
     *
     * La versión anterior no se borra: pasa al historial, de donde se puede volver
     * con [restaurar]. Es la diferencia entre editar y machacar.
     */
    fun guardar(perfil: PerfilFabricacion, ruta: String, rutaHistorial: String? = null): String? {
        if (PerfilFabricacion.VERIFICADOS.any { it.nombre == perfil.nombre }) {
            return "«${perfil.nombre}» es un perfil de fábrica; dale otro nombre al tuyo"
        }
        val anterior = guardados.firstOrNull { it.nombre == perfil.nombre }
        val nuevos = guardados.filter { it.nombre != perfil.nombre } + perfil
        if (!escribirArchivo(ruta, formato.encodeToString(ListSerializer(PerfilFabricacion.serializer()), nuevos).encodeToByteArray())) {
            return "No se pudo escribir $ruta"
        }
        guardados = nuevos
        if (anterior != null) {
            val versiones = historial[perfil.nombre] ?: emptyList()
            historial = historial + (perfil.nombre to listOf(anterior) + versiones)
            rutaHistorial?.let { escribirHistorial(it) }
        }
        return null
    }

    /**
     * Devuelve el perfil propio a una versión anterior de su historial.
     *
     * El que estaba puesto no se pierde: se archiva como la versión más reciente,
     * porque restaurar también es editar y deshacer un restaurar no existe. [indice]
     * indexa [historialDe]: 0 es la versión archivada más reciente.
     */
    fun restaurar(nombre: String, indice: Int, ruta: String, rutaHistorial: String? = null): String? {
        val versiones = historial[nombre] ?: return "«$nombre» no tiene versiones guardadas"
        if (indice !in versiones.indices) {
            return "«$nombre» no tiene la versión $indice"
        }
        val elegida = versiones[indice]
        val actual = guardados.firstOrNull { it.nombre == nombre }
            ?: return "No hay ningún perfil tuyo llamado «$nombre»"
        val nuevos = guardados.filter { it.nombre != nombre } + elegida
        if (!escribirArchivo(ruta, formato.encodeToString(ListSerializer(PerfilFabricacion.serializer()), nuevos).encodeToByteArray())) {
            return "No se pudo escribir $ruta"
        }
        guardados = nuevos
        historial = historial + (nombre to (listOf(actual) + versiones - elegida).distinct())
        rutaHistorial?.let { escribirHistorial(it) }
        return null
    }

    /** Lee el archivo del historial. Un archivo que no existe es la primera vez, no un error. */
    fun cargarHistorialDesde(ruta: String): Int {
        val texto = leerArchivo(ruta)?.decodeToString() ?: return 0
        historial = try {
            formato.decodeFromString(
                kotlinx.serialization.serializer<Map<String, List<PerfilFabricacion>>>(),
                texto,
            )
        } catch (e: Exception) {
            return -1
        }
        return historial.values.sumOf { it.size }
    }

    private fun escribirHistorial(ruta: String) {
        escribirArchivo(
            ruta,
            formato.encodeToString(
                kotlinx.serialization.serializer<Map<String, List<PerfilFabricacion>>>(),
                historial,
            ).encodeToByteArray(),
        )
    }

    /** Quita un perfil propio. Los de fábrica no se pueden borrar. */
    fun olvidar(nombre: String, ruta: String): String? {
        if (guardados.none { it.nombre == nombre }) return "No hay ningún perfil tuyo llamado «$nombre»"
        val nuevos = guardados.filter { it.nombre != nombre }
        if (!escribirArchivo(ruta, formato.encodeToString(ListSerializer(PerfilFabricacion.serializer()), nuevos).encodeToByteArray())) {
            return "No se pudo escribir $ruta"
        }
        guardados = nuevos
        return null
    }

    /** Vuelve a dejar solo los de fábrica, sin tocar el disco. Para arrancar limpio y para las pruebas. */
    fun vaciar() {
        guardados = emptyList()
        historial = emptyMap()
    }
}
