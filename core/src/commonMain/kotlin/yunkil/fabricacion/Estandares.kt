package yunkil.fabricacion

import yunkil.kernel.Punto2

/**
 * Patrones de montaje normalizados, con sus cotas exactas.
 *
 * Es la misma idea que `Roscas` llevada al montaje. «Un soporte de 2U para rack»
 * obliga hoy al modelo a recordar que una unidad son 44,45 mm, que los carriles
 * están a 465,1 mm, y que dentro de cada unidad los agujeros no están repartidos por
 * igual sino a 12,7 – 15,875 – 15,875 mm. Son tres datos que ningún modelo tiene
 * fiablemente en la cabeza, y basta fallar en uno para que la pieza no entre.
 *
 * Aquí la diferencia entre «se parece» y «encaja» es literal: si el patrón está bien,
 * la pieza atornilla; si está mal por medio milímetro, no. Por eso las cotas están
 * escritas en milímetros exactos —convertidas de las pulgadas de la norma, no
 * redondeadas— y por eso esto vive en el núcleo y no en el prompt.
 *
 * Cotas contrastadas contra EIA-310 y VESA FDMI.
 */
data class PatronDeMontaje(
    val nombre: String,
    val descripcion: String,
    /** Designación métrica del tornillo que espera el patrón. */
    val rosca: String,
    /** Centros de los agujeros en milímetros, centrados en el origen. */
    val puntos: List<Punto2>,
)

object Estandares {

    // ---------------------------------------------------------------- rack 19"

    /** Altura de una unidad de rack: 1,75 pulgadas. */
    const val UNIDAD_DE_RACK = 44.45f

    /** Ancho de un panel de 19 pulgadas, de oreja a oreja. */
    const val ANCHO_DE_PANEL = 482.6f

    /** Separación horizontal entre las dos filas de agujeros: 18 5/16". */
    const val SEPARACION_DE_CARRILES = 465.1f

    /** Hueco libre del bastidor. Nada del cuerpo del soporte puede pasarse de aquí. */
    const val HUECO_LIBRE = 450f

    /**
     * Altura real de un panel de `unidades` U.
     *
     * No es `unidades × 44,45`: se le quitan 0,79 mm (1/32") para que dos paneles
     * contiguos no se toquen. Un panel a la cota nominal roza con el de arriba y no
     * llega a asentar, que es el fallo clásico de quien lo calcula de memoria.
     */
    fun alturaDePanel(unidades: Int): Float = unidades * UNIDAD_DE_RACK - 0.79f

    /**
     * Los tres agujeros de cada unidad, medidos desde el borde inferior de esa unidad.
     *
     * El reparto es 12,7 – 15,875 – 15,875 y **no es simétrico**: la separación de
     * media pulgada queda a caballo entre dos unidades, así que el primer agujero de
     * una unidad está a un cuarto de pulgada de su borde, no a la mitad de nada.
     * Suponer que están repartidos por igual es el error que desplaza toda la fila.
     */
    private val ALTURAS_EN_UNA_UNIDAD = listOf(6.35f, 22.225f, 38.1f)

    private fun rack(unidades: Int): PatronDeMontaje {
        val alto = unidades * UNIDAD_DE_RACK
        val puntos = ArrayList<Punto2>(unidades * 6)
        for (u in 0 until unidades) {
            for (altura in ALTURAS_EN_UNA_UNIDAD) {
                val y = u * UNIDAD_DE_RACK + altura - alto * 0.5f
                puntos.add(Punto2(-SEPARACION_DE_CARRILES * 0.5f, y))
                puntos.add(Punto2(SEPARACION_DE_CARRILES * 0.5f, y))
            }
        }
        return PatronDeMontaje(
            nombre = "RACK_19",
            descripcion = "Rack de 19\" EIA-310, $unidades U: ${puntos.size} agujeros, " +
                "panel de ${ANCHO_DE_PANEL} × ${alturaDePanel(unidades)} mm",
            rosca = "M6",
            puntos = puntos,
        )
    }

    /**
     * Una sola oreja: la columna de agujeros de un lado, centrada en la pieza.
     *
     * Existe porque el panel entero **no cabe en un plato normal**: 482,6 mm frente a
     * los 256 de una P1S, y el analizador lo rechaza con razón. Lo que la gente
     * imprime de verdad son las dos orejas, y las atornilla a un travesaño comprado o
     * a una bandeja partida. Un patrón que solo se puede usar en una impresora que casi
     * nadie tiene no es un patrón útil.
     */
    private fun orejaDeRack(unidades: Int): PatronDeMontaje {
        val alto = unidades * UNIDAD_DE_RACK
        val puntos = ArrayList<Punto2>(unidades * 3)
        for (u in 0 until unidades) {
            for (altura in ALTURAS_EN_UNA_UNIDAD) {
                puntos.add(Punto2(0f, u * UNIDAD_DE_RACK + altura - alto * 0.5f))
            }
        }
        return PatronDeMontaje(
            nombre = "RACK_19_OREJA",
            descripcion = "Oreja de rack de $unidades U: ${puntos.size} agujeros M6 en una " +
                "columna. La pieza debe medir ${alturaDePanel(unidades)} mm de alto.",
            rosca = "M6",
            puntos = puntos,
        )
    }

    // ---------------------------------------------------------------- VESA

    private fun vesa(nombre: String, ancho: Float, alto: Float, rosca: String) = PatronDeMontaje(
        nombre = nombre,
        descripcion = "VESA ${ancho.toInt()}×${alto.toInt()} con $rosca",
        rosca = rosca,
        puntos = listOf(
            Punto2(-ancho * 0.5f, -alto * 0.5f), Punto2(ancho * 0.5f, -alto * 0.5f),
            Punto2(-ancho * 0.5f, alto * 0.5f), Punto2(ancho * 0.5f, alto * 0.5f),
        ),
    )

    // ---------------------------------------------------------------- placas

    /**
     * Patrón de una placa de circuito, medido **respecto a una base del tamaño de la
     * placa**, no respecto a la pieza que se esté taladrando.
     *
     * Se hace así porque el patrón de una Raspberry Pi grande **no está centrado**:
     * los cuatro agujeros van a 3,5 mm de los bordes, y como la placa es de 85 mm de
     * ancho con 58 entre agujeros, queda 3,5 mm por un lado y 23,5 por el otro —el de
     * los USB—. Una carcasa hecha suponiendo que está centrado no cierra, y es
     * exactamente el fallo que se ve en la mitad de los modelos que circulan.
     */
    private fun placa(
        nombre: String,
        anchoPlaca: Float,
        fondoPlaca: Float,
        separacionX: Float,
        separacionY: Float,
        margen: Float,
        rosca: String,
    ): PatronDeMontaje {
        val x0 = margen - anchoPlaca * 0.5f
        val y0 = margen - fondoPlaca * 0.5f
        return PatronDeMontaje(
            nombre = nombre,
            descripcion = "$nombre: placa de ${anchoPlaca}×${fondoPlaca} mm, agujeros " +
                "${separacionX}×${separacionY} a $margen mm de los bordes, $rosca",
            rosca = rosca,
            puntos = listOf(
                Punto2(x0, y0), Punto2(x0 + separacionX, y0),
                Punto2(x0, y0 + separacionY), Punto2(x0 + separacionX, y0 + separacionY),
            ),
        )
    }

    // ---------------------------------------------------------------- catálogo

    /**
     * Devuelve el patrón, o `null` si no existe.
     *
     * [unidades] solo lo usa el rack; el resto lo ignoran. Se pasa siempre para que
     * la firma no dependa del estándar y el aplicador no tenga que saber cuál es cuál.
     */
    fun porNombre(nombre: String, unidades: Int = 1): PatronDeMontaje? =
        when (nombre.trim().uppercase().replace("-", "_").replace(" ", "_")) {
            "RACK_19", "RACK", "EIA_310", "EIA310", "RACK19" ->
                rack(unidades.coerceIn(1, 48))

            "RACK_19_OREJA", "OREJA", "OREJA_RACK", "RACK_EAR", "EAR" ->
                orejaDeRack(unidades.coerceIn(1, 48))

            "VESA_75", "VESA75", "MIS_D_75" -> vesa("VESA_75", 75f, 75f, "M4")
            "VESA_100", "VESA100", "MIS_D_100", "VESA" -> vesa("VESA_100", 100f, 100f, "M4")
            "VESA_200X100", "VESA200X100", "MIS_E" -> vesa("VESA_200X100", 200f, 100f, "M4")
            "VESA_200", "VESA200", "MIS_F" -> vesa("VESA_200", 200f, 200f, "M6")
            "VESA_400", "VESA400" -> vesa("VESA_400", 400f, 400f, "M8")

            "RASPBERRY_PI", "RPI", "RASPBERRY", "RPI4", "RPI5", "RASPBERRY_PI_5",
            "RASPBERRY_PI_4", "PI4", "PI5" ->
                placa("RASPBERRY_PI", 85f, 56f, 58f, 49f, 3.5f, "M2.5")

            "RASPBERRY_PI_ZERO", "RPI_ZERO", "PI_ZERO", "ZERO" ->
                placa("RASPBERRY_PI_ZERO", 65f, 30f, 58f, 23f, 3.5f, "M2.5")

            else -> null
        }

    val designaciones: String
        get() = "RACK_19 y RACK_19_OREJA (con «unidades»), VESA_75, VESA_100, VESA_200X100, VESA_200, " +
            "VESA_400, RASPBERRY_PI, RASPBERRY_PI_ZERO"

    /** Las cotas que el modelo necesita para dimensionar la pieza, no solo taladrarla. */
    fun resumenParaModelo(): String = """
- RACK_19: 1U = $UNIDAD_DE_RACK mm. Panel de n U: ${ANCHO_DE_PANEL} mm de ancho por
  n×$UNIDAD_DE_RACK−0,79 mm de alto (1U = ${alturaDePanel(1)}, 2U = ${alturaDePanel(2)},
  3U = ${alturaDePanel(3)}). Agujeros M6 en dos filas separadas $SEPARACION_DE_CARRILES mm.
  El cuerpo que entra en el bastidor no puede pasar de $HUECO_LIBRE mm de ancho.
- RACK_19_OREJA: la columna de tres agujeros de **un solo lado**, centrada en la pieza.
  Es lo que hay que usar casi siempre: el panel entero mide 482,6 mm y no cabe en un
  plato corriente, así que se imprimen las dos orejas y se atornillan a un travesaño.
  La oreja debe medir n×44,45−0,79 mm de alto.
- VESA_75 y VESA_100: cuadrado de 75 o 100 mm, M4. VESA_200X100: 200 ancho × 100 alto, M4.
- VESA_200: cuadrado de 200 mm, M6. VESA_400: cuadrado de 400 mm, M8.
- RASPBERRY_PI (4 y 5): placa de 85×56 mm, agujeros M2.5 separados 58×49 mm a 3,5 mm
  de los bordes. OJO: **no está centrado a lo ancho** —sobran 3,5 mm por un lado y
  23,5 por el de los USB—. Haz la base de 85×56 antes de aplicar el patrón, porque
  las cotas se miden respecto a una placa de ese tamaño.
- RASPBERRY_PI_ZERO: placa de 65×30 mm, agujeros M2.5 separados 58×23 mm, sí centrado.
""".trimIndent()
}
