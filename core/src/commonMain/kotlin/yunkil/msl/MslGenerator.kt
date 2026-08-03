package yunkil.msl

import yunkil.kernel.Axis
import yunkil.kernel.Caja
import yunkil.kernel.Capsula
import yunkil.kernel.Cilindro
import yunkil.kernel.Cono
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.Interseccion
import yunkil.kernel.Repeticion
import yunkil.kernel.SdfNode
import yunkil.kernel.Simetria
import yunkil.kernel.Toro
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vaciado

/**
 * Shader generado a partir de un árbol SDF.
 *
 * `huellaTopologica` identifica la *forma* del árbol ignorando todos sus valores
 * numéricos. Dos árboles con la misma huella comparten shader y solo se distinguen
 * por el buffer de uniforms, que es justo lo que permite arrastrar un deslizador
 * sin recompilar nada.
 */
data class ShaderGenerado(
    val fuente: String,
    val numeroDeUniforms: Int,
    val huellaTopologica: String,
)

/**
 * Traduce un árbol SDF a Metal Shading Language.
 *
 * Dos reglas gobiernan este generador:
 *
 * 1. Los valores numéricos se emiten como lecturas del buffer `u`, nunca como
 *    literales. El orden de asignación es el preorden del árbol, el mismo que usa
 *    `empaquetarUniforms`, de modo que ambos no pueden desalinearse.
 * 2. Todo bucle que se emite lleva un tope constante de iteraciones. Esta es la
 *    salvaguarda que impide colgar el driver gráfico, y por eso vive en el
 *    generador y no en el criterio de quien escriba el shader.
 */
class MslGenerator {

    fun generar(raiz: SdfNode): ShaderGenerado {
        val cuerpo = StringBuilder()
        val estado = Estado()
        val resultado = emitir(raiz, "p", cuerpo, estado)

        val fuente = buildString {
            append(PRELUDIO)
            append("\nfloat yk_map(float3 p, constant float *u) {\n")
            append(cuerpo)
            append("    return $resultado;\n}\n")
            append(RAYMARCHER)
        }

        return ShaderGenerado(
            fuente = fuente,
            numeroDeUniforms = estado.cursorUniforms,
            huellaTopologica = huellaDe(raiz),
        )
    }

    private class Estado {
        var cursorUniforms = 0
        var siguienteVariable = 0
        fun nuevaVariable(prefijo: String) = "${prefijo}${siguienteVariable++}"
    }

    /**
     * Emite el código que calcula la distancia de [nodo] en el punto [punto] y
     * devuelve el nombre de la variable que la contiene.
     *
     * Reserva los uniforms del nodo *antes* de descender a sus hijos: así el orden
     * de reserva es exactamente el preorden del árbol.
     */
    private fun emitir(nodo: SdfNode, punto: String, sb: StringBuilder, e: Estado): String {
        val b = e.cursorUniforms
        e.cursorUniforms += nodo.escalares.size
        val d = e.nuevaVariable("d")
        val sangria = "    "

        fun u(offset: Int) = "u[${b + offset}]"

        when (nodo) {
            is Esfera ->
                sb.append("${sangria}float $d = length($punto) - ${u(0)};\n")

            is Caja -> {
                val q = e.nuevaVariable("q")
                sb.append("${sangria}float3 $q = abs($punto) - float3(${u(0)}, ${u(1)}, ${u(2)}) + ${u(3)};\n")
                sb.append(
                    "${sangria}float $d = length(max($q, float3(0.0f))) + " +
                        "min(max($q.x, max($q.y, $q.z)), 0.0f) - ${u(3)};\n",
                )
            }

            is Cilindro -> {
                val v = e.nuevaVariable("c")
                sb.append("${sangria}float2 $v = float2(length($punto.xz) - ${u(0)} + ${u(2)}, abs($punto.y) - ${u(1)} * 0.5f + ${u(2)});\n")
                sb.append("${sangria}float $d = min(max($v.x, $v.y), 0.0f) + length(max($v, float2(0.0f))) - ${u(2)};\n")
            }

            is Cono -> {
                val v = e.nuevaVariable("k")
                sb.append("${sangria}float $d;\n")
                sb.append("${sangria}{\n")
                sb.append("$sangria    float ${v}h = ${u(2)} * 0.5f;\n")
                sb.append("$sangria    float ${v}qx = length($punto.xz);\n")
                sb.append("$sangria    float ${v}qy = $punto.y;\n")
                sb.append("$sangria    float ${v}ax = ${v}qx - min(${v}qx, (${v}qy < 0.0f) ? ${u(0)} : ${u(1)});\n")
                sb.append("$sangria    float ${v}ay = abs(${v}qy) - ${v}h;\n")
                sb.append("$sangria    float ${v}2x = ${u(1)} - ${u(0)};\n")
                sb.append("$sangria    float ${v}2y = 2.0f * ${v}h;\n")
                sb.append("$sangria    float ${v}t = clamp(((${u(1)} - ${v}qx) * ${v}2x + (${v}h - ${v}qy) * ${v}2y) / (${v}2x * ${v}2x + ${v}2y * ${v}2y), 0.0f, 1.0f);\n")
                sb.append("$sangria    float ${v}bx = ${v}qx - ${u(1)} + ${v}2x * ${v}t;\n")
                sb.append("$sangria    float ${v}by = ${v}qy - ${v}h + ${v}2y * ${v}t;\n")
                sb.append("$sangria    float ${v}s = (${v}bx < 0.0f && ${v}ay < 0.0f) ? -1.0f : 1.0f;\n")
                sb.append("$sangria    $d = ${v}s * sqrt(min(${v}ax * ${v}ax + ${v}ay * ${v}ay, ${v}bx * ${v}bx + ${v}by * ${v}by));\n")
                sb.append("${sangria}}\n")
            }

            is Toro -> {
                val v = e.nuevaVariable("t")
                sb.append("${sangria}float2 $v = float2(length($punto.xz) - ${u(0)}, $punto.y);\n")
                sb.append("${sangria}float $d = length($v) - ${u(1)};\n")
            }

            is Capsula -> {
                val v = e.nuevaVariable("s")
                sb.append("${sangria}float ${v}h = ${u(1)} * 0.5f;\n")
                sb.append("${sangria}float ${v}y = $punto.y - clamp($punto.y, -${v}h, ${v}h);\n")
                sb.append("${sangria}float $d = length(float3($punto.x, ${v}y, $punto.z)) - ${u(0)};\n")
            }

            is Union -> {
                val a = emitir(nodo.a, punto, sb, e)
                val c = emitir(nodo.b, punto, sb, e)
                sb.append("${sangria}float $d = yk_smin($a, $c, ${u(0)});\n")
            }

            is Diferencia -> {
                val a = emitir(nodo.a, punto, sb, e)
                val c = emitir(nodo.b, punto, sb, e)
                sb.append("${sangria}float $d = yk_smax($a, -$c, ${u(0)});\n")
            }

            is Interseccion -> {
                val a = emitir(nodo.a, punto, sb, e)
                val c = emitir(nodo.b, punto, sb, e)
                sb.append("${sangria}float $d = yk_smax($a, $c, ${u(0)});\n")
            }

            is Transformado -> {
                val q = e.nuevaVariable("p")
                sb.append("${sangria}float3 ${q}d = $punto - float3(${u(9)}, ${u(10)}, ${u(11)});\n")
                sb.append(
                    "${sangria}float3 $q = float3(" +
                        "${u(0)} * ${q}d.x + ${u(1)} * ${q}d.y + ${u(2)} * ${q}d.z, " +
                        "${u(3)} * ${q}d.x + ${u(4)} * ${q}d.y + ${u(5)} * ${q}d.z, " +
                        "${u(6)} * ${q}d.x + ${u(7)} * ${q}d.y + ${u(8)} * ${q}d.z) * ${u(12)};\n",
                )
                val hijo = emitir(nodo.hijo, q, sb, e)
                sb.append("${sangria}float $d = $hijo * ${u(13)};\n")
            }

            is Vaciado -> {
                val hijo = emitir(nodo.hijo, punto, sb, e)
                sb.append("${sangria}float $d = abs($hijo) - ${u(0)} * 0.5f;\n")
            }

            is Simetria -> {
                val q = e.nuevaVariable("m")
                val expr = when (nodo.eje) {
                    Axis.X -> "float3(abs($punto.x), $punto.y, $punto.z)"
                    Axis.Y -> "float3($punto.x, abs($punto.y), $punto.z)"
                    Axis.Z -> "float3($punto.x, $punto.y, abs($punto.z))"
                }
                sb.append("${sangria}float3 $q = $expr;\n")
                val hijo = emitir(nodo.hijo, q, sb, e)
                sb.append("${sangria}float $d = $hijo;\n")
            }

            is Repeticion -> {
                // Se desenrolla en lugar de emitir un bucle: la cuenta es topología, no
                // parámetro, y desenrollar mantiene la promesa de que ningún bucle
                // generado depende de un valor en tiempo de ejecución.
                sb.append("${sangria}float $d = 1e30f;\n")
                // Las copias comparten nodo, luego comparten uniforms: se reserva una
                // sola vez y se restaura el cursor tras cada copia salvo la última.
                val cursorAntesDelHijo = e.cursorUniforms
                var cursorTrasElHijo = cursorAntesDelHijo
                for (i in 0 until nodo.cuenta) {
                    e.cursorUniforms = cursorAntesDelHijo
                    val factor = i - (nodo.cuenta - 1) * 0.5f
                    val q = e.nuevaVariable("r")
                    val desplazamiento = when (nodo.eje) {
                        Axis.X -> "float3(${lit(factor)} * ${u(0)}, 0.0f, 0.0f)"
                        Axis.Y -> "float3(0.0f, ${lit(factor)} * ${u(0)}, 0.0f)"
                        Axis.Z -> "float3(0.0f, 0.0f, ${lit(factor)} * ${u(0)})"
                    }
                    sb.append("${sangria}float3 $q = $punto - $desplazamiento;\n")
                    val hijo = emitir(nodo.hijo, q, sb, e)
                    sb.append("${sangria}$d = min($d, $hijo);\n")
                    cursorTrasElHijo = e.cursorUniforms
                }
                e.cursorUniforms = cursorTrasElHijo
            }
        }
        return d
    }

    private fun lit(v: Float): String = if (v == v.toInt().toFloat()) "${v.toInt()}.0f" else "${v}f"

    /**
     * Identidad estructural del árbol: tipos de nodo y valores que son topología
     * (la cuenta de una repetición, el eje de una simetría), nunca los numéricos
     * que viajan por uniforms.
     */
    private fun huellaDe(nodo: SdfNode): String = buildString {
        fun visitar(n: SdfNode) {
            append(
                when (n) {
                    is Esfera -> "E"
                    is Caja -> "C"
                    is Cilindro -> "L"
                    is Cono -> "K"
                    is Toro -> "T"
                    is Capsula -> "P"
                    is Union -> "u"
                    is Diferencia -> "d"
                    is Interseccion -> "i"
                    is Transformado -> "x"
                    is Vaciado -> "v"
                    is Simetria -> "s${n.eje.name}"
                    is Repeticion -> "r${n.cuenta}${n.eje.name}"
                },
            )
            if (n.hijos.isNotEmpty()) {
                append("(")
                n.hijos.forEach(::visitar)
                append(")")
            }
        }
        visitar(nodo)
    }

    private companion object {

        val PRELUDIO = """
            #include <metal_stdlib>
            using namespace metal;

            // Topes constantes del raymarcher. Ningún bucle de este shader depende de
            // un valor en tiempo de ejecución: es lo que impide colgar el driver.
            constant int   YK_MAX_PASOS   = 160;
            constant int   YK_PASOS_AO    = 5;
            constant float YK_DIST_MAXIMA = 4000.0f;

            struct YkCamara {
                float4 origen;      // xyz
                float4 frente;      // xyz
                float4 derecha;     // xyz
                float4 arriba;      // xyz
                float4 params;      // x = tan(fov/2), y = epsilon, z = escalaPasos
                float4 resolucion;  // xy en pixeles
            };

            inline float yk_smin(float a, float b, float k) {
                if (k <= 0.0f) return min(a, b);
                float h = clamp(0.5f + 0.5f * (b - a) / k, 0.0f, 1.0f);
                return mix(b, a, h) - k * h * (1.0f - h);
            }

            inline float yk_smax(float a, float b, float k) {
                return -yk_smin(-a, -b, k);
            }

        """.trimIndent()

        val RAYMARCHER = """

            inline float3 yk_normal(float3 p, constant float *u) {
                const float e = 0.001f;
                return normalize(float3(
                    yk_map(p + float3(e, 0, 0), u) - yk_map(p - float3(e, 0, 0), u),
                    yk_map(p + float3(0, e, 0), u) - yk_map(p - float3(0, e, 0), u),
                    yk_map(p + float3(0, 0, e), u) - yk_map(p - float3(0, 0, e), u)));
            }

            inline float yk_oclusion(float3 p, float3 n, constant float *u) {
                float oclusion = 0.0f;
                float escala = 1.0f;
                for (int i = 0; i < YK_PASOS_AO; ++i) {
                    float h = 0.02f + 0.35f * float(i) / float(YK_PASOS_AO);
                    oclusion += (h - yk_map(p + n * h, u)) * escala;
                    escala *= 0.72f;
                }
                return clamp(1.0f - 2.2f * oclusion, 0.0f, 1.0f);
            }

            struct YkVertice {
                float4 posicion [[position]];
                float2 uv;
            };

            vertex YkVertice yk_vertex(uint id [[vertex_id]]) {
                // Triángulo único que cubre la pantalla: sin buffers de vértices.
                float2 v[3] = { float2(-1, -1), float2(3, -1), float2(-1, 3) };
                YkVertice salida;
                salida.posicion = float4(v[id], 0, 1);
                salida.uv = v[id];
                return salida;
            }

            fragment float4 yk_fragment(YkVertice in [[stage_in]],
                                        constant YkCamara &cam [[buffer(0)]],
                                        constant float *u [[buffer(1)]]) {
                float aspecto = cam.resolucion.x / max(cam.resolucion.y, 1.0f);
                float2 ndc = float2(in.uv.x * aspecto, in.uv.y) * cam.params.x;

                float3 origen = cam.origen.xyz;
                float3 rayo = normalize(cam.frente.xyz
                                        + cam.derecha.xyz * ndc.x
                                        + cam.arriba.xyz  * ndc.y);

                float epsilon = cam.params.y;
                float escalaPasos = cam.params.z;

                float t = 0.0f;
                bool impacto = false;
                for (int i = 0; i < YK_MAX_PASOS; ++i) {
                    float3 p = origen + rayo * t;
                    float d = yk_map(p, u);
                    if (d < epsilon) { impacto = true; break; }
                    t += d * escalaPasos;
                    if (t > YK_DIST_MAXIMA) break;
                }

                float3 cielo = mix(float3(0.10f, 0.11f, 0.13f),
                                   float3(0.04f, 0.045f, 0.055f),
                                   clamp(in.uv.y * 0.5f + 0.5f, 0.0f, 1.0f));
                if (!impacto) return float4(cielo, 1.0f);

                float3 p = origen + rayo * t;
                float3 n = yk_normal(p, u);

                float3 luz = normalize(float3(0.45f, 0.85f, 0.35f));
                float difusa = clamp(dot(n, luz), 0.0f, 1.0f);
                float relleno = clamp(0.5f + 0.5f * dot(n, float3(0, 1, 0)), 0.0f, 1.0f);
                float ao = yk_oclusion(p, n, u);

                float3 base = float3(0.82f, 0.80f, 0.76f);
                float3 color = base * (0.15f * relleno + 0.85f * difusa) * ao;

                // Especular suave: ayuda a leer la curvatura sin pretender realismo.
                float3 media = normalize(luz - rayo);
                color += float3(0.25f) * pow(clamp(dot(n, media), 0.0f, 1.0f), 32.0f) * ao;

                // Niebla lejana para dar profundidad.
                color = mix(color, cielo, clamp(t / YK_DIST_MAXIMA * 3.0f, 0.0f, 1.0f));

                return float4(color, 1.0f);
            }
        """.trimIndent()
    }
}
