package yunkil.imagen

import yunkil.kernel.Aabb
import yunkil.kernel.SdfNode
import yunkil.kernel.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Dibuja el campo de distancias a imagen, sin Metal y sin mallar.
 *
 * Existe por el hueco que el proyecto lleva señalando desde el 4 de agosto: el revisor
 * geométrico mide números —piezas sueltas, restas que no cortan, cotas— y con eso subió
 * el suelo, pero **nadie mira la pieza**. Un modelo que pone el asa en el lado
 * equivocado produce números impecables.
 *
 * Tres decisiones, con motivo:
 *
 * - **En Kotlin puro y no con Metal.** El renderizador de la aplicación vive en Swift,
 *   solo corre en macOS y necesita una ventana. Esto tiene que poder correr en el banco,
 *   en una prueba y en iPad, así que traza el mismo `evaluar` que es la verdad de
 *   referencia del sistema. Es lento y da igual: son cuatro imágenes pequeñas.
 * - **Ortográficas y no en perspectiva.** Quien mira esto compara proporciones; la
 *   perspectiva las falsea justo en la dirección que hace dudar de una cota.
 * - **Gris y no color.** El campo no tiene material. Un color inventado sería
 *   información que no existe, y a un modelo de visión le da algo que interpretar.
 */
object Vistas {

    /** Las cuatro direcciones desde las que se mira, con su vertical. */
    enum class Angulo(val etiqueta: String, val hacia: Vec3, val arriba: Vec3) {
        /** De frente: se ve el plano XY. */
        FRENTE("frente", Vec3(0f, 0f, -1f), Vec3(0f, 1f, 0f)),

        /** De lado, desde la derecha. */
        LADO("lado", Vec3(-1f, 0f, 0f), Vec3(0f, 1f, 0f)),

        /** Desde arriba. La vertical de la imagen pasa a ser Z, o no se vería nada. */
        PLANTA("planta", Vec3(0f, -1f, 0f), Vec3(0f, 0f, -1f)),

        /** La de tres cuartos, que es la única que enseña las tres dimensiones a la vez. */
        ISOMETRICA("isométrica", Vec3(-1f, -1f, -1f), Vec3(0f, 1f, 0f)),
    }

    /** Fondo, para que la silueta se recorte contra algo y no contra negro puro. */
    private const val FONDO: Int = 32

    /**
     * Dibuja una vista suelta.
     *
     * [encuadre] se pasa desde fuera —y es el mismo para las cuatro— porque si cada
     * vista se encuadrara a sí misma, una pieza alargada saldría del mismo tamaño en
     * planta que de frente y las proporciones, que es justo lo que se va a leer,
     * quedarían mintiendo.
     */
    fun dibujar(
        nodo: SdfNode,
        encuadre: Aabb,
        angulo: Angulo,
        lado: Int,
        /** Semilado del cuadro, en milímetros. Compartido por las cuatro vistas. */
        semilado: Float = semiladoPara(encuadre),
    ): ByteArray {
        val pixeles = ByteArray(lado * lado) { FONDO.toByte() }

        val d = normalizar(angulo.hacia)
        val derecha = normalizar(cruz(d, angulo.arriba))
        val arriba = cruz(derecha, d)

        val centro = encuadre.center
        val medio = max(semilado, 1e-3f)
        // La cámara sale de fuera de la esfera envolvente, que sí tiene que ser la del
        // radio conservador: si el origen del rayo cayera dentro de la pieza, la marcha
        // arrancaría con distancia negativa y la vista saldría hueca.
        val partida = max(encuadre.radius, medio) * 2f
        val recorrido = partida * 2f
        // El paso lo fija el píxel: afinar más no añade detalle que se pueda ver, y
        // afinar menos se salta paredes finas, que es el defecto que más importa aquí.
        val pasoMinimo = (medio * 2f) / lado * 0.5f

        for (fila in 0 until lado) {
            // La fila 0 es la de arriba de la imagen, y +arriba va hacia arriba.
            val v = (1f - 2f * (fila + 0.5f) / lado) * medio
            for (columna in 0 until lado) {
                val u = (2f * (columna + 0.5f) / lado - 1f) * medio
                val origen = centro + derecha * u + arriba * v - d * partida

                val impacto = marchar(nodo, origen, d, recorrido, pasoMinimo)
                if (impacto != null) {
                    pixeles[fila * lado + columna] = sombra(nodo, impacto, d, pasoMinimo).toByte()
                }
            }
        }
        return pixeles
    }

    /**
     * Las cuatro vistas en una sola imagen 2×2, ya en PNG.
     *
     * Van juntas porque el destinatario es un modelo con 16K de contexto: cuatro
     * imágenes son cuatro bloques y cuatro veces el coste, y lo que se le pregunta
     * —«¿esto es lo que se pidió?»— se responde mirándolas a la vez.
     *
     * El orden es el del plano de un taller: frente y lado arriba, planta e isométrica
     * abajo.
     */
    fun cuatroVistas(nodo: SdfNode, lado: Int = 320): ByteArray {
        val encuadre = nodo.cotas()
        val semilado = semiladoPara(encuadre)
        val orden = listOf(Angulo.FRENTE, Angulo.LADO, Angulo.PLANTA, Angulo.ISOMETRICA)
        val trozos = orden.map { dibujar(nodo, encuadre, it, lado, semilado) }

        val ancho = lado * 2
        val lienzo = ByteArray(ancho * ancho)
        for ((indice, trozo) in trozos.withIndex()) {
            val desplazamientoX = (indice % 2) * lado
            val desplazamientoY = (indice / 2) * lado
            for (fila in 0 until lado) {
                trozo.copyInto(
                    destination = lienzo,
                    destinationOffset = (desplazamientoY + fila) * ancho + desplazamientoX,
                    startIndex = fila * lado,
                    endIndex = fila * lado + lado,
                )
            }
        }
        // Una raya clara partiendo la imagen en cuatro. Sin ella, dos vistas contiguas de
        // la misma pieza se leen como una sola forma rara.
        for (i in 0 until ancho) {
            lienzo[i * ancho + lado] = 90
            lienzo[lado * ancho + i] = 90
        }
        return Png.gris(ancho, ancho, lienzo)
    }

    /**
     * El semilado del cuadro: lo que ocupa la pieza en la peor de las cuatro vistas.
     *
     * Se comparte entre las cuatro **a propósito**, porque lo que se va a leer en estas
     * imágenes son proporciones: si cada vista se encuadrara sola, una losa saldría del
     * mismo tamaño en planta que de canto y las cuatro imágenes mentirían juntas.
     *
     * Y se calcula proyectando, no con `Aabb.radius`. El radio de la esfera envolvente
     * es el semilado por raíz de tres, así que una esfera ocupaba el 53 % del cuadro y
     * el 47 % restante era fondo. La proyección de una caja de semilados `h` sobre un
     * eje `e` es exactamente `|h·|e||`, y con eso el encuadre es ajustado y sigue sin
     * cortar nada.
     */
    fun semiladoPara(encuadre: Aabb, margen: Float = 1.08f): Float {
        val h = encuadre.size * 0.5f
        var peor = 0f
        for (angulo in Angulo.entries) {
            val d = normalizar(angulo.hacia)
            val derecha = normalizar(cruz(d, angulo.arriba))
            val arriba = cruz(derecha, d)
            for (eje in listOf(derecha, arriba)) {
                val proyectado = h.x * abs(eje.x) + h.y * abs(eje.y) + h.z * abs(eje.z)
                if (proyectado > peor) peor = proyectado
            }
        }
        return max(peor, 1e-3f) * margen
    }

    // ------------------------------------------------------------------- internos

    /** Marcha de esferas. Devuelve el punto de impacto, o `null` si el rayo no toca nada. */
    private fun marchar(
        nodo: SdfNode,
        origen: Vec3,
        direccion: Vec3,
        recorrido: Float,
        pasoMinimo: Float,
    ): Vec3? {
        var t = 0f
        var pasos = 0
        while (t < recorrido && pasos < TOPE_DE_PASOS) {
            val p = origen + direccion * t
            val d = nodo.evaluar(p)
            if (d < pasoMinimo * 0.25f) return p
            // El campo puede no ser 1-Lipschitz —una mezcla de anchura variable no lo es,
            // y está medido en 1,383— así que el avance se frena. Pasarse aquí no da un
            // error: da un agujero en la silueta que parece geometría rota.
            t += max(d * 0.8f, pasoMinimo * 0.1f)
            pasos++
        }
        return null
    }

    /**
     * Lambert con una luz de tres cuartos que acompaña a la cámara.
     *
     * El reparto entre las dos partes está medido mirando las cuatro vistas, no
     * elegido de memoria. Con una luz fija en el mundo, la vista de lado salía **casi
     * negra**: la cara que mira a la cámara tenía la normal en +X y la luz venía de
     * arriba y de frente, así que el producto escalar daba negativo y toda la pieza se
     * quedaba en el ambiente. Una vista negra no es fea, es una de las cuatro imágenes
     * que el modelo no puede usar para nada.
     *
     * El término contra la mirada garantiza que lo que se ve está iluminado; el fijo
     * mantiene la diferencia entre caras, que es lo que hace legible una arista. Solo
     * el primero aplanaría la pieza y todas las caras saldrían del mismo gris.
     */
    private fun sombra(nodo: SdfNode, punto: Vec3, mirada: Vec3, paso: Float): Int {
        val n = gradiente(nodo, punto, paso)
        val luz = normalizar(mirada * -0.6f + Vec3(0.35f, 0.8f, 0.3f))
        val difusa = max(0f, n.x * luz.x + n.y * luz.y + n.z * luz.z)
        val valor = 0.3f + 0.7f * difusa
        return (valor * 255f).toInt().coerceIn(FONDO + 24, 255)
    }

    private fun gradiente(nodo: SdfNode, p: Vec3, paso: Float): Vec3 {
        val e = paso * 0.5f
        val g = Vec3(
            nodo.evaluar(Vec3(p.x + e, p.y, p.z)) - nodo.evaluar(Vec3(p.x - e, p.y, p.z)),
            nodo.evaluar(Vec3(p.x, p.y + e, p.z)) - nodo.evaluar(Vec3(p.x, p.y - e, p.z)),
            nodo.evaluar(Vec3(p.x, p.y, p.z + e)) - nodo.evaluar(Vec3(p.x, p.y, p.z - e)),
        )
        return normalizar(g)
    }

    private fun normalizar(v: Vec3): Vec3 {
        val n = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        return if (n < 1e-9f) Vec3(0f, 1f, 0f) else v / n
    }

    private fun cruz(a: Vec3, b: Vec3) = Vec3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x,
    )

    /**
     * Tope de pasos por rayo.
     *
     * Sin él, un rayo que roza la superficie tangencialmente avanza en pasos cada vez
     * más pequeños y no termina nunca. Se prefiere un píxel de fondo a una vista que no
     * se dibuja.
     */
    private const val TOPE_DE_PASOS = 160

    /** Cuánto se aparta de la vertical una dirección, en grados. Para las pruebas. */
    internal fun inclinacionDe(v: Vec3): Float {
        val n = normalizar(v)
        return abs(n.y)
    }
}
