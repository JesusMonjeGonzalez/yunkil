package yunkil.kernel

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)

    fun length() = sqrt(x * x + y * y + z * z)
    fun abs() = Vec3(abs(x), abs(y), abs(z))
    fun maxComponent() = max(x, max(y, z))
    fun minComponent() = min(x, min(y, z))
    fun coerceAtLeastZero() = Vec3(max(x, 0f), max(y, 0f), max(z, 0f))

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val ONE = Vec3(1f, 1f, 1f)
        fun splat(v: Float) = Vec3(v, v, v)
    }
}

fun minOf(a: Vec3, b: Vec3) = Vec3(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z))
fun maxOf(a: Vec3, b: Vec3) = Vec3(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z))

/** Longitud del vector 2D (x, y). Aparece constantemente en las fórmulas de revolución. */
internal fun length2(x: Float, y: Float) = sqrt(x * x + y * y)

/**
 * Rotación como cuaternión unitario. Se guarda así porque compone e interpola sin
 * bloqueo de cardán; para evaluar y para el shader se deriva la matriz inversa.
 */
@Serializable
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {

    fun normalized(): Quat {
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n == 0f) return IDENTITY
        return Quat(x / n, y / n, z / n, w / n)
    }

    fun conjugate() = Quat(-x, -y, -z, w)

    /** Composición: `a * b` aplica primero `b` y después `a`. */
    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    /** Matriz de rotación 3x3 en orden por filas. */
    fun toMatrixRowMajor(): FloatArray {
        val q = normalized()
        val xx = q.x * q.x; val yy = q.y * q.y; val zz = q.z * q.z
        val xy = q.x * q.y; val xz = q.x * q.z; val yz = q.y * q.z
        val wx = q.w * q.x; val wy = q.w * q.y; val wz = q.w * q.z
        return floatArrayOf(
            1f - 2f * (yy + zz), 2f * (xy - wz), 2f * (xz + wy),
            2f * (xy + wz), 1f - 2f * (xx + zz), 2f * (yz - wx),
            2f * (xz - wy), 2f * (yz + wx), 1f - 2f * (xx + yy),
        )
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        fun fromAxisAngle(axis: Vec3, radians: Float): Quat {
            val len = axis.length()
            if (len == 0f) return IDENTITY
            val a = axis / len
            val h = radians * 0.5f
            val s = sin(h)
            return Quat(a.x * s, a.y * s, a.z * s, cos(h))
        }
    }
}

internal fun applyMatrix(m: FloatArray, v: Vec3) = Vec3(
    m[0] * v.x + m[1] * v.y + m[2] * v.z,
    m[3] * v.x + m[4] * v.y + m[5] * v.z,
    m[6] * v.x + m[7] * v.y + m[8] * v.z,
)

/**
 * Transformación rígida con escala uniforme.
 *
 * La escala no uniforme queda deliberadamente fuera: deformaría el campo de
 * distancias y las distancias dejarían de ser distancias, lo que arruinaría el
 * analizador de fabricación que se apoya en ellas.
 */
@Serializable
data class Transform(
    val rotation: Quat = Quat.IDENTITY,
    val translation: Vec3 = Vec3.ZERO,
    val scale: Float = 1f,
) {
    init {
        require(scale > 0f) { "La escala debe ser positiva, era $scale" }
    }

    /** Lleva un punto del espacio del padre al espacio local del hijo. */
    fun worldToLocal(p: Vec3): Vec3 =
        applyMatrix(rotation.conjugate().toMatrixRowMajor(), p - translation) / scale

    fun localToWorld(p: Vec3): Vec3 =
        applyMatrix(rotation.toMatrixRowMajor(), p * scale) + translation

    /**
     * Encadena esta transformación con la de un hijo, de modo que
     * `componer(h).localToWorld(p)` equivale a aplicar primero `h` y luego esta.
     *
     * Hace falta para razonar sobre una pieza anidada en coordenadas del mundo, que
     * es lo que necesita el analizador para decir *qué* pieza causó un aviso.
     */
    fun componer(hijo: Transform) = Transform(
        rotation = (rotation * hijo.rotation).normalized(),
        translation = localToWorld(hijo.translation),
        scale = scale * hijo.scale,
    )

    companion object {
        val IDENTITY = Transform()
    }
}

/** Caja alineada a ejes. Siempre conservadora: puede sobrar, nunca faltar. */
@Serializable
data class Aabb(val min: Vec3, val max: Vec3) {

    val center: Vec3 get() = (min + max) * 0.5f
    val size: Vec3 get() = max - min
    val radius: Float get() = (size * 0.5f).length()

    fun expanded(margin: Float) = Aabb(min - Vec3.splat(margin), max + Vec3.splat(margin))

    fun union(o: Aabb) = Aabb(minOf(min, o.min), maxOf(max, o.max))

    fun intersect(o: Aabb): Aabb {
        val lo = maxOf(min, o.min)
        val hi = minOf(max, o.max)
        // Una intersección vacía se degrada a un punto en lugar de invertirse.
        return if (lo.x > hi.x || lo.y > hi.y || lo.z > hi.z) Aabb(lo, lo) else Aabb(lo, hi)
    }

    fun transformed(t: Transform): Aabb {
        val m = t.rotation.toMatrixRowMajor()
        var lo = Vec3.splat(Float.MAX_VALUE)
        var hi = Vec3.splat(-Float.MAX_VALUE)
        for (i in 0 until 8) {
            val corner = Vec3(
                if (i and 1 == 0) min.x else max.x,
                if (i and 2 == 0) min.y else max.y,
                if (i and 4 == 0) min.z else max.z,
            )
            val w = applyMatrix(m, corner * t.scale) + t.translation
            lo = minOf(lo, w)
            hi = maxOf(hi, w)
        }
        return Aabb(lo, hi)
    }

    companion object {
        fun centered(half: Vec3) = Aabb(Vec3.ZERO - half, half)
        fun centered(half: Float) = centered(Vec3.splat(half))
    }
}

/**
 * Mínimo suave polinómico. Devuelve exactamente `min(a, b)` cuando k es cero, y
 * mezcla los dos campos en una banda de anchura k cuando no lo es. Es lo que
 * produce los acuerdos redondeados entre piezas sin coste añadido.
 */
internal fun smoothMin(a: Float, b: Float, k: Float): Float {
    if (k <= 0f) return min(a, b)
    val h = (0.5f + 0.5f * (b - a) / k).coerceIn(0f, 1f)
    return b * (1f - h) + a * h - k * h * (1f - h)
}

internal fun smoothMax(a: Float, b: Float, k: Float): Float = -smoothMin(-a, -b, k)

/** Media raíz de dos: la proyección de la diagonal del chaflán sobre cada cara. */
internal const val RAIZ_MEDIA = 0.70710678f

/**
 * Mezcla de chaflán: corta plano donde [smoothMin] redondea.
 *
 * El término cruzado `(a + b − k)·√½` es el chaflán clásico, y **acotado a propósito**.
 * Sin el tope tiene dos defectos que en un shader de demo no se notan y aquí sí:
 *
 *  - con `k = 0` no se apaga —para `a = b = −10` daría −14,1 en vez de −10—, y la caída
 *    local del acuerdo existe justo para que fuera de su esfera la booleana sea exacta;
 *  - hacia dentro del sólido el error crece sin techo, y ahí dentro es donde leen el
 *    vaciado, el grosor de pared y el analizador.
 *
 * El tope de `√½·k` por debajo de la booleana resuelve las dos cosas **sin tocar la
 * forma**: en la superficie las dos distancias son pequeñas y el tope no llega a actuar,
 * así que el chaflán que se ve es el chaflán entero; lo único que se acota es el valor
 * del campo dentro del material, donde ya nadie dibuja pero sí se mide.
 */
internal fun chaflanMin(a: Float, b: Float, k: Float): Float {
    if (k <= 0f) return min(a, b)
    val exacto = min(a, b)
    val cruzado = (a + b - k) * RAIZ_MEDIA
    return max(min(exacto, cruzado), exacto - k * RAIZ_MEDIA)
}

internal fun chaflanMax(a: Float, b: Float, k: Float): Float = -chaflanMin(-a, -b, k)
