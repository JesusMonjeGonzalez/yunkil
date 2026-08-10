package yunkil.ia

/**
 * Reconoce la respuesta que se fue en un bucle.
 *
 * Un modelo pequeño razonando se atasca repitiendo la misma frase y no para hasta
 * agotar el presupuesto de tokens: cuatro minutos de reloj para no decir nada. Desde
 * fuera eso llega como una respuesta sin JSON, exactamente igual que «no supo hacerlo»,
 * y la ronda de corrección le repite la petición para que se vuelva a desbocar.
 *
 * Se mira **solo el final**, que es donde vive el bucle: un texto que empieza bien y
 * acaba en bucle es el caso normal, no el raro.
 */
object Desbocamiento {

    /** Cuánto del final se examina. Un bucle no necesita más para delatarse. */
    private const val COLA = 600

    /**
     * Cuántas letras seguidas tiene que ocupar la repetición. Una muletilla —«sí, sí,
     * sí»— no es un desbocamiento, y llamarlo así mandaría al modelo un reproche
     * que no le sirve para nada.
     */
    private const val MINIMO_DEL_BUCLE = 120

    /** Y cuántas vueltas. Dos veces es énfasis; cuatro es un bucle. */
    private const val VUELTAS_MINIMAS = 4

    /**
     * El fragmento que el modelo repite, o `null` si la respuesta no está en bucle.
     *
     * Devolver el fragmento y no un booleano tiene un motivo: en el reproche se le
     * enseña lo que estaba repitiendo, que es lo que le hace parar.
     */
    fun detectar(texto: String): String? {
        val cola = texto.takeLast(COLA).trimEnd()
        if (cola.length < MINIMO_DEL_BUCLE) return null

        // De menor a mayor, así que sale la unidad de repetición y no un múltiplo suyo.
        for (periodo in 1..cola.length / VUELTAS_MINIMAS) {
            val vueltas = vueltasAlFinal(cola, periodo)
            if (vueltas >= VUELTAS_MINIMAS && periodo * vueltas >= MINIMO_DEL_BUCLE) {
                return cola.takeLast(periodo).trim().take(60)
            }
        }
        return null
    }

    /** Cuántas veces seguidas cabe el final de [cola] en sí mismo, hacia atrás. */
    private fun vueltasAlFinal(cola: String, periodo: Int): Int {
        var vueltas = 1
        var fin = cola.length - periodo
        while (fin - periodo >= 0 &&
            cola.regionMatches(fin - periodo, cola, cola.length - periodo, periodo)
        ) {
            vueltas++
            fin -= periodo
        }
        return vueltas
    }
}
