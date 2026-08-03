package yunkil.puente

import yunkil.kernel.Aabb
import yunkil.kernel.Axis
import yunkil.kernel.Caja
import yunkil.kernel.Cilindro
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.Quat
import yunkil.kernel.Repeticion
import yunkil.kernel.SdfNode
import yunkil.kernel.Simetria
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vaciado
import yunkil.kernel.Vec3
import yunkil.kernel.empaquetarUniforms
import yunkil.msl.MslGenerator

/**
 * Frontera entre el núcleo y la aplicación.
 *
 * Swift no conoce el árbol SDF ni lo manipula: le pide a la escena que aplique un
 * cambio y recoge lo único que necesita para dibujar, que es un shader y un puñado
 * de números. Mantener esta frontera estrecha es lo que permite que el núcleo se
 * pruebe entero sin GPU y que iOS sea después un objetivo más, no un puerto.
 */
class Escena(raizInicial: SdfNode = ModelosDemo.soporte()) {

    var raiz: SdfNode = raizInicial
        private set

    private val generador = MslGenerator()
    private var shaderActual = generador.generar(raizInicial)

    /**
     * Sustituye el modelo y responde si hace falta recompilar el shader.
     *
     * Devolver esto explícitamente evita que la capa de render tenga que adivinarlo:
     * si la huella topológica no cambió, basta con reescribir los uniforms, que es
     * el caso de arrastrar un deslizador y debe costar cero.
     */
    fun reemplazar(nueva: SdfNode): Boolean {
        val generado = generador.generar(nueva)
        val recompilar = generado.huellaTopologica != shaderActual.huellaTopologica
        raiz = nueva
        shaderActual = generado
        return recompilar
    }

    val fuenteMsl: String get() = shaderActual.fuente
    val huellaTopologica: String get() = shaderActual.huellaTopologica
    val numeroDeUniforms: Int get() = shaderActual.numeroDeUniforms

    /** El buffer de uniforms en el orden que espera el shader. */
    fun uniforms(): List<Float> = raiz.empaquetarUniforms().toList()

    private val cotas: Aabb get() = raiz.cotas()

    val cotaMinimaX: Float get() = cotas.min.x
    val cotaMinimaY: Float get() = cotas.min.y
    val cotaMinimaZ: Float get() = cotas.min.z
    val cotaMaximaX: Float get() = cotas.max.x
    val cotaMaximaY: Float get() = cotas.max.y
    val cotaMaximaZ: Float get() = cotas.max.z

    /** Distancia con signo en milímetros. La usará el analizador del subproyecto 2. */
    fun distanciaEn(x: Float, y: Float, z: Float): Float = raiz.evaluar(Vec3(x, y, z))
}

/**
 * Modelos de demostración. Existen para que la aplicación tenga algo que enseñar
 * mientras se construye el editor, y para que el rendimiento se mida sobre piezas
 * con la complejidad de una real, no sobre una esfera suelta.
 */
object ModelosDemo {

    /** Soporte de sobremesa: cuerpo vaciado, refuerzos fusionados y taladros pasantes. */
    fun soporte(): SdfNode = soporteCon(grosor = 3f, radioTaladro = 3.2f, fusion = 5f)

    /**
     * El mismo soporte con sus cotas abiertas.
     *
     * Los tres argumentos son parámetros puros: cambiarlos no altera la topología
     * del árbol, así que mover un deslizador solo reescribe uniforms y no cuesta ni
     * una recompilación. Es la propiedad central del diseño, y esta función existe
     * para poder comprobarla a mano.
     */
    fun soporteCon(grosor: Float, radioTaladro: Float, fusion: Float): SdfNode {
        val cuerpo = Vaciado(
            Caja(semilados = Vec3(45f, 8f, 30f), redondeo = 4f),
            grosor = grosor,
        )

        val respaldo = Transformado(
            Vaciado(Caja(semilados = Vec3(45f, 30f, 8f), redondeo = 4f), grosor = grosor),
            Transform(translation = Vec3(0f, 22f, -22f)),
        )

        val refuerzos = Simetria(
            Transformado(
                Cilindro(radio = 6f, altura = 40f, redondeo = 2f),
                Transform(
                    rotation = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), 0.9f),
                    translation = Vec3(32f, 12f, -10f),
                ),
            ),
            eje = Axis.X,
        )

        val taladros = Repeticion(
            Transformado(
                Cilindro(radio = radioTaladro, altura = 40f),
                Transform(translation = Vec3(0f, 0f, 0f)),
            ),
            cuenta = 3,
            paso = 26f,
            eje = Axis.X,
        )

        return Diferencia(
            Union(Union(cuerpo, respaldo, fusion = fusion), refuerzos, fusion = fusion + 1f),
            taladros,
        )
    }

    /** Pieza mínima, útil para aislar problemas de render. */
    fun esferaSuelta(): SdfNode = Esfera(25f)

    /** Caso de estrés: muchas copias para medir el techo del raymarcher. */
    fun rejilla(): SdfNode = Repeticion(
        Simetria(
            Transformado(
                Union(Esfera(6f), Caja(Vec3(5f, 5f, 5f), redondeo = 1f), fusion = 2f),
                Transform(translation = Vec3(18f, 0f, 0f)),
            ),
            eje = Axis.X,
        ),
        cuenta = 8,
        paso = 20f,
        eje = Axis.Z,
    )
}
