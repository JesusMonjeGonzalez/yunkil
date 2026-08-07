package yunkil.fabricacion

import kotlinx.serialization.Serializable
import yunkil.kernel.Vec3

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

        fun porNombre(nombre: String): PerfilFabricacion? =
            VERIFICADOS.firstOrNull { it.nombre == nombre }
    }
}
