package yunkil.kernel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

enum class Axis { X, Y, Z }

private fun dot2(x: Float, y: Float) = x * x + y * y

/**
 * Un nodo del árbol de distancias con signo.
 *
 * `evaluar` es la verdad de referencia del sistema entero: el shader MSL que se
 * genera a partir de este árbol debe coincidir con él dentro de la tolerancia del
 * test de paridad. Si divergen, el viewport enseña una cosa y el analizador de
 * fabricación razona sobre otra.
 *
 * `escalares` expone, en orden fijo, todos los valores numéricos del nodo. El
 * generador de MSL los emite como uniforms y el empaquetador los recoge con esta
 * misma travesía, de modo que ambos no pueden desalinearse. Gracias a eso mover un
 * deslizador solo reescribe un buffer y jamás recompila un shader.
 */
@Serializable
sealed interface SdfNode {
    fun evaluar(p: Vec3): Float
    fun cotas(): Aabb
    val escalares: List<Float>
    val hijos: List<SdfNode> get() = emptyList()
}

// ---------------------------------------------------------------- primitivas

@Serializable
@SerialName("esfera")
data class Esfera(val radio: Float) : SdfNode {
    override fun evaluar(p: Vec3) = p.length() - radio
    override fun cotas() = Aabb.centered(radio)
    override val escalares get() = listOf(radio)
}

/** Caja centrada en el origen, con redondeo opcional de aristas. */
@Serializable
@SerialName("caja")
data class Caja(val semilados: Vec3, val redondeo: Float = 0f) : SdfNode {
    override fun evaluar(p: Vec3): Float {
        val q = p.abs() - semilados + Vec3.splat(redondeo)
        return q.coerceAtLeastZero().length() + min(q.maxComponent(), 0f) - redondeo
    }

    override fun cotas() = Aabb.centered(semilados)
    override val escalares get() = listOf(semilados.x, semilados.y, semilados.z, redondeo)
}

/** Cilindro con tapas, eje Y, centrado en el origen. */
@Serializable
@SerialName("cilindro")
data class Cilindro(val radio: Float, val altura: Float, val redondeo: Float = 0f) : SdfNode {
    override fun evaluar(p: Vec3): Float {
        val dx = length2(p.x, p.z) - radio + redondeo
        val dy = abs(p.y) - altura * 0.5f + redondeo
        return min(max(dx, dy), 0f) + length2(max(dx, 0f), max(dy, 0f)) - redondeo
    }

    override fun cotas() = Aabb.centered(Vec3(radio, altura * 0.5f, radio))
    override val escalares get() = listOf(radio, altura, redondeo)
}

/**
 * Tronco de cono con tapas, eje Y. Con `radioSuperior` a cero es un cono; con los
 * dos radios iguales, un cilindro. Es la primitiva que da chaflanes y conicidades
 * de desmoldeo, muy usadas para evitar voladizos.
 */
@Serializable
@SerialName("cono")
data class Cono(
    val radioInferior: Float,
    val radioSuperior: Float,
    val altura: Float,
) : SdfNode {
    override fun evaluar(p: Vec3): Float {
        val h = altura * 0.5f
        val qx = length2(p.x, p.z)
        val qy = p.y

        val cax = qx - min(qx, if (qy < 0f) radioInferior else radioSuperior)
        val cay = abs(qy) - h

        val k1x = radioSuperior
        val k1y = h
        val k2x = radioSuperior - radioInferior
        val k2y = 2f * h

        val t = (((k1x - qx) * k2x + (k1y - qy) * k2y) / dot2(k2x, k2y)).coerceIn(0f, 1f)
        val cbx = qx - k1x + k2x * t
        val cby = qy - k1y + k2y * t

        val signo = if (cbx < 0f && cay < 0f) -1f else 1f
        return signo * sqrt(min(dot2(cax, cay), dot2(cbx, cby)))
    }

    override fun cotas(): Aabb {
        val r = max(radioInferior, radioSuperior)
        return Aabb.centered(Vec3(r, altura * 0.5f, r))
    }

    override val escalares get() = listOf(radioInferior, radioSuperior, altura)
}

/** Toro en el plano XZ, eje de revolución Y. */
@Serializable
@SerialName("toro")
data class Toro(val radioMayor: Float, val radioMenor: Float) : SdfNode {
    override fun evaluar(p: Vec3): Float {
        val qx = length2(p.x, p.z) - radioMayor
        return length2(qx, p.y) - radioMenor
    }

    override fun cotas() = Aabb.centered(
        Vec3(radioMayor + radioMenor, radioMenor, radioMayor + radioMenor),
    )

    override val escalares get() = listOf(radioMayor, radioMenor)
}

/** Cápsula de eje Y: segmento de longitud `altura` engrosado por `radio`. */
@Serializable
@SerialName("capsula")
data class Capsula(val radio: Float, val altura: Float) : SdfNode {
    override fun evaluar(p: Vec3): Float {
        val h = altura * 0.5f
        val y = p.y - p.y.coerceIn(-h, h)
        return Vec3(p.x, y, p.z).length() - radio
    }

    override fun cotas() = Aabb.centered(Vec3(radio, altura * 0.5f + radio, radio))
    override val escalares get() = listOf(radio, altura)
}

/**
 * Distancia exacta al casco convexo de dos bolas: un cono de extremos redondeados.
 *
 * Es la pieza de la que se hace un cordón. La superficie lateral no une los dos
 * ecuadores —eso dejaría una arista viva en cada extremo— sino que es la tangente
 * común a las dos esferas, y por eso una cadena de estos troncos se lee como un tubo
 * continuo que engorda y adelgaza en vez de como una ristra de conos empalmados.
 */
internal fun conoRedondeado(p: Vec3, a: Vec3, b: Vec3, ra: Float, rb: Float): Float {
    val bax = b.x - a.x; val bay = b.y - a.y; val baz = b.z - a.z
    val l2 = bax * bax + bay * bay + baz * baz
    val rr = ra - rb
    val a2 = l2 - rr * rr

    // `a2 <= 0` quiere decir que una bola se traga a la otra: no hay tangente común y
    // la fórmula general dividiría por cero. La unión es entonces la bola grande, y el
    // mínimo de las dos distancias la da exacta, dentro y fuera.
    if (l2 <= 1e-12f || a2 <= 1e-9f) {
        return min((p - a).length() - ra, (p - b).length() - rb)
    }

    val il2 = 1f / l2
    val pax = p.x - a.x; val pay = p.y - a.y; val paz = p.z - a.z
    val y = pax * bax + pay * bay + paz * baz
    val z = y - l2

    val xpx = pax * l2 - bax * y
    val xpy = pay * l2 - bay * y
    val xpz = paz * l2 - baz * y
    val x2 = xpx * xpx + xpy * xpy + xpz * xpz
    val y2 = y * y * l2
    val z2 = z * z * l2

    val k = signoDe(rr) * rr * rr * x2
    return when {
        // Más allá de la tapa de `b`: manda la bola de `b`.
        signoDe(z) * a2 * z2 > k -> sqrt(x2 + z2) * il2 - rb
        // Más acá de la tapa de `a`: manda la bola de `a`.
        signoDe(y) * a2 * y2 < k -> sqrt(x2 + y2) * il2 - ra
        // En medio: la tangente común a las dos esferas.
        else -> (sqrt(x2 * a2 * il2) + y * rr) * il2 - ra
    }
}

/** Signo con cero en el cero, como el `sign` de MSL: las tapas dependen de ello. */
private fun signoDe(v: Float) = if (v > 0f) 1f else if (v < 0f) -1f else 0f

/**
 * Cordón: un tubo de radio variable que recorre una polilínea 3D.
 *
 * Es la primitiva que faltaba para una cola, una pata, un mechón o un cable. Hasta
 * ahora esas piezas se aproximaban encadenando cápsulas orientadas a mano: cada codo
 * era una parte más del contrato, el radio solo podía cambiar a saltos entre pieza y
 * pieza, y una cola de seis tramos gastaba seis nodos con sus seis transformaciones.
 * Aquí la curva entera es **un** nodo, el grosor interpola vértice a vértice y el
 * afilado de la punta es un radio que llega a cero, no un cono pegado al final.
 *
 * El campo es el mínimo de un cono redondeado por tramo. Cada tramo es una distancia
 * exacta, y el mínimo de distancias exactas es exacto fuera de la unión y conservador
 * dentro, así que el cordón sigue siendo 1-Lipschitz y el trazador puede saltar a
 * paso completo. Los codos empalman redondeados por construcción, sin coser nada.
 *
 * La curva se guarda ya muestreada. Suavizar es cosa de quien la construye —el motor
 * orgánico interpola sus puntos de control con Catmull-Rom antes de llegar aquí—, de
 * modo que el shader recorre un bucle de tope constante y el nodo no tiene que
 * evaluar splines por cada punto del espacio ni por cada rayo.
 */
@Serializable
@SerialName("cordon")
data class Cordon(
    val puntos: List<Vec3>,
    val radios: List<Float>,
) : SdfNode {

    init {
        require(puntos.size >= 2) { "un cordón necesita al menos dos puntos" }
        require(puntos.size <= MAXIMO_DE_PUNTOS) {
            "un cordón no admite más de $MAXIMO_DE_PUNTOS puntos"
        }
        require(radios.size == puntos.size) { "cada punto del cordón necesita su radio" }
    }

    /** Tramos que se recorren. Un cordón nunca se cierra sobre sí mismo. */
    val tramos: Int get() = puntos.size - 1

    override fun evaluar(p: Vec3): Float {
        var mejor = Float.MAX_VALUE
        for (i in 0 until tramos) {
            val d = conoRedondeado(p, puntos[i], puntos[i + 1], radios[i], radios[i + 1])
            if (d < mejor) mejor = d
        }
        return mejor
    }

    override fun cotas(): Aabb {
        // Cada tramo cabe en la caja de sus dos bolas: el casco convexo de dos convexos
        // no se sale de una caja que ya contiene a los dos.
        var lo = Vec3.splat(Float.MAX_VALUE)
        var hi = Vec3.splat(-Float.MAX_VALUE)
        for (i in puntos.indices) {
            val r = Vec3.splat(radios[i])
            lo = minOf(lo, puntos[i] - r)
            hi = maxOf(hi, puntos[i] + r)
        }
        return Aabb(lo, hi)
    }

    override val escalares: List<Float>
        get() = buildList {
            for (i in puntos.indices) {
                add(puntos[i].x); add(puntos[i].y); add(puntos[i].z); add(radios[i])
            }
        }

    companion object {
        /**
         * Tope de vértices de un cordón. Coincide con `YK_MAX_CORDON` en el prelude MSL:
         * el bucle del shader se recorre con una condición constante y salida temprana,
         * así que ningún bucle emitido depende de un valor en tiempo de ejecución.
         */
        const val MAXIMO_DE_PUNTOS = 64
    }
}

/**
 * Extrusión de un perfil 2D a lo largo del eje Y.
 *
 * Es la operación que convierte Yunkil en una herramienta para piezas de verdad:
 * casi todo lo que se imprime funcional —una escuadra, una brida, una tapa con su
 * ranura— es un contorno acotado con precisión y estirado un grosor. Hasta ahora
 * había que aproximarlo combinando primitivas, y eso obliga a hacer aritmética en
 * lugar de dibujar.
 *
 * El perfil se define en XZ, que es el plano del plato: dibujar sobre la mesa y
 * levantar es como se piensa una pieza impresa.
 */
@Serializable
@SerialName("extrusion")
data class Extrusion(
    val perfil: Perfil2D,
    val altura: Float,
    val redondeo: Float = 0f,
) : SdfNode {

    /**
     * El redondeo **encoge el perfil y la altura antes de engordar el campo**, que
     * es la misma convención que `Caja` y `Cilindro`. Restarlo en vez de sumarlo
     * dejaba la pieza dos radios más ancha de lo pedido y, peor, más ancha que sus
     * propias cotas: el mallador corta por la caja y salían agujeros.
     */
    override fun evaluar(p: Vec3): Float {
        val d2 = perfil.evaluar(Punto2(p.x, p.z)) + redondeo
        val dy = abs(p.y) - altura * 0.5f + redondeo
        return min(max(d2, dy), 0f) + length2(max(d2, 0f), max(dy, 0f)) - redondeo
    }

    override fun cotas(): Aabb {
        val (lo, hi) = perfil.cotas()
        // Con el perfil encogido, el redondeo no añade alcance: la pieza acaba
        // justo donde acaba el perfil y a media altura, con o sin arista viva.
        return Aabb(
            Vec3(lo.x, -altura * 0.5f, lo.y),
            Vec3(hi.x, altura * 0.5f, hi.y),
        )
    }

    /**
     * Los vértices viajan como uniforms y su *cantidad* es topología: cambiar una
     * cota del perfil no recompila el shader, pero añadir un tramo sí. Es la misma
     * regla que gobierna la repetición.
     */
    override val escalares: List<Float>
        get() = buildList {
            add(altura)
            add(redondeo)
            add(perfil.redondeo)
            for (v in perfil.poligono) { add(v.x); add(v.y) }
        }
}

/**
 * Barrido de una sección circular a lo largo del contorno del perfil.
 *
 * Es la familia que faltaba: tubos doblados, marcos, aros, asas, canaletas, juntas y
 * cualquier pieza que sea «un alambre gordo siguiendo un recorrido». Antes había que
 * aproximarlas encadenando cilindros a mano, que es justo la clase de aritmética en
 * la que un modelo de lenguaje se equivoca —y donde además los codos quedaban con
 * cantos vivos porque dos cilindros no empalman solos.
 *
 * En SDF sale casi gratis y **exacto**: la distancia a una cadena de segmentos menos
 * el radio es una cadena de cápsulas. Tres consecuencias que importan:
 *
 * - Los codos salen redondeados por construcción, sin operación de acuerdo.
 * - El campo sigue siendo una distancia verdadera, así que el mallador y el salto de
 *   espacio libre siguen valiendo sin tocar nada.
 * - Reutiliza el `Perfil2D` que ya existe, con lo cual el camino se dibuja con la
 *   misma operación `perfil` y viaja con el mismo empaquetado de uniforms.
 *
 * El camino vive en XZ y la sección es perpendicular a él. `cerrado` decide si el
 * último punto vuelve al primero: cerrado da marcos y aros, abierto da tubos y asas.
 */
@Serializable
@SerialName("barrido")
data class Barrido(
    val perfil: Perfil2D,
    val radio: Float,
    val cerrado: Boolean = true,
) : SdfNode {

    /** Tramos que se recorren. Uno abierto tiene un segmento menos que puntos. */
    val tramos: Int
        get() = perfil.poligono.size.let { if (cerrado) it else it - 1 }

    override fun evaluar(p: Vec3): Float {
        val puntos = perfil.poligono
        val n = puntos.size
        if (n < 2 || tramos < 1) return Float.MAX_VALUE

        var mejor = Float.MAX_VALUE
        for (i in 0 until tramos) {
            val a = puntos[i]
            val b = puntos[(i + 1) % n]

            val ax = a.x; val az = a.y
            val ex = b.x - ax; val ez = b.y - az
            val hx = p.x - ax; val hy = p.y; val hz = p.z - az

            val largo = ex * ex + ez * ez
            // El camino no tiene componente en Y, así que la proyección solo mira XZ;
            // la altura entra entera en la distancia, que es lo que hace de la
            // sección un círculo y no una elipse.
            val t = if (largo > 1e-20f) ((hx * ex + hz * ez) / largo).coerceIn(0f, 1f) else 0f
            val cx = hx - ex * t; val cz = hz - ez * t
            val cuadrado = cx * cx + hy * hy + cz * cz
            if (cuadrado < mejor) mejor = cuadrado
        }
        return sqrt(mejor) - radio
    }

    override fun cotas(): Aabb {
        val puntos = perfil.poligono
        if (puntos.isEmpty()) return Aabb(Vec3.ZERO, Vec3.ZERO)

        // Se mide sobre el polígono crudo y no sobre `perfil.cotas()`: aquel añade el
        // redondeo de esquina del contorno, que aquí no se usa para nada. Declarar
        // más alcance del real no rompe nada, pero declarar menos corta la pieza al
        // mallarla, y eso ya costó un fallo caro en `Extrusion`.
        var minX = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (v in puntos) {
            minX = min(minX, v.x); maxX = max(maxX, v.x)
            minZ = min(minZ, v.y); maxZ = max(maxZ, v.y)
        }
        return Aabb(
            Vec3(minX - radio, -radio, minZ - radio),
            Vec3(maxX + radio, radio, maxZ + radio),
        )
    }

    override val escalares: List<Float>
        get() = buildList {
            add(radio)
            for (v in perfil.poligono) { add(v.x); add(v.y) }
        }
}

/**
 * Revolución de un perfil 2D alrededor del eje Y.
 *
 * Da tornillos, poleas, bridas y cualquier pieza torneada con una sola operación.
 * `desplazamiento` separa el perfil del eje, que es lo que convierte un contorno
 * cerrado pequeño en un anillo en lugar de un sólido macizo.
 */
@Serializable
@SerialName("revolucion")
data class Revolucion(
    val perfil: Perfil2D,
    val desplazamiento: Float = 0f,
) : SdfNode {

    /**
     * El eje cae en `x = -desplazamiento` del perfil: ahí es donde el radio vale cero.
     * Se lo dice al perfil para que no cuente como superficie lo que al girar se
     * colapsa sobre el propio eje. Ver [Perfil2D.evaluar].
     */
    override fun evaluar(p: Vec3): Float =
        perfil.evaluar(Punto2(length2(p.x, p.z) - desplazamiento, p.y), -desplazamiento)

    override fun cotas(): Aabb {
        val (lo, hi) = perfil.cotas()
        // Al girar, el alcance radial es el punto del perfil más lejano del eje.
        val radio = max(abs(lo.x + desplazamiento), abs(hi.x + desplazamiento))
        return Aabb(Vec3(-radio, lo.y, -radio), Vec3(radio, hi.y, radio))
    }

    override val escalares: List<Float>
        get() = buildList {
            add(desplazamiento)
            add(perfil.redondeo)
            for (v in perfil.poligono) { add(v.x); add(v.y) }
        }
}

// ---------------------------------------------------------------- booleanas

/**
 * `fusion` mayor que cero mezcla los dos campos en una banda de esa anchura y
 * produce un acuerdo redondeado. En un kernel B-rep los acuerdos son la parte más
 * cara del motor; aquí son un parámetro.
 */
@Serializable
@SerialName("union")
data class Union(
    val a: SdfNode,
    val b: SdfNode,
    val fusion: Float = 0f,
) : SdfNode {
    override fun evaluar(p: Vec3) = smoothMin(a.evaluar(p), b.evaluar(p), fusion)
    override fun cotas() = a.cotas().union(b.cotas()).expanded(fusion)
    override val escalares get() = listOf(fusion)
    override val hijos get() = listOf(a, b)
}

/** `a` menos `b`. */
@Serializable
@SerialName("diferencia")
data class Diferencia(
    val a: SdfNode,
    val b: SdfNode,
    val fusion: Float = 0f,
) : SdfNode {
    override fun evaluar(p: Vec3) = smoothMax(a.evaluar(p), -b.evaluar(p), fusion)

    /**
     * Las cotas del minuendo, **sin margen por la fusión**.
     *
     * `smoothMax` es siempre mayor o igual que `max`, y mayor significa *menos* material:
     * una resta con acuerdo solo puede quitar más, nunca añadir. Donde `d < 0` también
     * `da < 0`, así que el material está contenido en el de `a` y sus cotas son
     * conservadoras por construcción.
     *
     * Expandía por `fusion`, y el coste no era cosmético: lo encontró el banco de modelado.
     * Una caja de 60 mm con el canto interior redondeado —que es lo correcto— declaraba 62,
     * y de esas cotas beben `acotar` para escalar el conjunto y el analizador para
     * muestrear. Pedir «60 de ancho» dejaba la pieza en 58.
     */
    override fun cotas() = a.cotas()
    override val escalares get() = listOf(fusion)
    override val hijos get() = listOf(a, b)
}

@Serializable
@SerialName("interseccion")
data class Interseccion(
    val a: SdfNode,
    val b: SdfNode,
    val fusion: Float = 0f,
) : SdfNode {
    override fun evaluar(p: Vec3) = smoothMax(a.evaluar(p), b.evaluar(p), fusion)
    // Sin margen por la fusión, por el mismo motivo que en `Diferencia`: `smoothMax` solo
    // puede subir el campo, y subirlo es quitar material. Lo común de dos cuerpos con
    // acuerdo cabe en lo común de sus cotas.
    override fun cotas() = a.cotas().intersect(b.cotas())
    override val escalares get() = listOf(fusion)
    override val hijos get() = listOf(a, b)
}

/** Qué booleana lleva el acuerdo local. */
@Serializable
enum class ModoDeAcuerdo { UNION, DIFERENCIA, INTERSECCION }

/**
 * Booleana con acuerdo **limitado a un sitio**: el filete de una arista concreta.
 *
 * `fusion` en las booleanas de arriba redondea *todo* el encuentro entre dos sólidos, y
 * eso no es lo que se pide cuando alguien señala un canto y dice «este, a 2 mm». Aquí la
 * anchura de la mezcla se multiplica por una caída centrada en [centro]: dentro de la
 * esfera de radio [radio] hay filete, fuera la booleana vuelve a ser exacta.
 *
 * La ventaja frente a un kernel de contornos es que **no hace falta topología**: la
 * arista se elige apuntando con el cursor y el punto de impacto es el centro. En B-rep
 * hay que identificar la arista, sus caras y resolver los solapes; aquí es un número más.
 *
 * Y la limitación, que se declara en vez de esconderse: es un filete «de bola». Si la
 * arista se curva dentro de la esfera de influencia, el radio no sale constante a lo
 * largo de ella. Para una pieza impresa a 0,4 mm de boquilla no se aprecia; para una
 * superficie de producto sí, y para eso está Plasticity.
 */
/** Qué forma tiene el acuerdo: redondo como un filete, o plano como un chaflán. */
@Serializable
enum class PerfilDeAcuerdo { REDONDEO, CHAFLAN }

@Serializable
@SerialName("acuerdoLocal")
data class AcuerdoLocal(
    val a: SdfNode,
    val b: SdfNode,
    val modo: ModoDeAcuerdo,
    val centro: Vec3,
    val radio: Float,
    val fusion: Float,
    /** Por defecto redondeo: es lo que había antes de que existiera el chaflán, y los
     * documentos ya guardados no traen este campo. */
    val perfil: PerfilDeAcuerdo = PerfilDeAcuerdo.REDONDEO,
) : SdfNode {

    override fun evaluar(p: Vec3): Float {
        val da = a.evaluar(p)
        val db = b.evaluar(p)
        val k = fusion * caidaDeAcuerdo((p - centro).length(), radio)
        val redondo = perfil == PerfilDeAcuerdo.REDONDEO
        return when (modo) {
            ModoDeAcuerdo.UNION -> if (redondo) smoothMin(da, db, k) else chaflanMin(da, db, k)
            ModoDeAcuerdo.DIFERENCIA -> if (redondo) smoothMax(da, -db, k) else chaflanMax(da, -db, k)
            ModoDeAcuerdo.INTERSECCION -> if (redondo) smoothMax(da, db, k) else chaflanMax(da, db, k)
        }
    }

    // Las mismas cotas que la booleana equivalente, con el mismo criterio: unir con acuerdo
    // añade material y hay que declararlo; restar e intersecar solo pueden quitar más, así
    // que expandir ahí sería declarar un alcance que la pieza no tiene.
    override fun cotas(): Aabb = when (modo) {
        ModoDeAcuerdo.UNION -> a.cotas().union(b.cotas()).expanded(fusion)
        ModoDeAcuerdo.DIFERENCIA -> a.cotas()
        ModoDeAcuerdo.INTERSECCION -> a.cotas().intersect(b.cotas())
    }

    override val escalares get() = listOf(centro.x, centro.y, centro.z, radio, fusion)
    override val hijos get() = listOf(a, b)

    /**
     * Cota del gradiente de este nodo, mayor que 1 cuando hay filete.
     *
     * De aquí sale el paso de trazado seguro. Con un filete pequeño respecto a su alcance
     * el coste es casi nulo, y solo se paga de verdad cuando la mezcla es tan ancha como
     * la esfera que la limita.
     */
    val lipschitz: Float
        get() {
            if (radio <= 0f) return 1f
            val porLaCaida = 1f + FACTOR_DE_GRADIENTE * (fusion / radio).coerceAtMost(1f)
            // El chaflán suma lo suyo por otra vía: el término cruzado `(a+b)·√½` tiene
            // gradiente √2 cuando las dos superficies son paralelas, y eso no depende de
            // lo ancha que sea la mezcla. Se toma el mayor de los dos, que es la única
            // cota que vale para las dos causas a la vez.
            if (perfil == PerfilDeAcuerdo.REDONDEO) return porLaCaida
            return CHAFLAN_BASE + CHAFLAN_POR_CAIDA * (fusion / radio).coerceAtMost(1f)
        }

    companion object {
        /**
         * Gradiente extra por unidad de `fusion / radio`, **derivado y comprobado**.
         *
         * Una mezcla cuya anchura cambia con la posición no es exactamente 1-Lipschitz: la
         * derivada de la caída añade su parte. El mínimo suave polinómico no puede
         * apartarse del mínimo exacto más de `k/4`, así que `|∂d/∂k| ≤ 1/4`; y la caída
         * `(1−t²)²` tiene pendiente máxima `4t(1−t²) = 1,54` en `t = 1/√3`. El producto es
         * `1,54 / 4 = 0,385` por cada unidad de `fusion/radio`.
         *
         * No es un número a ojo: una prueba mide el gradiente peor sobre 20.000 puntos con
         * `fusion = radio` y sale 1,383 frente al 1,385 que predice esta cuenta.
         */
        const val FACTOR_DE_GRADIENTE = 0.385f

        /**
         * Pendiente máxima de la caída `(1 − t²)²`, en unidades de `1/radio`.
         *
         * Estaba escondida dentro de [FACTOR_DE_GRADIENTE] —0,385 = 1,54 × 0,25, donde
         * 0,25 es lo que un `smooth-min` se mueve por unidad de `k`—. El chaflán se mueve
         * por unidad de `k` otra cantidad distinta, así que la pendiente tenía que salir
         * a la luz para poder combinarla con la que toque.
         */
        const val PENDIENTE_DE_LA_CAIDA = 1.54f

        /**
         * Gradiente extra del chaflán, y **de dónde sale**, porque la primera cifra que
         * escribí (√2 a secas) la tumbó la prueba: medía 1,94 contra 1,41 declarado.
         *
         * Son dos causas que se suman y no una. El término cruzado `(a + b − k)·√½` tiene
         * gradiente `√½·|∇a + ∇b|`, que vale √2 cuando las dos superficies son paralelas
         * —y eso no depende de lo ancha que sea la mezcla—. Y encima `k` cambia con la
         * posición, lo que añade `√½·|∇k| = √½ · 1,54 · fusion/radio`.
         *
         * Con fusion/radio = 0,5 la cuenta da 1,959 y la medida numérica dio 1,939.
         */
        const val CHAFLAN_BASE = 1.41422f
        const val CHAFLAN_POR_CAIDA = 1.089f

        /** El peor caso, con la mezcla tan ancha como su alcance. */
        const val LIPSCHITZ_MAXIMO = 1f + FACTOR_DE_GRADIENTE + 0.005f
    }
}

/**
 * Peso de la influencia del acuerdo: 1 en el centro, 0 a partir del radio.
 *
 * `(1 − t²)²` es C¹ en los dos extremos —su derivada se anula en t=0 y en t=1—, y eso
 * importa: una caída con un codo metería un salto en el gradiente del campo justo donde
 * el filete se acaba, y el trazado por esferas lo vería como una arista falsa.
 *
 * Radio cero es «en ningún sitio», no «en todas partes»: sin esta guarda la división
 * dejaría el campo entero en NaN.
 */
internal fun caidaDeAcuerdo(distancia: Float, radio: Float): Float {
    if (radio <= 0f) return 0f
    val t = distancia / radio
    if (t >= 1f) return 0f
    val u = 1f - t * t
    return u * u
}

// ---------------------------------------------------------------- modificadores

@Serializable
@SerialName("transformado")
data class Transformado(
    val hijo: SdfNode,
    val transform: Transform = Transform.IDENTITY,
) : SdfNode {
    override fun evaluar(p: Vec3) = hijo.evaluar(transform.worldToLocal(p)) * transform.scale
    override fun cotas() = hijo.cotas().transformed(transform)
    override val hijos get() = listOf(hijo)

    override val escalares: List<Float>
        get() {
            // Se emite la matriz de rotación inversa ya resuelta para que el shader
            // no tenga que hacer álgebra de cuaterniones.
            val m = transform.rotation.conjugate().toMatrixRowMajor()
            return listOf(
                m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8],
                transform.translation.x, transform.translation.y, transform.translation.z,
                1f / transform.scale, transform.scale,
            )
        }
}

/**
 * Convierte el sólido en una cáscara del grosor pedido, centrada en su superficie.
 * Es la operación que más usa quien imprime: ahorra material y tiempo.
 */
@Serializable
@SerialName("vaciado")
data class Vaciado(val hijo: SdfNode, val grosor: Float) : SdfNode {
    override fun evaluar(p: Vec3) = abs(hijo.evaluar(p)) - grosor * 0.5f
    override fun cotas() = hijo.cotas().expanded(grosor * 0.5f)
    override val escalares get() = listOf(grosor)
    override val hijos get() = listOf(hijo)
}

/** Dilata o erosiona un sólido sin perder su árbol paramétrico. */
@Serializable
@SerialName("desfase")
data class Desfase(val hijo: SdfNode, val distancia: Float) : SdfNode {
    override fun evaluar(p: Vec3) = hijo.evaluar(p) - distancia
    override fun cotas() = if (distancia > 0f) hijo.cotas().expanded(distancia) else hijo.cotas()
    override val escalares get() = listOf(distancia)
    override val hijos get() = listOf(hijo)
}

/** Simetría especular respecto al plano que pasa por el origen normal al eje dado. */
@Serializable
@SerialName("simetria")
data class Simetria(val hijo: SdfNode, val eje: Axis) : SdfNode {
    override fun evaluar(p: Vec3): Float {
        val q = when (eje) {
            Axis.X -> Vec3(abs(p.x), p.y, p.z)
            Axis.Y -> Vec3(p.x, abs(p.y), p.z)
            Axis.Z -> Vec3(p.x, p.y, abs(p.z))
        }
        return hijo.evaluar(q)
    }

    override fun cotas(): Aabb {
        val c = hijo.cotas()
        // El reflejo puede alcanzar tan lejos como el punto más distante del original.
        val alcance = max(abs(c.min.x), abs(c.max.x))
        val alcanceY = max(abs(c.min.y), abs(c.max.y))
        val alcanceZ = max(abs(c.min.z), abs(c.max.z))
        return when (eje) {
            Axis.X -> Aabb(Vec3(-alcance, c.min.y, c.min.z), Vec3(alcance, c.max.y, c.max.z))
            Axis.Y -> Aabb(Vec3(c.min.x, -alcanceY, c.min.z), Vec3(c.max.x, alcanceY, c.max.z))
            Axis.Z -> Aabb(Vec3(c.min.x, c.min.y, -alcanceZ), Vec3(c.max.x, c.max.y, alcanceZ))
        }
    }

    override val escalares get() = emptyList<Float>()
    override val hijos get() = listOf(hijo)
}

/**
 * Repetición lineal centrada. `cuenta` es topología (cambiarla regenera el shader),
 * `paso` es un parámetro (cambiarlo no lo regenera).
 */
@Serializable
@SerialName("repeticion")
data class Repeticion(
    val hijo: SdfNode,
    val cuenta: Int,
    val paso: Float,
    val eje: Axis = Axis.X,
) : SdfNode {
    init {
        require(cuenta in 1..MAXIMO) { "La cuenta debe estar entre 1 y $MAXIMO, era $cuenta" }
    }

    private fun desplazamiento(i: Int): Vec3 {
        val d = (i - (cuenta - 1) * 0.5f) * paso
        return when (eje) {
            Axis.X -> Vec3(d, 0f, 0f)
            Axis.Y -> Vec3(0f, d, 0f)
            Axis.Z -> Vec3(0f, 0f, d)
        }
    }

    override fun evaluar(p: Vec3): Float {
        var d = Float.MAX_VALUE
        for (i in 0 until cuenta) d = min(d, hijo.evaluar(p - desplazamiento(i)))
        return d
    }

    override fun cotas(): Aabb {
        val base = hijo.cotas()
        var acc = base.transformed(Transform(translation = desplazamiento(0)))
        for (i in 1 until cuenta) {
            acc = acc.union(base.transformed(Transform(translation = desplazamiento(i))))
        }
        return acc
    }

    override val escalares get() = listOf(paso)
    override val hijos get() = listOf(hijo)

    companion object {
        /** Tope duro: la repetición se desenrolla en el shader y no puede crecer sin límite. */
        const val MAXIMO = 64
    }
}

/** Repetición angular alrededor de un eje, paramétrica y sin duplicar el hijo. */
@Serializable
@SerialName("repeticion_circular")
data class RepeticionCircular(
    val hijo: SdfNode,
    val cuenta: Int,
    val angulo: Float = 360f,
    val eje: Axis = Axis.Y,
) : SdfNode {
    init {
        require(cuenta in 1..Repeticion.MAXIMO) {
            "La cuenta debe estar entre 1 y ${Repeticion.MAXIMO}, era $cuenta"
        }
        require(angulo.isFinite() && angulo != 0f && abs(angulo) <= 360f) {
            "El ángulo debe estar entre -360 y 360 grados"
        }
    }

    private fun grados(i: Int): Float = when {
        cuenta == 1 -> 0f
        abs(angulo) >= 359.999f -> i * angulo / cuenta
        else -> i * angulo / (cuenta - 1)
    }

    private fun inversa(p: Vec3, grados: Float): Vec3 {
        val r = grados * (PI.toFloat() / 180f)
        val c = cos(r)
        val s = sin(r)
        return when (eje) {
            Axis.X -> Vec3(p.x, c * p.y + s * p.z, -s * p.y + c * p.z)
            Axis.Y -> Vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z)
            Axis.Z -> Vec3(c * p.x + s * p.y, -s * p.x + c * p.y, p.z)
        }
    }

    override fun evaluar(p: Vec3): Float {
        var d = Float.MAX_VALUE
        for (i in 0 until cuenta) d = min(d, hijo.evaluar(inversa(p, grados(i))))
        return d
    }

    override fun cotas(): Aabb {
        val base = hijo.cotas()
        val axis = when (eje) {
            Axis.X -> Vec3(1f, 0f, 0f)
            Axis.Y -> Vec3(0f, 1f, 0f)
            Axis.Z -> Vec3(0f, 0f, 1f)
        }
        fun rotada(i: Int) = base.transformed(
            Transform(rotation = Quat.fromAxisAngle(axis, grados(i) * PI.toFloat() / 180f)),
        )
        var acc = rotada(0)
        for (i in 1 until cuenta) acc = acc.union(rotada(i))
        return acc
    }

    override val escalares get() = listOf(angulo)
    override val hijos get() = listOf(hijo)
}

// ---------------------------------------------------------------- utilidades

/** Normal de la superficie por diferencias centrales sobre el campo. */
fun SdfNode.normal(p: Vec3, epsilon: Float = 1e-3f): Vec3 {
    val dx = evaluar(Vec3(p.x + epsilon, p.y, p.z)) - evaluar(Vec3(p.x - epsilon, p.y, p.z))
    val dy = evaluar(Vec3(p.x, p.y + epsilon, p.z)) - evaluar(Vec3(p.x, p.y - epsilon, p.z))
    val dz = evaluar(Vec3(p.x, p.y, p.z + epsilon)) - evaluar(Vec3(p.x, p.y, p.z - epsilon))
    val g = Vec3(dx, dy, dz)
    val len = g.length()
    return if (len == 0f) Vec3(0f, 1f, 0f) else g / len
}

/** Recorrido en preorden. Es el orden canónico del árbol y del que dependen los uniforms. */
fun SdfNode.preorden(): List<SdfNode> {
    val salida = ArrayList<SdfNode>()
    fun visitar(n: SdfNode) {
        salida.add(n)
        n.hijos.forEach(::visitar)
    }
    visitar(this)
    return salida
}

/**
 * Todo el contenido del buffer de uniforms: los escalares en preorden y, a
 * continuación, las cajas de cada nodo (mínimo y máximo, 6 floats por nodo, en el
 * mismo orden del preorden).
 *
 * Las cajas van detrás de los escalares a propósito: el orden que comprueba la
 * paridad —los escalares— no cambia, y la marcha podada de `MslGenerator` las lee
 * desde el mismo buffer sin desalinear nada.
 */
fun SdfNode.empaquetarUniforms(): FloatArray {
    val orden = preorden()
    val salida = ArrayList<Float>(orden.sumOf { it.escalares.size } + orden.size * 6)
    orden.forEach { salida.addAll(it.escalares) }
    orden.forEach { c ->
        val caja = c.cotas()
        salida.add(caja.min.x); salida.add(caja.min.y); salida.add(caja.min.z)
        salida.add(caja.max.x); salida.add(caja.max.y); salida.add(caja.max.z)
    }
    return salida.toFloatArray()
}
