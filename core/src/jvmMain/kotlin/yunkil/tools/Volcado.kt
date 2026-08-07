package yunkil.tools

import yunkil.kernel.AcuerdoLocal
import yunkil.kernel.Axis
import yunkil.kernel.Caja
import yunkil.kernel.Capsula
import yunkil.kernel.Cilindro
import yunkil.kernel.Cono
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.Interseccion
import yunkil.kernel.ModoDeAcuerdo
import yunkil.kernel.Quat
import yunkil.kernel.Repeticion
import yunkil.kernel.SdfNode
import yunkil.kernel.Simetria
import yunkil.kernel.Toro
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vaciado
import yunkil.kernel.Vec3
import yunkil.kernel.empaquetarUniforms
import yunkil.kernel.normal
import yunkil.msl.MslGenerator
import java.io.File
import kotlin.random.Random

/**
 * Vuelca los casos de paridad a disco para que el arnés de Metal los verifique.
 *
 * El invariante que protege es el más importante del proyecto: si `evaluar` en
 * Kotlin y el MSL generado divergen, el viewport enseña una geometría y el
 * analizador de fabricación razona sobre otra distinta.
 */
fun main(args: Array<String>) {
    val destino = File(args.firstOrNull() ?: "build/paridad")
    destino.deleteRecursively()
    destino.mkdirs()

    val generador = MslGenerator()
    casos().forEach { (nombre, modelo) ->
        val dir = File(destino, nombre).apply { mkdirs() }
        val shader = generador.generar(modelo)

        File(dir, "shader.metal").writeText(shader.fuente)

        val uniforms = modelo.empaquetarUniforms()
        check(uniforms.size == shader.numeroDeUniforms) {
            "$nombre: el shader espera ${shader.numeroDeUniforms} uniforms pero el árbol empaqueta ${uniforms.size}"
        }

        val puntos = puntosDeMuestra(modelo)
        File(dir, "datos.txt").writeText(
            buildString {
                appendLine("uniforms ${uniforms.size}")
                appendLine(uniforms.joinToString(" ") { it.toString() })
                appendLine("puntos ${puntos.size}")
                for (p in puntos) {
                    appendLine("${p.x} ${p.y} ${p.z} ${modelo.evaluar(p)}")
                }
            },
        )
    }

    println("Volcados ${casos().size} casos en ${destino.absolutePath}")
}

/**
 * Puntos de muestra en dos poblaciones: una nube uniforme que cubre el dominio y
 * otra pegada a la superficie, que es donde las fórmulas se rompen si están mal.
 */
private fun puntosDeMuestra(modelo: SdfNode): List<Vec3> {
    val r = Random(20260803)
    val cotas = modelo.cotas()
    val centro = cotas.center
    val alcance = maxOf(cotas.radius * 1.8f, 1f)

    fun aleatorio() = centro + Vec3(
        (r.nextFloat() * 2f - 1f) * alcance,
        (r.nextFloat() * 2f - 1f) * alcance,
        (r.nextFloat() * 2f - 1f) * alcance,
    )

    val uniformes = List(600) { aleatorio() }

    // Proyección aproximada sobre la superficie siguiendo el gradiente.
    val superficie = ArrayList<Vec3>(400)
    var intentos = 0
    while (superficie.size < 400 && intentos < 20000) {
        intentos++
        var p = aleatorio()
        repeat(24) {
            val d = modelo.evaluar(p)
            if (kotlin.math.abs(d) < 0.05f) return@repeat
            p -= modelo.normal(p) * d
        }
        if (kotlin.math.abs(modelo.evaluar(p)) < 0.5f) superficie.add(p)
    }

    return uniformes + superficie
}

private fun casos(): List<Pair<String, SdfNode>> = listOf(
    "esfera" to Esfera(10f),
    "caja" to Caja(Vec3(12f, 8f, 5f)),
    "caja_redondeada" to Caja(Vec3(12f, 8f, 5f), redondeo = 2f),
    "cilindro" to Cilindro(6f, 20f),
    "cilindro_redondeado" to Cilindro(6f, 20f, redondeo = 1.5f),
    "cono" to Cono(radioInferior = 10f, radioSuperior = 3f, altura = 18f),
    "cono_punta" to Cono(radioInferior = 9f, radioSuperior = 0f, altura = 14f),
    "toro" to Toro(radioMayor = 15f, radioMenor = 4f),
    "capsula" to Capsula(radio = 5f, altura = 16f),

    "union" to Union(Esfera(10f), desplazada(Esfera(9f), 8f)),
    "union_fusionada" to Union(Esfera(10f), desplazada(Esfera(9f), 8f), fusion = 4f),
    "diferencia" to Diferencia(Caja(Vec3(12f, 12f, 12f)), Esfera(15f)),
    "diferencia_fusionada" to Diferencia(Caja(Vec3(12f, 12f, 12f)), Esfera(15f), fusion = 2f),
    "interseccion" to Interseccion(Caja(Vec3(10f, 10f, 10f)), Esfera(13f)),

    // Los filetes locales llevan una caída propia en el shader (`yk_caida`). Es la parte
    // del acuerdo local que puede divergir en silencio: la matemática es corta pero si el
    // peso de la mezcla no sale igual en los dos lados, el viewport enseñaría un canto
    // redondeado donde el analizador ve una arista viva. Los tres modos, porque cada uno
    // emite una expresión distinta.
    "acuerdo_union" to AcuerdoLocal(
        Caja(Vec3(10f, 10f, 10f)),
        desplazada(Caja(Vec3(10f, 10f, 10f)), 20f),
        ModoDeAcuerdo.UNION,
        centro = Vec3(10f, 10f, 0f),
        radio = 6f,
        fusion = 4f,
    ),
    "acuerdo_diferencia" to AcuerdoLocal(
        Caja(Vec3(14f, 14f, 14f)),
        Esfera(16f),
        ModoDeAcuerdo.DIFERENCIA,
        centro = Vec3(8f, 8f, 8f),
        radio = 7f,
        fusion = 3f,
    ),
    "acuerdo_interseccion" to AcuerdoLocal(
        Caja(Vec3(10f, 10f, 10f)),
        Esfera(13f),
        ModoDeAcuerdo.INTERSECCION,
        centro = Vec3(6f, 6f, 0f),
        radio = 5f,
        fusion = 2.5f,
    ),

    "transformado" to Transformado(
        Caja(Vec3(10f, 4f, 6f)),
        Transform(
            rotation = Quat.fromAxisAngle(Vec3(0.3f, 1f, 0.2f), 0.9f),
            translation = Vec3(5f, -3f, 2f),
            scale = 1.4f,
        ),
    ),
    "vaciado" to Vaciado(Caja(Vec3(15f, 10f, 10f), redondeo = 2f), grosor = 2.4f),
    "simetria" to Simetria(desplazada(Cilindro(3f, 20f), 12f), Axis.X),
    "repeticion" to Repeticion(Esfera(3f), cuenta = 5, paso = 9f, eje = Axis.Z),

    // El caso que de verdad importa: todo mezclado, como una pieza real.
    "compuesto" to Diferencia(
        Union(
            Vaciado(Caja(Vec3(30f, 12f, 20f), redondeo = 3f), grosor = 2.4f),
            Simetria(
                Transformado(
                    Cilindro(radio = 4f, altura = 30f, redondeo = 0.5f),
                    Transform(
                        rotation = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), 0.6f),
                        translation = Vec3(20f, 6f, 0f),
                        scale = 1.25f,
                    ),
                ),
                eje = Axis.X,
            ),
            fusion = 2f,
        ),
        Repeticion(
            Transformado(Esfera(3.5f), Transform(translation = Vec3(0f, 8f, 0f))),
            cuenta = 4,
            paso = 12f,
            eje = Axis.Z,
        ),
    ),

    // Y los modelos que la aplicación abre de verdad, compilados desde el documento.
    //
    // Los casos de arriba se escriben a mano para cubrir cada nodo; estos cubren lo
    // que el usuario tiene delante al arrancar. La distinción dejó de ser teórica el
    // día que la marcha podada devolvía 20 mm de más: el fallo estaba en la unión de
    // varios hijos con acuerdo, que es exactamente la forma del demo del soporte.
    "demo_soporte" to (yunkil.doc.ModelosDemo.soporte().compilar() ?: Esfera(1f)),
    "demo_rejilla" to (yunkil.doc.ModelosDemo.rejilla().compilar() ?: Esfera(1f)),
)

private fun desplazada(n: SdfNode, x: Float) =
    Transformado(n, Transform(translation = Vec3(x, 0f, 0f)))
