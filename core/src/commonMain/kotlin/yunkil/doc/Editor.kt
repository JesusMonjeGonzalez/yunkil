package yunkil.doc

import kotlinx.serialization.json.Json
import yunkil.kernel.Axis
import yunkil.kernel.Esfera
import yunkil.kernel.Quat
import yunkil.kernel.SdfNode
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Vec3
import yunkil.kernel.empaquetarUniforms
import yunkil.malla.Certificado
import yunkil.malla.Exportador
import yunkil.msl.MslGenerator

/**
 * Una fila del árbol tal y como la pinta la interfaz. Es una vista aplanada y
 * sin comportamiento: la interfaz no debe poder alcanzar el documento a través de
 * ella ni modificarlo por accidente.
 */
data class FilaArbol(
    val id: String,
    val nombre: String,
    val tipo: String,
    val etiquetaTipo: String,
    val profundidad: Int,
    val visible: Boolean,
    val esOperacion: Boolean,
    val admiteHijos: Boolean,
    val numeroDeHijos: Int,
)

/** Un parámetro listo para pintar un control, con sus límites ya resueltos. */
data class ParametroVisible(
    val clave: String,
    val etiqueta: String,
    val valor: Float,
    val minimo: Float,
    val maximo: Float,
    val unidad: String,
)

/**
 * Toda la lógica de edición del documento, y la única superficie que consume la
 * aplicación.
 *
 * Cada cambio pasa por una operación que se valida antes de aplicarse: si no es
 * legal, el documento queda intacto y se explica el motivo. Esa disciplina existe
 * hoy por el deshacer, y mañana porque será exactamente el canal por el que un
 * modelo de lenguaje proponga cambios sin poder ejecutar código.
 */
class Editor(inicial: Documento = Documento.vacio()) {

    private var documento: Documento = inicial
    private val historial = ArrayDeque<Documento>()
    private val rehechos = ArrayDeque<Documento>()

    private val generador = MslGenerator()
    private var shaderActual = generador.generar(compilarSeguro(inicial))

    /** Motivo del último rechazo, o `null` si el último cambio se aplicó. */
    var ultimoError: String? = null
        private set

    // ------------------------------------------------------------------ lectura

    val fuenteMsl: String get() = shaderActual.fuente
    val huellaTopologica: String get() = shaderActual.huellaTopologica
    val numeroDeUniforms: Int get() = shaderActual.numeroDeUniforms
    val seleccionado: String? get() = documento.seleccionado
    val puedeDeshacer: Boolean get() = historial.isNotEmpty()
    val puedeRehacer: Boolean get() = rehechos.isNotEmpty()
    val estaVacio: Boolean get() = documento.compilar() == null

    fun uniforms(): List<Float> = compilarSeguro(documento).empaquetarUniforms().toList()

    fun filas(): List<FilaArbol> = documento.raiz.aplanar().map { (pieza, profundidad) ->
        FilaArbol(
            id = pieza.id,
            nombre = pieza.nombre,
            tipo = pieza.tipo.name,
            etiquetaTipo = pieza.tipo.etiqueta,
            profundidad = profundidad,
            visible = pieza.visible,
            esOperacion = pieza.tipo.esOperacion,
            admiteHijos = pieza.tipo.admiteHijos,
            numeroDeHijos = pieza.hijos.size,
        )
    }

    fun parametrosDe(id: String): List<ParametroVisible> {
        val pieza = documento.buscar(id) ?: return emptyList()
        return pieza.tipo.parametros.map {
            ParametroVisible(
                clave = it.clave,
                etiqueta = it.etiqueta,
                valor = pieza.parametro(it.clave),
                minimo = it.minimo,
                maximo = it.maximo,
                unidad = it.unidad,
            )
        }
    }

    /** Traslación y giro de la pieza, en milímetros y grados. */
    fun transformDe(id: String): List<Float> {
        val t = documento.buscar(id)?.transform ?: return listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val e = aEulerGrados(t.rotation)
        return listOf(t.translation.x, t.translation.y, t.translation.z, e[0], e[1], e[2], t.scale)
    }

    fun ejeDe(id: String): String = documento.buscar(id)?.eje?.name ?: Axis.X.name
    fun cuentaDe(id: String): Int = documento.buscar(id)?.cuenta ?: 1
    fun nombreDe(id: String): String = documento.buscar(id)?.nombre ?: ""
    fun esVisible(id: String): Boolean = documento.buscar(id)?.visible ?: true

    val cotaMinima: List<Float> get() = cotas().first
    val cotaMaxima: List<Float> get() = cotas().second

    private fun cotas(): Pair<List<Float>, List<Float>> {
        val nodo = documento.compilar()
            ?: return listOf(-30f, -30f, -30f) to listOf(30f, 30f, 30f)
        val c = nodo.cotas()
        return listOf(c.min.x, c.min.y, c.min.z) to listOf(c.max.x, c.max.y, c.max.z)
    }

    // ------------------------------------------------------------------ edición

    fun seleccionar(id: String) {
        if (documento.buscar(id) != null) documento = documento.copy(seleccionado = id)
    }

    /**
     * Añade una pieza dentro de [padreId].
     *
     * Si el destino no admite hijos —una primitiva— la pieza entra como hermana en
     * lugar de rechazarse: es lo que casi siempre quería quien pulsó el botón, y
     * negarse sin más solo obligaría a un paso extra.
     */
    fun anadir(tipoNombre: String, padreId: String?): Boolean {
        val tipo = TipoPieza.entries.firstOrNull { it.name == tipoNombre }
        if (tipo == null) return rechazar("Tipo de pieza desconocido: $tipoNombre")

        val nueva = Pieza.nueva(tipo)
        val destino = destinoValidoPara(padreId ?: documento.raiz.id)

        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(destino) { it.copy(hijos = it.hijos + nueva) },
                seleccionado = nueva.id,
            )
        }
    }

    private fun destinoValidoPara(idPropuesto: String): String {
        val pieza = documento.buscar(idPropuesto) ?: return documento.raiz.id
        if (pieza.tipo.admiteHijos) return pieza.id
        return documento.padreDe(pieza.id) ?: documento.raiz.id
    }

    fun eliminar(id: String): Boolean {
        if (id == documento.raiz.id) return rechazar("La raíz del documento no se puede eliminar")
        if (documento.buscar(id) == null) return rechazar("No existe la pieza $id")

        val padre = documento.padreDe(id) ?: documento.raiz.id
        return aplicar { doc ->
            doc.copy(raiz = doc.raiz.sin(id), seleccionado = padre)
        }
    }

    fun fijarParametro(id: String, clave: String, valor: Float): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        val definicion = pieza.tipo.parametros.firstOrNull { it.clave == clave }
            ?: return rechazar("«$clave» no es un parámetro de ${pieza.tipo.etiqueta}")

        // Se recorta en lugar de rechazar: un deslizador nunca debe poder atascarse.
        val acotado = valor.coerceIn(definicion.minimo, definicion.maximo)
        return aplicar(registrarEnHistorial = false) { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(parametros = it.parametros + (clave to acotado)) })
        }
    }

    /** Cierra un arrastre de deslizador: marca un único punto de deshacer. */
    fun confirmarEdicionContinua() {
        historial.addLast(documento)
        recortarHistorial()
        rehechos.clear()
    }

    fun fijarTransform(
        id: String,
        x: Float, y: Float, z: Float,
        giroX: Float, giroY: Float, giroZ: Float,
        escala: Float,
    ): Boolean {
        if (documento.buscar(id) == null) return rechazar("No existe la pieza $id")
        if (escala <= 0f) return rechazar("La escala debe ser positiva")

        val transform = Transform(
            rotation = deEulerGrados(giroX, giroY, giroZ),
            translation = Vec3(x, y, z),
            scale = escala,
        )
        return aplicar(registrarEnHistorial = false) { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(transform = transform) })
        }
    }

    fun renombrar(id: String, nombre: String): Boolean {
        val limpio = nombre.trim()
        if (limpio.isEmpty()) return rechazar("El nombre no puede quedar vacío")
        return aplicar { doc -> doc.copy(raiz = doc.raiz.mapear(id) { it.copy(nombre = limpio) }) }
    }

    fun fijarVisible(id: String, visible: Boolean): Boolean =
        aplicar { doc -> doc.copy(raiz = doc.raiz.mapear(id) { it.copy(visible = visible) }) }

    fun fijarEje(id: String, ejeNombre: String): Boolean {
        val eje = Axis.entries.firstOrNull { it.name == ejeNombre }
            ?: return rechazar("Eje desconocido: $ejeNombre")
        return aplicar { doc -> doc.copy(raiz = doc.raiz.mapear(id) { it.copy(eje = eje) }) }
    }

    fun fijarCuenta(id: String, cuenta: Int): Boolean {
        if (cuenta < 1) return rechazar("La cuenta mínima es 1")
        if (cuenta > yunkil.kernel.Repeticion.MAXIMO) {
            return rechazar("La cuenta máxima es ${yunkil.kernel.Repeticion.MAXIMO}")
        }
        return aplicar { doc -> doc.copy(raiz = doc.raiz.mapear(id) { it.copy(cuenta = cuenta) }) }
    }

    /** Envuelve una pieza en una operación nueva, que pasa a ocupar su lugar. */
    fun envolver(id: String, tipoNombre: String): Boolean {
        val tipo = TipoPieza.entries.firstOrNull { it.name == tipoNombre }
        if (tipo == null || !tipo.admiteHijos) return rechazar("«$tipoNombre» no admite hijos")
        if (id == documento.raiz.id) return rechazar("La raíz no se puede envolver")

        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        val envoltorio = Pieza.nueva(tipo).copy(hijos = listOf(pieza))
        val padre = documento.padreDe(id) ?: return rechazar("No existe la pieza $id")

        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(padre) { p ->
                    p.copy(hijos = p.hijos.map { if (it.id == id) envoltorio else it })
                },
                seleccionado = envoltorio.id,
            )
        }
    }

    /** Reordena una pieza dentro de su padre. Es lo que decide qué se resta a qué. */
    fun desplazar(id: String, haciaArriba: Boolean): Boolean {
        val padre = documento.padreDe(id) ?: return rechazar("La raíz no se puede reordenar")
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(padre) { p ->
                    val i = p.hijos.indexOfFirst { it.id == id }
                    val j = if (haciaArriba) i - 1 else i + 1
                    if (i < 0 || j !in p.hijos.indices) {
                        p
                    } else {
                        val lista = p.hijos.toMutableList()
                        lista[i] = p.hijos[j]
                        lista[j] = p.hijos[i]
                        p.copy(hijos = lista)
                    }
                },
            )
        }
    }

    // ------------------------------------------------------------------ historial

    fun deshacer(): Boolean {
        val anterior = historial.removeLastOrNull() ?: return false
        rehechos.addLast(documento)
        documento = anterior
        regenerar()
        return true
    }

    fun rehacer(): Boolean {
        val siguiente = rehechos.removeLastOrNull() ?: return false
        historial.addLast(documento)
        documento = siguiente
        regenerar()
        return true
    }

    // ------------------------------------------------------------------ exportar

    /** Resolución de partida: fina pero acotada para que el mallado no se dispare. */
    fun resolucionSugerida(): Float =
        documento.compilar()?.let { yunkil.malla.resolucionSugerida(it) } ?: 0.5f

    /** Celdas que recorrerá el mallador. Sirve para avisar antes de empezar. */
    fun celdasEstimadas(resolucion: Float): Long =
        documento.compilar()?.let { yunkil.malla.estimarCeldas(it, resolucion) } ?: 0L

    /**
     * Exporta a STL binario y devuelve el certificado del examen.
     *
     * Devuelve `null` si no hay nada que exportar. Si la malla no pasa el examen no
     * se escribe archivo alguno: el certificado explica por qué.
     */
    fun exportarStl(ruta: String, resolucion: Float, alAvanzar: ((Float) -> Unit)? = null): Certificado? {
        val nodo = documento.compilar() ?: return rechazarNulo("El documento no tiene material que exportar")
        val exportador = Exportador(nodo)
        exportador.alAvanzar = alAvanzar
        ultimoError = null
        return exportador.exportarStl(ruta, resolucion)
    }

    private fun rechazarNulo(motivo: String): Certificado? {
        ultimoError = motivo
        return null
    }

    // ------------------------------------------------------------------ archivo

    fun aJson(): String = formato.encodeToString(Documento.serializer(), documento)

    /** Devuelve `true` si el documento se cargó; deja el actual intacto si falla. */
    fun desdeJson(texto: String): Boolean {
        val cargado = try {
            formato.decodeFromString(Documento.serializer(), texto)
        } catch (e: Exception) {
            return rechazar("El archivo no es un documento de Yunkil válido: ${e.message}")
        }
        historial.addLast(documento)
        recortarHistorial()
        rehechos.clear()
        documento = cargado
        regenerar()
        ultimoError = null
        return true
    }

    fun reemplazarDocumento(nuevo: Documento) {
        historial.addLast(documento)
        recortarHistorial()
        rehechos.clear()
        documento = nuevo
        regenerar()
    }

    // ------------------------------------------------------------------ internos

    /**
     * Aplica un cambio y responde si hay que recompilar el shader.
     *
     * Las ediciones continuas —arrastrar un deslizador— no anotan historial en cada
     * fotograma: lo haría inservible. La confirmación llega al soltar, mediante
     * `confirmarEdicionContinua`.
     */
    private fun aplicar(registrarEnHistorial: Boolean = true, cambio: (Documento) -> Documento): Boolean {
        val anterior = documento
        val nuevo = cambio(documento)
        if (nuevo == anterior) {
            ultimoError = null
            return false
        }
        if (registrarEnHistorial) {
            historial.addLast(anterior)
            recortarHistorial()
            rehechos.clear()
        }
        documento = nuevo
        ultimoError = null
        return regenerar()
    }

    private fun regenerar(): Boolean {
        val generado = generador.generar(compilarSeguro(documento))
        val recompilar = generado.huellaTopologica != shaderActual.huellaTopologica
        shaderActual = generado
        return recompilar
    }

    private fun rechazar(motivo: String): Boolean {
        ultimoError = motivo
        return false
    }

    private fun recortarHistorial() {
        while (historial.size > PROFUNDIDAD_DEL_HISTORIAL) historial.removeFirst()
    }

    private companion object {
        const val PROFUNDIDAD_DEL_HISTORIAL = 200

        val formato = Json { prettyPrint = true; encodeDefaults = true }

        /**
         * Sólido de relleno para un documento vacío. Va lo bastante lejos como para
         * quedar fuera del alcance del raymarcher, de modo que el shader siempre es
         * válido aunque no haya nada que enseñar.
         */
        val VACIO: SdfNode = Transformado(
            Esfera(0.001f),
            Transform(translation = Vec3(0f, -1_000_000f, 0f)),
        )

        fun compilarSeguro(doc: Documento): SdfNode = doc.compilar() ?: VACIO

        fun aEulerGrados(q: Quat): FloatArray {
            val m = q.toMatrixRowMajor()
            val sy = kotlin.math.sqrt(m[0] * m[0] + m[3] * m[3])
            val singular = sy < 1e-6f
            val x: Float
            val y: Float
            val z: Float
            if (!singular) {
                x = kotlin.math.atan2(m[7], m[8])
                y = kotlin.math.atan2(-m[6], sy)
                z = kotlin.math.atan2(m[3], m[0])
            } else {
                x = kotlin.math.atan2(-m[5], m[4])
                y = kotlin.math.atan2(-m[6], sy)
                z = 0f
            }
            val aGrados = 180f / kotlin.math.PI.toFloat()
            return floatArrayOf(x * aGrados, y * aGrados, z * aGrados)
        }

        fun deEulerGrados(x: Float, y: Float, z: Float): Quat {
            val aRadianes = kotlin.math.PI.toFloat() / 180f
            val qx = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), x * aRadianes)
            val qy = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), y * aRadianes)
            val qz = Quat.fromAxisAngle(Vec3(0f, 0f, 1f), z * aRadianes)
            return multiplicar(multiplicar(qz, qy), qx).normalized()
        }

        fun multiplicar(a: Quat, b: Quat) = Quat(
            a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
            a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
            a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
            a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
        )
    }
}
