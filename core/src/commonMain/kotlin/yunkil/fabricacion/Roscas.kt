package yunkil.fabricacion

import kotlinx.serialization.Serializable

/** Para qué se hace el agujero, que es lo que decide su diámetro. */
@Serializable
enum class AjusteDeTaladro(val etiqueta: String) {
    /** El tornillo pasa holgado: agujero de paso ISO 273, serie media. */
    PASANTE("de paso"),

    /** El tornillo muerde el plástico: diámetro de núcleo, sin holgura añadida. */
    ROSCA("roscado"),
}

/**
 * Una rosca métrica ISO con los dos diámetros que hacen falta para taladrar.
 *
 * Están tabulados y no calculados a propósito. La fórmula del núcleo —nominal menos
 * el paso— y la de paso —nominal más un margen por serie— dan valores cercanos a la
 * norma pero no iguales, y el que se lleva la sorpresa es quien intenta meter el
 * tornillo. Estos son los de tabla.
 */
@Serializable
data class RoscaMetrica(
    val designacion: String,
    /** Diámetro exterior nominal del tornillo. */
    val nominal: Float,
    val paso: Float,
    /** Agujero de paso, ISO 273 serie media. */
    val diametroDePaso: Float,
    /** Broca para roscar, ISO 2306. */
    val diametroDeNucleo: Float,
)

/**
 * Catálogo de roscas métricas y cálculo del diámetro a taladrar.
 *
 * Esto es exactamente el tipo de dato que un modelo de lenguaje recuerda mal: dice
 * «3 mm» para un M3 —y entonces el tornillo no entra—, o «3,2» —y baila—. Teniéndolo
 * aquí, el modelo solo tiene que escribir «M3» y acierta siempre.
 */
object Roscas {

    val CATALOGO: List<RoscaMetrica> = listOf(
        RoscaMetrica("M2", 2f, 0.4f, 2.4f, 1.6f),
        RoscaMetrica("M2.5", 2.5f, 0.45f, 2.9f, 2.05f),
        RoscaMetrica("M3", 3f, 0.5f, 3.4f, 2.5f),
        RoscaMetrica("M4", 4f, 0.7f, 4.5f, 3.3f),
        RoscaMetrica("M5", 5f, 0.8f, 5.5f, 4.2f),
        RoscaMetrica("M6", 6f, 1f, 6.6f, 5f),
        RoscaMetrica("M8", 8f, 1.25f, 9f, 6.8f),
        RoscaMetrica("M10", 10f, 1.5f, 11f, 8.5f),
    )

    /** Acepta «M3», «m3», «3» y «M3.0», que es como lo escriben los modelos. */
    fun porDesignacion(texto: String): RoscaMetrica? {
        val limpio = texto.trim().removePrefix("M").removePrefix("m").replace(',', '.')
        val pedido = limpio.toFloatOrNull() ?: return null
        return CATALOGO.firstOrNull { kotlin.math.abs(it.nominal - pedido) < 0.01f }
    }

    val designaciones: String get() = CATALOGO.joinToString(", ") { it.designacion }

    /**
     * Diámetro que hay que vaciar para que el tornillo haga su trabajo.
     *
     * A un agujero de paso se le suma la holgura del perfil **por lado**, porque una
     * impresora FDM cierra los agujeros: el plástico fluye hacia dentro en cada capa
     * y un agujero modelado a 3,4 sale por debajo de 3,2. A uno roscado no se le suma
     * nada, que ahí lo que se busca es justamente que el tornillo muerda.
     */
    fun diametroPara(rosca: RoscaMetrica, ajuste: AjusteDeTaladro, perfil: PerfilFabricacion): Float =
        when (ajuste) {
            AjusteDeTaladro.PASANTE -> rosca.diametroDePaso + 2f * perfil.holguraEncaje
            AjusteDeTaladro.ROSCA -> rosca.diametroDeNucleo
        }
}
