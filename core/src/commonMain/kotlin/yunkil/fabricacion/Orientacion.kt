package yunkil.fabricacion

import kotlinx.serialization.Serializable
import yunkil.kernel.Quat
import yunkil.kernel.SdfNode
import yunkil.kernel.Transform
import yunkil.kernel.Vec3
import yunkil.malla.ContorneadoDual
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min

/**
 * Una orientación candidata ya puntuada.
 *
 * Los grados son los que hay que aplicarle al modelo, no los que tiene: la
 * interfaz puede ofrecerlos tal cual como un botón.
 */
@Serializable
data class OrientacionEvaluada(
    val gradosX: Float,
    val gradosZ: Float,
    val fraccionEnVoladizo: Float,
    val areaDeContacto: Float,
    val altura: Float,
    val coste: Float,
) {
    val puntuacion: Int get() = (100f - coste).coerceIn(0f, 100f).toInt()

    val esLaActual: Boolean get() = gradosX == 0f && gradosZ == 0f

    val descripcion: String
        get() = when {
            esLaActual -> "tal y como está"
            gradosX != 0f && gradosZ != 0f -> "girando ${grado(gradosX)} en X y ${grado(gradosZ)} en Z"
            gradosX != 0f -> "girando ${grado(gradosX)} en X"
            else -> "girando ${grado(gradosZ)} en Z"
        }

    private fun grado(v: Float) = "${AnalizadorFdm.redondear(v, 0)}°"
}

/**
 * Busca la orientación de impresión que menos voladizo deja con la mejor base.
 *
 * La pieza se malla **una sola vez** y después cada candidata se puntúa girando
 * los puntos y las normales ya calculados. Girar unas decenas de miles de vectores
 * cuesta microsegundos; volver a mallar costaría segundos por candidata, y esa es
 * la diferencia entre una función que se usa y una que se evita.
 */
class BuscadorDeOrientacion(
    private val nodo: SdfNode,
    private val perfil: PerfilFabricacion,
    resolucion: Float? = null,
) {

    private val resolucionDeAnalisis =
        resolucion ?: AnalizadorFdm.resolucionAdecuada(nodo, perfil)

    /**
     * Devuelve las candidatas ordenadas de mejor a peor. La primera de la lista es
     * la recomendación; la orientación actual siempre aparece para poder comparar.
     */
    fun buscar(maximoDeResultados: Int = 5): List<OrientacionEvaluada> {
        val muestras = muestrear()
        if (muestras.isEmpty()) return emptyList()

        val evaluadas = LinkedHashMap<Pair<Float, Float>, OrientacionEvaluada>()

        fun evaluarSi(x: Float, z: Float) {
            val clave = normalizar(x) to normalizar(z)
            if (clave in evaluadas) return
            evaluadas[clave] = evaluar(muestras, clave.first, clave.second)
        }

        for (x in CUARTOS) for (z in CUARTOS) evaluarSi(x, z)

        // Refinamiento: la mejor de las giradas en cuartos suele estar cerca del
        // óptimo, pero no encima. Inclinar unos grados alrededor rescata los casos
        // en los que una cara queda justo en el filo del umbral de voladizo.
        val mejorHastaAhora = evaluadas.values.minByOrNull { it.coste }
        if (mejorHastaAhora != null) {
            for (dx in AJUSTES) for (dz in AJUSTES) {
                evaluarSi(mejorHastaAhora.gradosX + dx, mejorHastaAhora.gradosZ + dz)
            }
        }

        val ordenadas = evaluadas.values.sortedBy { it.coste }
        val actual = evaluadas[0f to 0f]
        val salida = ordenadas.take(maximoDeResultados).toMutableList()
        if (actual != null && salida.none { it.esLaActual }) salida.add(actual)
        return salida
    }

    // ------------------------------------------------------------------ internos

    private fun muestrear(): List<MuestraDeSuperficie> {
        val malla = ContorneadoDual(nodo, resolucionDeAnalisis).generar()
        val salida = ArrayList<MuestraDeSuperficie>(malla.numeroDeTriangulos)
        for (t in 0 until malla.numeroDeTriangulos) {
            val a = malla.vertice(malla.triangulos[t * 3])
            val b = malla.vertice(malla.triangulos[t * 3 + 1])
            val c = malla.vertice(malla.triangulos[t * 3 + 2])
            val u = b - a
            val v = c - a
            val cruz = Vec3(
                u.y * v.z - u.z * v.y,
                u.z * v.x - u.x * v.z,
                u.x * v.y - u.y * v.x,
            )
            val longitud = cruz.length()
            if (longitud <= 0f || !longitud.isFinite()) continue
            // Aquí sí sirve la normal del triángulo: se necesita coherente con el
            // área que se le atribuye, y no una medida puntual del campo.
            salida.add(MuestraDeSuperficie((a + b + c) / 3f, cruz / longitud, longitud * 0.5f))
        }
        return salida
    }

    private fun evaluar(
        muestras: List<MuestraDeSuperficie>,
        gradosX: Float,
        gradosZ: Float,
    ): OrientacionEvaluada {
        val giro = Transform(rotation = rotacion(gradosX, gradosZ))

        var minimoY = Float.MAX_VALUE
        var maximoY = -Float.MAX_VALUE
        val puntos = FloatArray(muestras.size)
        val bajadas = FloatArray(muestras.size)

        for (i in muestras.indices) {
            val m = muestras[i]
            val p = giro.localToWorld(m.punto)
            val n = giro.localToWorld(m.normal)
            puntos[i] = p.y
            bajadas[i] = -n.y
            if (p.y < minimoY) minimoY = p.y
            if (p.y > maximoY) maximoY = p.y
        }

        val corte = minimoY + perfil.alturaCapa * 1.5f
        var areaTotal = 0f
        var areaVoladizo = 0f
        var areaContacto = 0f

        for (i in muestras.indices) {
            val area = muestras[i].area
            areaTotal += area
            val bajada = bajadas[i]
            if (puntos[i] <= corte) {
                // Proyección horizontal de la cara: es el área que toca el plato.
                if (bajada > 0.5f) areaContacto += area * bajada
                continue
            }
            if (bajada <= 0f) continue
            val angulo = grados(asin(bajada.coerceIn(0f, 1f)))
            if (angulo > perfil.anguloVoladizoMaximo) areaVoladizo += area
        }

        val fraccion = if (areaTotal > 0f) areaVoladizo / areaTotal else 0f
        val faltaDeBase = max(0f, 1f - areaContacto / max(perfil.areaBaseMinima, 1e-6f))
        val altura = maximoY - minimoY
        val alturaMaxima = max(nodo.cotas().radius * 2f, 1e-6f)

        // El voladizo pesa más que todo lo demás junto porque es lo único que obliga
        // a soporte, y el soporte es lo que estropea el acabado y gasta el tiempo.
        val coste = 60f * fraccion +
            25f * faltaDeBase +
            15f * min(altura / alturaMaxima, 1f)

        return OrientacionEvaluada(
            gradosX = gradosX,
            gradosZ = gradosZ,
            fraccionEnVoladizo = fraccion,
            areaDeContacto = areaContacto,
            altura = altura,
            coste = coste,
        )
    }

    private companion object {
        val CUARTOS = floatArrayOf(0f, 90f, 180f, 270f)
        val AJUSTES = floatArrayOf(-20f, -10f, 10f, 20f)

        fun rotacion(gradosX: Float, gradosZ: Float): Quat {
            val aRadianes = PI.toFloat() / 180f
            val qx = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), gradosX * aRadianes)
            val qz = Quat.fromAxisAngle(Vec3(0f, 0f, 1f), gradosZ * aRadianes)
            return (qz * qx).normalized()
        }

        fun grados(radianes: Float) = radianes * 180f / PI.toFloat()

        /** Lleva cualquier ángulo a [0, 360) y limpia el −0 para que la clave sea única. */
        fun normalizar(grados: Float): Float {
            var g = grados % 360f
            if (g < 0f) g += 360f
            return if (abs(g) < 1e-4f) 0f else g
        }
    }
}
