package yunkil.ia

/**
 * Lo que la petición dice que tiene que medir la pieza, si es que lo dice.
 *
 * Cuando alguien escribe «60 × 40 × 25 mm» eso no es una preferencia de estilo: es un
 * hecho comprobable sobre la pieza que salga, y comprobarlo es barato. Es la mitad
 * fácil de la post-condición de cotas; la otra mitad es medir la pieza.
 *
 * El listón para dar una medida por dicha es alto a propósito. Una petición viene
 * llena de números que no son la cota de la pieza —«cuatro agujeros M3», «2 mm de
 * pared», «40 mm de diámetro»— y confundir uno de esos con el tamaño del conjunto
 * escalaría el modelo entero por un malentendido. Cuando hay duda, no se lee nada:
 * el arnés que no actúa cuesta cero, y el que actúa mal cuesta la pieza.
 */
data class CotasPedidas(
    /**
     * Cotas sin eje asignado: las tres de un «60 × 40 × 25», o la única de un
     * «60 mm de largo». Se emparejan por tamaño con lo que mida la pieza, que evita
     * tener que adivinar si el primer número era el ancho o el fondo.
     */
    val libres: List<Float> = emptyList(),
    /** El eje de una cota nombrada —«8 cm de ancho»—, o `null` si no lo dice. */
    val eje: EjeNombrado? = null,
    val medidaDelEje: Float? = null,
) {
    companion object {

        private const val NUMERO = """\d+(?:[.,]\d+)?"""
        private const val UNIDAD = """(mm|cm|milímetros|milimetros|centímetros|centimetros)"""

        /** «60 × 40 × 25 mm», «60x40x25mm», «60 X 40 X 25». */
        private val TRIPLE = Regex(
            """($NUMERO)\s*[x×*]\s*($NUMERO)\s*[x×*]\s*($NUMERO)\s*$UNIDAD?""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * «8 cm de ancho». La palabra del eje es obligatoria y la lista es cerrada:
         * «de diámetro» gobierna dos ejes a la vez sin decir cuáles, y «de pared» no
         * habla del tamaño de la pieza sino de su chapa.
         */
        private val NOMBRADA = Regex(
            """($NUMERO)\s*$UNIDAD?\s+de\s+(anchura|ancho|altura|alto|profundidad|profundo|fondo|largo|longitud)""",
            RegexOption.IGNORE_CASE,
        )

        fun leer(peticion: String): CotasPedidas? {
            TRIPLE.find(peticion)?.let { m ->
                val factor = factorDe(m.groupValues[4])
                val cotas = (1..3).map { numero(m.groupValues[it]) * factor }
                if (cotas.all { it > 0f }) return CotasPedidas(libres = cotas)
            }

            NOMBRADA.find(peticion)?.let { m ->
                val medida = numero(m.groupValues[1]) * factorDe(m.groupValues[2])
                if (medida <= 0f) return null
                return when (val eje = ejeDe(m.groupValues[3])) {
                    // «Largo» es la dirección mayor de la pieza, y cuál sea depende de
                    // cómo la haya montado el modelo: se guarda sin eje.
                    null -> CotasPedidas(libres = listOf(medida))
                    else -> CotasPedidas(eje = eje, medidaDelEje = medida)
                }
            }
            return null
        }

        private fun numero(texto: String): Float = texto.replace(',', '.').toFloatOrNull() ?: 0f

        private fun factorDe(unidad: String): Float =
            if (unidad.lowercase().startsWith("c")) 10f else 1f

        private fun ejeDe(palabra: String): EjeNombrado? = when (palabra.lowercase()) {
            "ancho", "anchura" -> EjeNombrado.X
            "alto", "altura" -> EjeNombrado.Y
            "profundidad", "profundo", "fondo" -> EjeNombrado.Z
            else -> null
        }
    }
}
