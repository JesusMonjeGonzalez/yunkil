package yunkil.kernel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class Punto2(val x: Float, val y: Float) {
    operator fun plus(o: Punto2) = Punto2(x + o.x, y + o.y)
    operator fun minus(o: Punto2) = Punto2(x - o.x, y - o.y)
    operator fun times(s: Float) = Punto2(x * s, y * s)

    fun longitud() = sqrt(x * x + y * y)
    fun punto(o: Punto2) = x * o.x + y * o.y
    /** Producto cruzado 2D: el signo dice a qué lado queda `o`. */
    fun cruz(o: Punto2) = x * o.y - y * o.x

    companion object {
        val CERO = Punto2(0f, 0f)
    }
}

/**
 * Un tramo del contorno. El punto de partida es el final del tramo anterior, así
 * que solo se guarda a dónde va: es como se dibuja un boceto y evita que dos
 * tramos consecutivos puedan discrepar sobre dónde se tocan.
 */
@Serializable
sealed interface Segmento2D {
    val hasta: Punto2
}

@Serializable
@SerialName("linea")
data class Linea(override val hasta: Punto2) : Segmento2D

/**
 * Arco de circunferencia por radio, como se acota en un plano.
 *
 * `radio` negativo o `mayor` cambian cuál de los cuatro arcos posibles entre dos
 * puntos se toma. Es la misma convención que el arco elíptico de SVG, que es la
 * que ya conoce cualquiera que haya tocado un contorno vectorial.
 */
@Serializable
@SerialName("arco")
data class Arco(
    override val hasta: Punto2,
    val radio: Float,
    /** `true` toma el arco largo de los dos posibles. */
    val mayor: Boolean = false,
    /** `true` gira en sentido horario. */
    val horario: Boolean = false,
) : Segmento2D

@Serializable
@SerialName("bezier")
data class Bezier(
    val control1: Punto2,
    val control2: Punto2,
    override val hasta: Punto2,
) : Segmento2D

/**
 * Un contorno cerrado en el plano.
 *
 * **Por qué esto y no un solucionador de restricciones**: un boceto paramétrico
 * de verdad —con paralelismos, tangencias y concentricidades que se resuelven por
 * Newton— es un subsistema tan grande como el resto del programa junto. Aquí se
 * acota cada tramo con números y se colocan los puntos con imanes de
 * ortogonalidad; lo que no llegue por ahí lo cubre una orden en lenguaje natural,
 * que ya sabe convertir «hazlo simétrico» en coordenadas.
 *
 * Para evaluar, el contorno se convierte en un polígono. Las líneas salen exactas
 * por construcción; los arcos y las bézier se aproximan, y **la desviación se
 * mide y se publica** en vez de esconderse: `desviacionMaxima` dice en milímetros
 * cuánto se separa el polígono de la curva ideal, y es varios órdenes de magnitud
 * menor que la boquilla de cualquier impresora.
 */
@Serializable
data class Perfil2D(
    val origen: Punto2 = Punto2.CERO,
    val segmentos: List<Segmento2D> = emptyList(),
    /** Redondeo de todas las esquinas del contorno. */
    val redondeo: Float = 0f,
) {

    /**
     * El contorno convertido en polígono, en orden y sin repetir el punto inicial.
     * Se calcula una vez y se reutiliza: es la representación con la que trabajan
     * la distancia, el signo, el área y el generador de shader.
     */
    val poligono: List<Punto2> by lazy { teselar() }

    val desviacionMaxima: Float by lazy { calcularDesviacion() }

    val esValido: Boolean get() = poligono.size >= 3

    fun cotas(): Pair<Punto2, Punto2> {
        if (poligono.isEmpty()) return Punto2.CERO to Punto2.CERO
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (v in poligono) {
            minX = min(minX, v.x); minY = min(minY, v.y)
            maxX = max(maxX, v.x); maxY = max(maxY, v.y)
        }
        val margen = max(redondeo, 0f)
        return Punto2(minX - margen, minY - margen) to Punto2(maxX + margen, maxY + margen)
    }

    /** Área con signo. Negativa si el contorno va en sentido horario. */
    fun areaConSigno(): Float {
        if (poligono.size < 3) return 0f
        var doble = 0.0
        for (i in poligono.indices) {
            val a = poligono[i]
            val b = poligono[(i + 1) % poligono.size]
            doble += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return (doble / 2.0).toFloat()
    }

    fun area(): Float = abs(areaConSigno())

    fun perimetro(): Float {
        if (poligono.size < 2) return 0f
        var total = 0f
        for (i in poligono.indices) {
            total += (poligono[(i + 1) % poligono.size] - poligono[i]).longitud()
        }
        return total
    }

    /**
     * Distancia con signo al contorno: negativa dentro, positiva fuera.
     *
     * La distancia se toma como la menor a cualquier arista, y el signo lo decide
     * el número de cruces del rayo horizontal, no el sentido de giro. Así un
     * perfil dibujado al revés no sale del revés, que es el error que más se
     * comete al escribir coordenadas a mano.
     *
     * @param xDelEje abscisa del eje de revolución, o [SIN_EJE] si el perfil no va a
     *   girar. Las aristas que caen enteras sobre esa vertical **no cuentan para la
     *   distancia**: al girar no barren superficie, se colapsan en el propio eje. Sin
     *   esta excepción, un perfil que llega al eje —lo normal en cualquier pieza
     *   maciza torneada— mide cero justo en el eje, y ahí el mallador ve un cambio de
     *   signo donde no hay superficie y suelta astillas. Siguen contando para la
     *   paridad del rayo, que es lo que dice si el punto está dentro.
     */
    fun evaluar(p: Punto2, xDelEje: Float = SIN_EJE): Float {
        val n = poligono.size
        if (n < 3) return Float.MAX_VALUE

        var distanciaCuadrado = Float.MAX_VALUE
        var dentro = false

        for (i in 0 until n) {
            val a = poligono[i]
            val b = poligono[(i + 1) % n]

            if (!enElEje(a, b, xDelEje)) {
                val arista = b - a
                val hacia = p - a
                val t = (hacia.punto(arista) / max(arista.punto(arista), 1e-20f)).coerceIn(0f, 1f)
                val cercano = hacia - arista * t
                distanciaCuadrado = min(distanciaCuadrado, cercano.punto(cercano))
            }

            // Cruce del rayo horizontal hacia +x: cada arista que lo cruza cambia
            // la paridad. Comparar con `!=` evita contar dos veces un vértice.
            if ((a.y > p.y) != (b.y > p.y)) {
                val x = a.x + (p.y - a.y) / (b.y - a.y) * (b.x - a.x)
                if (p.x < x) dentro = !dentro
            }
        }

        val d = sqrt(distanciaCuadrado)
        return (if (dentro) -d else d) - redondeo
    }

    private fun enElEje(a: Punto2, b: Punto2, xDelEje: Float): Boolean =
        xDelEje != SIN_EJE && abs(a.x - xDelEje) <= TOLERANCIA_DEL_EJE &&
            abs(b.x - xDelEje) <= TOLERANCIA_DEL_EJE

    // ------------------------------------------------------------------ teselado

    private fun teselar(): List<Punto2> {
        if (segmentos.isEmpty()) return emptyList()
        val salida = ArrayList<Punto2>(segmentos.size * 4)
        salida.add(origen)
        var actual = origen

        for (segmento in segmentos) {
            when (segmento) {
                is Linea -> salida.add(segmento.hasta)
                is Arco -> salida.addAll(puntosDeArco(actual, segmento).drop(1))
                is Bezier -> salida.addAll(puntosDeBezier(actual, segmento).drop(1))
            }
            actual = segmento.hasta
        }

        // El contorno es cerrado por definición: si el último punto coincide con el
        // primero se quita, porque el polígono ya cierra solo.
        if (salida.size > 1 && (salida.last() - salida.first()).longitud() < 1e-5f) {
            salida.removeAt(salida.size - 1)
        }
        return if (salida.size > MAXIMO_DE_VERTICES) simplificar(salida) else salida
    }

    /**
     * Puntos de un arco entre dos extremos, dado el radio.
     *
     * Si el radio pedido no alcanza a unir los dos puntos se agranda al mínimo
     * posible en vez de rechazar el segmento: es un error de acotación muy común y
     * dejar el perfil roto sería peor que dibujar el semicírculo que sí cabe.
     */
    private fun puntosDeArco(desde: Punto2, arco: Arco): List<Punto2> {
        val cuerda = arco.hasta - desde
        val mitad = cuerda.longitud() * 0.5f
        if (mitad < 1e-6f) return listOf(desde, arco.hasta)

        val radio = max(abs(arco.radio), mitad)
        val alturaDelCentro = sqrt(max(radio * radio - mitad * mitad, 0f))
        val medio = (desde + arco.hasta) * 0.5f
        val normal = Punto2(-cuerda.y, cuerda.x) * (1f / cuerda.longitud())
        val signo = if (arco.horario != arco.mayor) 1f else -1f
        val centro = medio + normal * (alturaDelCentro * signo)

        var desdeAngulo = atan2(desde.y - centro.y, desde.x - centro.x)
        var hastaAngulo = atan2(arco.hasta.y - centro.y, arco.hasta.x - centro.x)
        var barrido = hastaAngulo - desdeAngulo
        val dosPi = (2 * PI).toFloat()
        while (barrido <= -PI.toFloat()) barrido += dosPi
        while (barrido > PI.toFloat()) barrido -= dosPi
        if (arco.horario && barrido > 0f) barrido -= dosPi
        if (!arco.horario && barrido < 0f) barrido += dosPi

        val pasos = pasosParaArco(radio, abs(barrido))
        val salida = ArrayList<Punto2>(pasos + 1)
        for (i in 0..pasos) {
            val a = desdeAngulo + barrido * (i.toFloat() / pasos)
            salida.add(Punto2(centro.x + radio * cos(a), centro.y + radio * sin(a)))
        }
        return salida
    }

    private fun puntosDeBezier(desde: Punto2, curva: Bezier): List<Punto2> {
        // Cota superior de la longitud: el polígono de control. Basta para elegir
        // cuántos trozos hacen falta sin resolver ninguna integral.
        val cota = (curva.control1 - desde).longitud() +
            (curva.control2 - curva.control1).longitud() +
            (curva.hasta - curva.control2).longitud()
        val pasos = min(max(ceil(cota / max(TOLERANCIA * 12f, 1e-4f)).toInt(), 4), 96)
        val salida = ArrayList<Punto2>(pasos + 1)
        for (i in 0..pasos) {
            val t = i.toFloat() / pasos
            val s = 1f - t
            salida.add(
                desde * (s * s * s) +
                    curva.control1 * (3f * s * s * t) +
                    curva.control2 * (3f * s * t * t) +
                    curva.hasta * (t * t * t),
            )
        }
        return salida
    }

    /**
     * Cuántos trozos necesita un arco para que la flecha —la separación máxima
     * entre la cuerda y el arco— quede por debajo de la tolerancia.
     */
    private fun pasosParaArco(radio: Float, barrido: Float): Int {
        if (radio <= 0f || barrido <= 0f) return 1
        val coseno = (1f - TOLERANCIA / radio).coerceIn(-1f, 1f)
        val anguloPorTrozo = 2f * acos(coseno)
        if (anguloPorTrozo <= 1e-4f) return MAXIMO_POR_CURVA
        return min(max(ceil(barrido / anguloPorTrozo).toInt(), 2), MAXIMO_POR_CURVA)
    }

    private fun calcularDesviacion(): Float {
        var peor = 0f
        var actual = origen
        for (segmento in segmentos) {
            when (segmento) {
                is Linea -> {}
                is Arco -> {
                    val puntos = puntosDeArco(actual, segmento)
                    val radio = max(abs(segmento.radio), (segmento.hasta - actual).longitud() * 0.5f)
                    if (puntos.size >= 2) {
                        val cuerda = (puntos[1] - puntos[0]).longitud()
                        val mitad = cuerda * 0.5f
                        peor = max(peor, radio - sqrt(max(radio * radio - mitad * mitad, 0f)))
                    }
                }
                // Para una bézier no hay fórmula cerrada barata; se acota con el
                // paso usado, que es conservador por construcción.
                is Bezier -> peor = max(peor, TOLERANCIA)
            }
            actual = segmento.hasta
        }
        return peor
    }

    /**
     * Recorta el número de vértices quedándose con los que más forma aportan.
     *
     * El tope existe porque el shader desenrolla el polígono: sin él, un perfil con
     * muchas curvas generaría un bucle enorme y el compilador de Metal tardaría
     * segundos o se rendiría.
     */
    private fun simplificar(puntos: List<Punto2>): List<Punto2> {
        val paso = puntos.size.toFloat() / MAXIMO_DE_VERTICES
        val salida = ArrayList<Punto2>(MAXIMO_DE_VERTICES)
        var cursor = 0f
        while (salida.size < MAXIMO_DE_VERTICES) {
            salida.add(puntos[min(cursor.toInt(), puntos.size - 1)])
            cursor += paso
        }
        return salida
    }

    companion object {
        /** Separación máxima consentida entre el polígono y la curva ideal, en mm. */
        const val TOLERANCIA = 0.01f
        const val MAXIMO_DE_VERTICES = 256
        const val MAXIMO_POR_CURVA = 128

        /** El perfil no gira: ninguna arista se colapsa y todas cuentan. */
        const val SIN_EJE = 3.4e38f

        /**
         * Cuánto puede separarse del eje una arista y seguir contando como suya.
         *
         * 10 nm: cuatro órdenes de magnitud por debajo de lo que imprime una boquilla,
         * así que ninguna pared real cae dentro, y muy por encima del ruido que dejan
         * el teselado de un arco o el ida y vuelta a JSON.
         */
        const val TOLERANCIA_DEL_EJE = 1e-5f

        // -------------------------------------------------------------- catálogo

        /** Contorno a partir de puntos sueltos, que es lo que emite un modelo. */
        fun poligono(puntos: List<Punto2>, redondeo: Float = 0f): Perfil2D {
            if (puntos.isEmpty()) return Perfil2D()
            return Perfil2D(
                origen = puntos.first(),
                segmentos = puntos.drop(1).map { Linea(it) } + Linea(puntos.first()),
                redondeo = redondeo,
            )
        }

        fun rectangulo(ancho: Float, alto: Float, redondeo: Float = 0f): Perfil2D {
            val a = ancho * 0.5f
            val b = alto * 0.5f
            // El redondeo se aplica como radio del campo, así que el rectángulo se
            // encoge para que la medida exterior siga siendo la pedida.
            val r = redondeo.coerceIn(0f, min(a, b) * 0.999f)
            return poligono(
                listOf(
                    Punto2(-a + r, -b + r), Punto2(a - r, -b + r),
                    Punto2(a - r, b - r), Punto2(-a + r, b - r),
                ),
                redondeo = r,
            )
        }

        /**
         * Círculo con los lados que hagan falta para respetar la tolerancia.
         *
         * Un número fijo de lados es una trampa: 64 sobran para un agujero de 3 mm
         * y se quedan cortos para una brida de 100, donde dejarían escalones de una
         * décima que sí se ven en la pieza impresa.
         */
        fun circulo(radio: Float, lados: Int? = null): Perfil2D {
            val n = (lados ?: ladosParaTolerancia(radio)).coerceIn(3, MAXIMO_DE_VERTICES)
            // Radio compensado: los vértices salen un pelo fuera y los puntos medios
            // de cada lado caen exactamente sobre el círculo. Un polígono inscrito
            // sin compensar deja todos los agujeros pequeños y todos los cilindros
            // finos, siempre en la misma dirección, y ese sesgo se acumula en los
            // encajes. Repartir el error a los dos lados lo cancela.
            return poligonoRegular(n, radio / cos(PI.toFloat() / n))
        }

        /** Lados de un polígono regular para que la flecha quede bajo la tolerancia. */
        fun ladosParaTolerancia(radio: Float): Int {
            if (radio <= TOLERANCIA) return 8
            val coseno = (1f - TOLERANCIA / radio).coerceIn(-1f, 1f)
            val paso = 2f * acos(coseno)
            if (paso <= 1e-5f) return MAXIMO_DE_VERTICES
            return ceil(2f * PI.toFloat() / paso).toInt().coerceIn(8, MAXIMO_DE_VERTICES)
        }

        fun poligonoRegular(lados: Int, radio: Float, giroGrados: Float = 0f): Perfil2D {
            val n = lados.coerceIn(3, MAXIMO_DE_VERTICES)
            val giro = giroGrados * (PI.toFloat() / 180f)
            return poligono(
                (0 until n).map {
                    val a = giro + it * 2f * PI.toFloat() / n
                    Punto2(radio * cos(a), radio * sin(a))
                },
            )
        }

        /**
         * Ranura alargada: dos semicírculos unidos por dos rectas. Es el agujero
         * que permite ajustar la posición de un tornillo, y una de las formas más
         * repetidas en cualquier pieza atornillada.
         */
        fun ranura(largo: Float, ancho: Float): Perfil2D {
            val r = ancho * 0.5f
            val mitad = max((largo - ancho) * 0.5f, 0f)
            return Perfil2D(
                origen = Punto2(-mitad, -r),
                segmentos = listOf(
                    Linea(Punto2(mitad, -r)),
                    Arco(Punto2(mitad, r), radio = r, horario = false),
                    Linea(Punto2(-mitad, r)),
                    Arco(Punto2(-mitad, -r), radio = r, horario = false),
                ),
            )
        }

        /** Escuadra en L, el refuerzo más común de una pieza impresa. */
        fun ele(ancho: Float, alto: Float, grosor: Float): Perfil2D {
            val g = grosor.coerceIn(0.1f, min(ancho, alto) * 0.9f)
            return poligono(
                listOf(
                    Punto2(0f, 0f), Punto2(ancho, 0f), Punto2(ancho, g),
                    Punto2(g, g), Punto2(g, alto), Punto2(0f, alto),
                ),
            )
        }

        fun estrella(puntas: Int, radioExterior: Float, radioInterior: Float): Perfil2D {
            val n = puntas.coerceIn(3, 60)
            val puntos = ArrayList<Punto2>(n * 2)
            for (i in 0 until n * 2) {
                val r = if (i % 2 == 0) radioExterior else radioInterior
                val a = i * PI.toFloat() / n
                puntos.add(Punto2(r * cos(a), r * sin(a)))
            }
            return poligono(puntos)
        }
    }
}
