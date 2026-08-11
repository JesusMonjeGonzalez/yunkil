package yunkil.ia

/**
 * Lo que dice el crítico visual después de mirar la pieza.
 *
 * `cumple` decide si el bucle sigue; [mirada] dice qué contarle al usuario, que no es lo
 * mismo: un «no cumple» que no sabe nombrar ningún fallo deja pasar la pieza —así está
 * decidido— pero no es una aprobación, y contarlo como tal sería firmar una revisión que
 * nadie ha hecho.
 */
data class VeredictoVisual(
    val cumple: Boolean,
    val reparos: List<String> = emptyList(),
    val mirada: Mirada = Mirada.APROBADA,
)

/**
 * Qué ha pasado al intentar mirar la pieza.
 *
 * Existe porque «no tengo reparos» y «no he podido mirarla» se contaban igual —una lista
 * vacía— y son cosas muy distintas: la primera es un revisor más que la aprueba, la segunda
 * es un revisor que no estaba. Que el crítico falle abierto es deliberado y sigue siéndolo;
 * lo que no puede es fallar **callado**, porque entonces el usuario cree que se ha revisado
 * algo que nadie ha visto.
 *
 * Es la misma regla que el resto del producto aplica a las medidas: un número sin
 * procedencia es una opinión.
 */
enum class Mirada(val etiqueta: String) {
    APROBADA("mirada: es la pieza que se pidió"),
    CON_REPAROS("mirada: hay algo que no encaja"),

    /** Miró, protestó y no supo decir qué. Pasa, pero no cuenta como aprobada. */
    DUDOSA("mirada: el visor protestó sin decir qué"),

    SIN_DIBUJO("sin mirar: no se pudo dibujar la pieza"),
    SIN_VISOR("sin mirar: no hay ningún modelo con visión disponible"),
    SIN_RESPUESTA("sin mirar: el visor no contestó");

    /** Si de verdad llegó a verse. Lo demás pasa igual, pero no lo ha revisado nadie. */
    val seMiro: Boolean get() = this == APROBADA || this == CON_REPAROS || this == DUDOSA

    /** Si alguien puede decir que la pieza está revisada a la vista. */
    val esAprobacion: Boolean get() = this == APROBADA
}

/**
 * El crítico visual: lo único del bucle que **mira** la pieza en vez de medirla.
 *
 * El revisor determinista sabe si dos sólidos se tocan, si una resta corta y si las
 * cotas cuadran. No sabe si lo que salió es un gancho. Ese fallo —el plan es válido, la
 * geometría está limpia, los números cuadran y la pieza no sirve— es el que se lleva los
 * casos que quedan del banco, y es exactamente el que se ve de un vistazo.
 *
 * Dos decisiones gobiernan el diseño, y las dos son restricciones, no capacidades:
 *
 * 1. **No se le pregunta por medidas.** Una imagen no tiene escala y el núcleo sí sabe
 *    cuánto mide cada cosa: pedirle milímetros a un modelo de visión sería sustituir un
 *    dato exacto por una estimación peor. Se le pregunta por lo que solo se ve —qué
 *    forma es, qué falta, qué está donde no debe—.
 * 2. **Ante la duda, la pieza pasa.** Es la regla que ya dejó escrita el revisor
 *    determinista: un crítico que protesta de más convierte el bucle en un generador de
 *    reintentos infinitos. Aquí se aplica dos veces —se le pide en las instrucciones, y
 *    además un «NO CUMPLE» sin un fallo que nombrar se lee como cumple—, porque lo
 *    primero es una petición y lo segundo es una garantía.
 */
object CriticoVisual {

    /** Cuántos reparos se le devuelven al modelo. Más de tres es una lista de deseos. */
    private const val MAXIMO_DE_REPAROS = 3

    fun instrucciones(peticion: String): String = """
        Eres el revisor de una herramienta de modelado 3D. Vas a ver UNA imagen con
        cuatro vistas de la misma pieza, colocadas en dos filas y separadas por una
        raya clara: arriba el frente y el lado, abajo la planta y una isométrica.

        Esto es lo que se pidió:
        «$peticion»

        Tu único trabajo es decir si la pieza que ves es esa pieza.

        No juzgues medidas. En una imagen no hay escala, y las cotas ya están
        comprobadas con números exactos por otra parte del sistema. Juzga solo lo que
        se ve: qué forma tiene, si le falta alguna parte que la petición pedía, si hay
        algo suelto o en un sitio absurdo, y si serviría para lo que se pidió.

        Empieza SIEMPRE describiendo, en una línea por vista, qué se ve en cada uno de
        los cuatro paneles. Esta parte no es opcional y es la que hace el trabajo:
        preguntado de un vistazo, un agujero negro en mitad de un disco blanco pasa
        desapercibido; obligado a mirar ese panel y contarlo, se ve sin esfuerzo. Mira
        primero, opina después.

        Y responde al final exactamente así:

        VEREDICTO: CUMPLE
        o bien
        VEREDICTO: NO CUMPLE
        - un fallo por línea, empezando por un guion

        Ante la duda, responde CUMPLE. Solo digas NO CUMPLE si puedes nombrar un fallo
        concreto que estés viendo; si no sabes decir qué está mal, es que no está mal.
    """.trimIndent()

    fun instruccionesConReferencia(peticion: String): String = """
        Eres el revisor visual de una herramienta de modelado 3D. Recibirás DOS imágenes:
        1. La referencia original: foto o boceto de la persona.
        2. Cuatro vistas del modelo generado: frente y lado arriba, planta e isométrica abajo.

        Esto es lo que se pidió:
        «$peticion»

        Compara la segunda imagen contra la primera. Comprueba silueta, proporciones,
        número y posición relativa de partes, huecos y detalles funcionales visibles.
        Tolera perspectiva, fondo, iluminación y orientación diferentes. No compares
        píxeles ni colores y no estimes medidas: la escala y las cotas se verifican
        numéricamente en otra parte.

        Describe primero la referencia y después, una línea por panel, las cuatro vistas.
        Responde al final exactamente así:

        VEREDICTO: CUMPLE
        o bien
        VEREDICTO: NO CUMPLE
        - un fallo visible y concreto por línea

        Ante la duda responde CUMPLE. No penalices detalles ocultos ni inventes caras que
        la referencia no muestra.
    """.trimIndent()

    fun leer(respuesta: String): VeredictoVisual {
        val texto = respuesta.lowercase()
        if ("no cumple" !in texto) return VeredictoVisual(cumple = true, mirada = Mirada.APROBADA)

        val reparos = respuesta.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("*") || it.startsWith("•") }
            .map { it.drop(1).trim() }
            .filter { it.length > 3 }
            .take(MAXIMO_DE_REPAROS)
            .toList()

        // Una queja que no sabe decir qué está mal no es un fallo, es un modelo de
        // visión dubitativo. No se le puede pedir al modelo de texto que arregle eso, así
        // que pasa; pero pasa **como dudosa**, no como aprobada.
        if (reparos.isEmpty()) return VeredictoVisual(cumple = true, mirada = Mirada.DUDOSA)

        return VeredictoVisual(cumple = false, reparos = reparos, mirada = Mirada.CON_REPAROS)
    }

    /** Los reparos, tal y como se le cuentan al modelo que tiene que corregir. */
    fun informeParaModelo(veredicto: VeredictoVisual): String =
        "Se ha dibujado la pieza y mirado desde cuatro lados. Lo que se ve no es lo que " +
            "se pidió:\n" + veredicto.reparos.joinToString("\n") { "- $it" }
}
