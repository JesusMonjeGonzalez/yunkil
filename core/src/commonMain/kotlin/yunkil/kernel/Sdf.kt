package yunkil.kernel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

    // Restar nunca añade material, así que las cotas de `a` bastan; la fusión sí
    // puede desbordarlas ligeramente.
    override fun cotas() = a.cotas().expanded(fusion)
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
    override fun cotas() = a.cotas().intersect(b.cotas()).expanded(fusion)
    override val escalares get() = listOf(fusion)
    override val hijos get() = listOf(a, b)
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

/** Todos los escalares del árbol en orden canónico: el contenido del buffer de uniforms. */
fun SdfNode.empaquetarUniforms(): FloatArray {
    val salida = ArrayList<Float>()
    preorden().forEach { salida.addAll(it.escalares) }
    return salida.toFloatArray()
}
