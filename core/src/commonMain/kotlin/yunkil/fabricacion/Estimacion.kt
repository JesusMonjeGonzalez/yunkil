package yunkil.fabricacion

import kotlin.math.PI
import kotlin.math.round

/**
 * Lo que va a costar —en material, en metros y en dinero— imprimir la pieza.
 *
 * Nadie manda una pieza de 300 g a imprimir sin saberlo, y hasta ahora el informe
 * daba el volumen pero nadie iba con la calculadora a por la densidad. Es la última
 * cifra del circuito: el analizador mide, la estimación traduce.
 *
 * Es una **estimación**, y lo dice en el nombre y en cada cifra: el volumen sale del
 * certificado de la malla —material sólido, sin contar el relleno del laminador,
 * que lo baja—, la densidad es la del filamento macizo y el consumo real añade
 * torres de soporte y purgas de cambio de color. Sirve para decidir si imprimes y
 * con qué material; no para facturar.
 */
@kotlinx.serialization.Serializable
data class EstimacionDeImpresion(
    val material: String,
    /** g/cm³ del filamento macizo. */
    val densidadGcm3: Float,
    /** Precio del material por kg, tal y como lo puso el perfil. */
    val costePorKg: Float,
    /** mm³ de la pieza según el certificado del análisis. */
    val volumenMm3: Float,
    /** La densidad venía de la tabla del material y no del perfil del usuario. */
    val densidadDeTabla: Boolean,
) {
    /** Peso de la pieza maciza, en gramos. */
    val gramos: Float get() = (volumenMm3 / 1000f) * densidadGcm3

    /** Metros de filamento de 1,75 mm que traga la pieza maciza.
     *
     * La sección de 1,75 mm es la que hay en el 99 % de las bobinas; un filamento de
     * 2,85 mm consume menos metros el mismo volumen, y quien lo use sabe contarlo.
     */
    val metrosDeFilamento: Float
        get() {
            val areaMm2 = (PI.toFloat() * 1.75f * 1.75f) / 4f
            return volumenMm3 / areaMm2 / 1000f
        }

    /** Lo que cuesta el material de la pieza, al precio por kg del perfil. */
    val coste: Float get() = (gramos / 1000f) * costePorKg

    fun descripcion(): String {
        val origen = if (densidadDeTabla) "densidad de tabla" else "densidad del perfil"
        return "${cifra(gramos, 1)} g · ${cifra(metrosDeFilamento, 1)} m de 1,75 · " +
            "${cifra(coste, 2)} por pieza ($origen, sin relleno ni soportes)"
    }

    companion object {
        /**
         * Densidades de filamento macizo, en g/cm³. Los plásticos técnicos van entre
         * 1,0 y 1,3; la tabla cubre los que nombran los perfiles de fábrica.
         */
        private val DENSIDADES: Map<String, Float> = mapOf(
            "PLA" to 1.24f,
            "PETG" to 1.27f,
            "PLA/PETG" to 1.25f,
            "ABS" to 1.04f,
            "ASA" to 1.07f,
            "TPU" to 1.21f,
            "PC" to 1.20f,
            "NYLON" to 1.14f,
        )

        /**
         * La estimación para un volumen ya medido. `null` con un volumen sin sentido:
         * la pieza no se ha mallado o el análisis no llegó a medir, y ahí no hay
         * número que inventar.
         */
        fun desde(volumenMm3: Float, perfil: PerfilFabricacion): EstimacionDeImpresion? {
            if (!volumenMm3.isFinite() || volumenMm3 <= 0f) return null
            val densidadPerfil = perfil.densidadMaterial
            val deTabla = densidadPerfil <= 0f
            val densidad = if (deTabla) {
                DENSIDADES[perfil.material.uppercase().trim()] ?: 1.24f
            } else densidadPerfil
            val coste = if (perfil.costePorKg > 0f) perfil.costePorKg else 20f
            return EstimacionDeImpresion(
                material = perfil.material,
                densidadGcm3 = densidad,
                costePorKg = coste,
                volumenMm3 = volumenMm3,
                densidadDeTabla = deTabla,
            )
        }
    }
}

/** Redondeo con [decimales] cifras, sin ceros colgando: la cifra que se enseña. */
internal fun cifra(v: Float, decimales: Int): String {
    var factor = 1f
    repeat(decimales) { factor *= 10f }
    val r = round(v * factor) / factor
    return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
}
