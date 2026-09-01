package yunkil.doc

import kotlinx.serialization.json.Json
import yunkil.fabricacion.AccionCorrectora
import yunkil.fabricacion.AnalizadorFdm
import yunkil.fabricacion.CatalogoDePerfiles
import yunkil.fabricacion.CuponDeCalibracion
import yunkil.fabricacion.BuscadorDeOrientacion
import yunkil.fabricacion.Correccion
import yunkil.fabricacion.Estandares
import yunkil.fabricacion.EstimacionDeImpresion
import yunkil.fabricacion.InformeDeFabricacion
import yunkil.fabricacion.InterferenciaDeCuerpos
import yunkil.fabricacion.OrientacionEvaluada
import yunkil.fabricacion.OrigenDelPerfil
import yunkil.fabricacion.AjusteDeTaladro
import yunkil.fabricacion.PerfilFabricacion
import yunkil.fabricacion.Roscas
import yunkil.fabricacion.VerificadorDeEnsamblajes
import yunkil.fabricacion.aMarkdown
import yunkil.ia.Aplicador
import yunkil.ia.Asiento
import yunkil.ia.Bitacora
import yunkil.ia.Cara
import yunkil.ia.ClaseDeFallo
import yunkil.ia.Acotar
import yunkil.ia.Colocar
import yunkil.ia.CotasPedidas
import yunkil.ia.Conversacion
import yunkil.ia.Desenlace
import yunkil.ia.Explicacion
import yunkil.ia.LineaExplicada
import yunkil.ia.Reparo
import yunkil.ia.Rol
import yunkil.ia.Turno
import yunkil.ia.Interprete
import yunkil.ia.PlanDeModelado
import yunkil.ia.ResultadoDeAplicacion
import yunkil.ia.ResultadoDeInterpretacion
import yunkil.ia.RevisionDePlan
import yunkil.ia.RevisorDeGeometria
import yunkil.ia.Vocabulario
import yunkil.ia.contextoParaModelo
import yunkil.ia.EjeNombrado
import yunkil.ia.cotasEnMundoDe
import yunkil.ia.transformDelPadreDe
import yunkil.imagen.Vistas
import yunkil.kernel.Aabb
import yunkil.kernel.Axis
import yunkil.kernel.CampoDeMalla
import yunkil.kernel.Cordon
import yunkil.kernel.Esfera
import yunkil.kernel.Perfil2D
import yunkil.kernel.Punto2
import yunkil.kernel.Quat
import yunkil.kernel.SdfNode
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Vec3
import yunkil.kernel.applyMatrix
import yunkil.kernel.empaquetarUniforms
import yunkil.malla.Certificado
import yunkil.malla.LectorStl
import yunkil.malla.TresMf
import yunkil.malla.escribirArchivo
import yunkil.malla.leerArchivo
import yunkil.malla.anadirLinea
import yunkil.malla.Exportador
import yunkil.organico.MotorOrganico
import yunkil.organico.ResultadoContratoOrganico
import yunkil.msl.CampoEnShader
import yunkil.msl.MslGenerator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max

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

/**
 * Un plan del modelo ya leído: o hay plan, o hay un motivo concreto por el que no.
 * Los avisos recogen lo que hubo que normalizar, para que la tolerancia sea visible.
 */
data class PlanInterpretado(
    val plan: PlanDeModelado?,
    val motivoDelRechazo: String?,
    val avisos: List<String>,
) {
    val aceptado: Boolean get() = plan != null
    val numeroDeOperaciones: Int get() = plan?.operaciones?.size ?: 0
    val resumen: String get() = plan?.resumen.orEmpty()
    val reemplaza: Boolean get() = plan?.reemplazar ?: false
    val requiereDatos: Boolean get() = plan?.estado == yunkil.ia.EstadoDelPlan.NECESITA_DATOS
    val preguntas: List<String> get() = plan?.preguntas.orEmpty()
}

/**
 * Una malla leída y horneada, todavía fuera del documento.
 *
 * Existe para poder partir la importación en dos: lo caro y puro —leer el STL y rasterizar
 * su campo, que son segundos— y lo que muta el documento, que es inmediato. Así lo primero
 * puede hacerse en otro hilo sin que la ventana se quede congelada, y el motivo del fallo
 * viaja aquí dentro en vez de escribirse en el `ultimoError` del editor, que es estado
 * compartido y no se toca desde fuera del hilo de la interfaz.
 */
sealed interface MallaImportada {
    data class Lista(
        val campo: CampoDeMalla,
        val ruta: String,
        val nombre: String,
        /** Lo que hay que decir aunque la pieza entre igual: una malla abierta, por ejemplo. */
        val aviso: String?,
    ) : MallaImportada

    data class Fallo(val motivo: String) : MallaImportada
}

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
/**
 * Lo que hay entre dos piezas, en milímetros.
 *
 * `hueco` y `solape` son excluyentes por construcción: o sobra aire o falta. Van como
 * dos números y no como uno con signo porque el signo se lee mal —un −3 en una cota se
 * confunde con una dirección— y porque cada uno responde una pregunta distinta: el hueco
 * dice si cabe algo en medio, el solape dice si hay que separarlas.
 */
data class Medicion(
    val nombreA: String,
    val nombreB: String,
    val hueco: Float,
    val solape: Float,
    val entreCentros: Float,
    /** Cuánto hay que moverse en cada eje para ir del centro de A al de B. */
    val porEje: List<Float>,
)


class Editor(inicial: Documento = Documento.vacio()) {

    private var versionInterna: Long = 0

    /**
     * Perfil de fabricación vigente. Gobierna la holgura de todos los encajes, así que
     * cambiarlo vuelve a derivar las cotas que dependen de él.
     *
     * Se guarda el perfil entero y no su nombre: un perfil calibrado con un cupón impreso
     * en esta máquina no está en la lista de fábrica, y buscarlo por nombre lo devolvería
     * a los valores tabulados sin que nadie se enterara.
     */
    private var perfilDeFabricacion: PerfilFabricacion = PerfilFabricacion.PREDETERMINADO

    private var documento: Documento = inicial.resolverEncajes(
        PerfilFabricacion.PREDETERMINADO,
    )
        set(value) {
            if (field != value) {
                field = value
                versionInterna++
            }
        }

    /** El documento vivo, para la reproducción del historial. */
    internal val documentoActual: Documento get() = documento
    private val historial = ArrayDeque<Documento>()
    private val rehechos = ArrayDeque<Documento>()

    private val generador = MslGenerator()
    private var shaderActual = generador.generar(compilarSeguro(documento))

    /**
     * El árbol de la propuesta que se está mirando, dibujado junto al documento.
     *
     * Vive fuera del documento a propósito, como el plano de sección: es estado de
     * vista. Se empaqueta una sola vez al ponerlo —el árbol no cambia mientras la
     * propuesta está en pantalla— porque `uniforms()` se llama en cada fotograma.
     */
    private var nodoFantasma: SdfNode? = null
    private var uniformsDelFantasma: List<Float> = emptyList()

    /** Mientras está abierta, las ediciones no anotan puntos de deshacer propios. */
    private var enTransaccion = false

    /** Motivo del último rechazo, o `null` si el último cambio se aplicó. */
    var ultimoError: String? = null
        private set

    // ------------------------------------------------------------------ lectura

    val fuenteMsl: String get() = shaderActual.fuente
    val huellaTopologica: String get() = shaderActual.huellaTopologica
    val numeroDeUniforms: Int get() = shaderActual.numeroDeUniforms

    /**
     * Fracción de la distancia que el trazado puede avanzar sin pasarse de largo.
     *
     * Vale 1 mientras el árbol sea 1-Lipschitz, que es lo normal; baja cuando hay filetes
     * locales. Lo decide el generador porque es quien sabe qué nodos hay.
     */
    val pasoSeguroDelShader: Float get() = shaderActual.pasoSeguro

    /**
     * Los campos horneados que el shader lee como textura, en orden de enlace.
     *
     * El renderizador los sube a texturas 3D. Se le da la lista del generador y no se
     * le deja recorrer el documento por su cuenta: el orden es el del preorden del
     * árbol, y si lo dedujera por otro camino el día que cambie la emisión se pintaría
     * una malla con los datos de otra sin que nada lo dijera.
     */
    val camposDelShader: List<CampoEnShader> get() = shaderActual.campos
    val seleccionado: String? get() = documento.seleccionado
    /** Revisión monotónica del estado que la IA pudo observar. */
    val versionDocumento: Long get() = versionInterna
    val puedeDeshacer: Boolean get() = historial.isNotEmpty()
    val puedeRehacer: Boolean get() = rehechos.isNotEmpty()
    val estaVacio: Boolean get() = documento.compilar() == null

    /**
     * El buffer de uniforms: el del documento y, detrás, el del fantasma.
     *
     * Concatenados y en este orden porque es exactamente el orden en que el generador
     * emite los índices de los dos árboles. Son los dos únicos sitios donde ese acuerdo
     * está escrito, y por eso hay una prueba que compara el tamaño con el que declara el
     * shader: un desfase de un hueco no da error, da un fantasma con otras cotas.
     */
    fun uniforms(): List<Float> =
        compilarSeguro(documento).empaquetarUniforms().toList() + uniformsDelFantasma

    /** ¿Hay una propuesta dibujándose encima de la pieza? */
    val hayFantasma: Boolean get() = nodoFantasma != null

    /**
     * Pone —o quita— la previsualización fantasma de un plan.
     *
     * El plan se aplica en un **banco aislado** y de ahí sale el árbol que se dibuja
     * junto al documento: mirar una propuesta no toca la pieza, no gasta un punto de
     * deshacer y no aparece en el árbol. Es el mismo patrón que `medirPlan` y
     * `vistasDelPlan`, que es lo que hace que esta función no pueda estropear nada.
     *
     * Con [aceptadas] se previsualiza solo la parte marcada, cerrada igual que al
     * aplicar. Las casillas del panel cambian lo que se aplicaría, así que tienen que
     * cambiar lo que se ve: un fantasma que enseña la propuesta entera mientras el
     * usuario desmarca operaciones miente justo en el momento de decidir.
     *
     * Devuelve `true` si hay que recompilar el shader, igual que el resto de ediciones.
     */
    fun previsualizar(
        plan: PlanDeModelado?,
        aceptadas: List<Int>? = null,
        nombrePerfil: String? = null,
    ): Boolean {
        val nuevo = plan?.let { arbolDe(it, aceptadas, nombrePerfil) }
        if (nuevo == nodoFantasma) return false
        nodoFantasma = nuevo
        uniformsDelFantasma = nuevo?.empaquetarUniforms()?.toList() ?: emptyList()
        return regenerar()
    }

    /**
     * Pone —o quita— el fantasma de una escultura orgánica propuesta por la IA.
     *
     * Usa el mismo canal que [previsualizar] a propósito: mirar el contrato no toca
     * el documento, no gasta un punto de deshacer y no aparece en el árbol. Una
     * escultura es un árbol como cualquier otro, y el generador ya sabe emitir un
     * segundo árbol de vista; lo único que cambia es de dónde sale el nodo.
     *
     * Un contrato que no compila quita el fantasma en lugar de dejar el anterior:
     * enseñar una figura que ya no es la propuesta es mentir en el momento de decidir.
     *
     * Devuelve `true` si hay que recompilar el shader, igual que el resto de ediciones.
     */
    fun previsualizarEscultura(contratoCanonico: String?): Boolean {
        val nuevo = contratoCanonico?.let { MotorOrganico.nodoDeContrato(it) }
        if (nuevo == nodoFantasma) return false
        nodoFantasma = nuevo
        uniformsDelFantasma = nuevo?.empaquetarUniforms()?.toList() ?: emptyList()
        return regenerar()
    }

    /** El árbol que dejaría un plan, medido en un banco. `null` si no se puede aplicar. */
    private fun arbolDe(
        plan: PlanDeModelado,
        aceptadas: List<Int>?,
        nombrePerfil: String?,
    ): SdfNode? {
        val banco = Editor(documento)
        val aplicacion =
            if (aceptadas == null) banco.aplicarPlan(plan, nombrePerfil)
            else banco.aplicarParteDelPlan(plan, aceptadas.toSet(), nombrePerfil)
        if (!aplicacion.exito) return null
        return banco.documento.compilar()
    }

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
        return pieza.tipo.parametrosCon(pieza.forma).map {
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

    /**
     * Lo que separa —o lo que solapa— a dos piezas.
     *
     * Es la herramienta de medir que tiene cualquier editor 3D y que aquí faltaba. El
     * analizador de fabricación ya sabía calcular estos números, pero solo los sacaba
     * envueltos en un aviso: para saber cuánto separaba dos piezas había que provocar
     * una queja, que es usar la alarma de incendios de termómetro.
     *
     * Se mide **sobre el campo, no sobre las cajas envolventes**. Dos cilindros
     * separados en diagonal tienen las cajas solapadas y las piezas a tres milímetros,
     * y quien pregunta quiere los tres milímetros.
     */
    fun medirEntre(idA: String, idB: String, paso: Float = 0.5f): Medicion? {
        if (idA == idB) return null
        val piezaA = documento.buscar(idA) ?: return null
        val piezaB = documento.buscar(idB) ?: return null
        val campoA = documento.raiz.solo(idA)?.compilar() ?: return null
        val campoB = documento.raiz.solo(idB)?.compilar() ?: return null

        val cotasA = campoA.cotas()
        val cotasB = campoB.cotas()
        val centroA = cotasA.center
        val centroB = cotasB.center
        val porEje = listOf(centroB.x - centroA.x, centroB.y - centroA.y, centroB.z - centroA.z)

        // El barrido cubre las dos piezas y un margen: el punto más cercano de A a B
        // está en la superficie de una de las dos, nunca fuera de la envolvente común.
        val caja = cotasA.union(cotasB).expanded(paso)
        val n = { a: Float, b: Float -> maxOf(1, ((b - a) / paso).toInt()) }
        val nx = n(caja.min.x, caja.max.x)
        val ny = n(caja.min.y, caja.max.y)
        val nz = n(caja.min.z, caja.max.z)

        var hueco = Float.MAX_VALUE
        var solape = 0f
        for (i in 0..nx) for (j in 0..ny) for (k in 0..nz) {
            val p = Vec3(
                caja.min.x + (caja.max.x - caja.min.x) * i / nx,
                caja.min.y + (caja.max.y - caja.min.y) * j / ny,
                caja.min.z + (caja.max.z - caja.min.z) * k / nz,
            )
            val da = campoA.evaluar(p)
            val db = campoB.evaluar(p)
            // Dentro de las dos a la vez: se están metiendo una en otra, y lo que
            // interesa es cuánto se meten en el peor punto.
            if (da <= 0f && db <= 0f) solape = maxOf(solape, minOf(-da, -db))
            // Fuera de las dos: el punto está en el aire que las separa, y la suma de
            // las dos distancias acota lo que hay entre ellas por ese camino.
            if (da > 0f && db > 0f) hueco = minOf(hueco, da + db)
        }

        return Medicion(
            nombreA = piezaA.nombre,
            nombreB = piezaB.nombre,
            hueco = if (solape > 0f || hueco == Float.MAX_VALUE) 0f else maxOf(0f, hueco),
            solape = solape,
            entreCentros = (centroB - centroA).length(),
            porEje = porEje,
        )
    }

    /** La caja envolvente del modelo entero, o `null` si no hay geometría. */
    fun cotasDelModelo(): Aabb? = documento.compilar()?.cotas()

    private fun cotas(): Pair<List<Float>, List<Float>> {
        val nodo = documento.compilar()
            ?: return listOf(-30f, -30f, -30f) to listOf(30f, 30f, 30f)
        val c = nodo.cotas()
        return listOf(c.min.x, c.min.y, c.min.z) to listOf(c.max.x, c.max.y, c.max.z)
    }

    /**
     * Qué pieza hay en la dirección de un rayo, y dónde.
     *
     * Es lo que faltaba para poder pinchar en el viewport en vez de teclear números.
     * La interfaz pasa el rayo que sale del cursor y recibe la pieza; quien decide es
     * el núcleo, con el mismo campo que exporta y analiza.
     */
    fun senalar(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Impacto? =
        documento.impactar(Vec3(ox, oy, oz), Vec3(dx, dy, dz))

    /** De qué pieza es la superficie que pasa por un punto del mundo. */
    fun atribuir(x: Float, y: Float, z: Float): String? = documento.atribuir(Vec3(x, y, z))

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

    /** Duplica una pieza completa junto con sus hijos y selecciona la copia. */
    fun duplicar(id: String): Boolean {
        if (id == documento.raiz.id) return rechazar("La raíz del documento no se puede duplicar")
        val original = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        val padre = documento.padreDe(id) ?: return rechazar("No existe el padre de la pieza")

        fun copiar(pieza: Pieza): Pieza {
            val nueva = Pieza.nueva(pieza.tipo, "${pieza.nombre} copia")
            return pieza.copy(
                id = nueva.id,
                nombre = nueva.nombre,
                hijos = pieza.hijos.map(::copiar),
            )
        }

        val copia = copiar(original)
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(padre) { p ->
                    val posicion = p.hijos.indexOfFirst { it.id == id } + 1
                    p.copy(hijos = p.hijos.toMutableList().apply { add(posicion, copia) })
                },
                seleccionado = copia.id,
            )
        }
    }

    fun fijarParametro(id: String, clave: String, valor: Float): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        val definicion = pieza.tipo.parametrosCon(pieza.forma).firstOrNull { it.clave == clave }
            ?: return rechazar("«$clave» no es un parámetro de ${pieza.tipo.etiqueta}")

        // Se recorta en lugar de rechazar: un deslizador nunca debe poder atascarse.
        var acotado = valor.coerceIn(definicion.minimo, definicion.maximo)
        // Y si el parámetro tiene un hueco prohibido en el cero, se salta al lado hacia
        // el que iba en vez de pararse en un valor que el nodo no acepta.
        val hueco = definicion.huecoEnCero
        if (hueco > 0f && abs(acotado) < hueco) {
            acotado = if (acotado < 0f) -hueco else hueco
        }

        if (gobernadaPorEncaje(id, clave)) {
            val encaje = documento.encajeQueGobierna(pieza, clave)!!
            val medida = documento.medidaDe(encaje.medida)?.nombre ?: encaje.medida
            return rechazar(
                "«$clave» manda en ${encaje.eje}, y en ${encaje.eje} manda el encaje con " +
                    "«$medida». Cambia la medida, o suelta el encaje para editar la pieza a mano.",
            )
        }

        return aplicar(registrarEnHistorial = false) { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(parametros = it.parametros + (clave to acotado)) })
        }
    }

    val raizId: String get() = documento.raiz.id

    /** Las caras de las que se puede tirar en esta pieza. */
    fun asasDe(id: String): List<String> =
        documento.buscar(id)?.tipo?.asas?.keys?.map { it.name } ?: emptyList()

    /**
     * Qué cara de la pieza corresponde a una normal del mundo.
     *
     * La interfaz tiene el punto y la normal del impacto, no el nombre de una cara. Aquí
     * se pasa la normal al espacio local de la pieza y se elige el asa que le
     * corresponde, que es una decisión geométrica y por tanto del núcleo.
     */
    fun asaParaNormal(id: String, nx: Float, ny: Float, nz: Float): String? {
        val pieza = documento.buscar(id) ?: return null
        val asas = pieza.tipo.asas.keys
        if (asas.isEmpty()) return null

        // Solo la rotación: la normal es una dirección, así que la traslación no cuenta
        // y la escala uniforme no la cambia.
        val acumulado = documento.transformDelPadreDe(id)?.componer(pieza.transform) ?: pieza.transform
        val enMundo = Vec3(nx, ny, nz)
        val local = acumulado.worldToLocal(enMundo) - acumulado.worldToLocal(Vec3.ZERO)
        val largo = local.length()
        if (largo < 1e-6f) return null

        return Asa.masParecidaA(local / largo, asas)?.name
    }

    /**
     * Empuja una cara hacia fuera [milimetros] milímetros del mundo.
     *
     * Con esto arrastrar una cara hace lo que espera cualquiera que venga de Shapr3D, y
     * **sigue siendo un parámetro con su número**: la cota no se pierde por haberla
     * movido con el ratón.
     *
     * Dos cosas que no son obvias y sin las cuales el gesto se siente roto:
     *
     * - **La compensación del centro.** Las primitivas están centradas, así que crecer
     *   10 mm mueve las dos caras 5. Se desplaza el centro medio crecimiento en la
     *   dirección de la cara para que la de enfrente se quede donde está.
     * - **Se compensa lo conseguido, no lo pedido.** Si el parámetro topa con su
     *   máximo, compensar el arrastre entero movería la pieza sin que creciera y la cara
     *   que se estaba sujetando se despegaría.
     */
    fun empujar(
        id: String,
        asaNombre: String,
        milimetros: Float,
        registrarEnHistorial: Boolean = true,
    ): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        val asa = Asa.entries.firstOrNull { it.name == asaNombre }
            ?: return rechazar("«$asaNombre» no es una cara")
        val clave = pieza.tipo.asas[asa]
            ?: return rechazar("${pieza.tipo.etiqueta} no tiene ${asa.etiqueta} que empujar")
        if (!milimetros.isFinite()) return rechazar("El arrastre no es válido")

        val definicion = pieza.tipo.parametrosCon(pieza.forma).firstOrNull { it.clave == clave }
            ?: return rechazar("«$clave» no es un parámetro de ${pieza.tipo.etiqueta}")

        // El arrastre viene en milímetros del mundo y el parámetro vive en los del
        // espacio local de la pieza, que la cadena de escalas separa.
        val escala = documento.raiz.escalaHasta(id) ?: 1f
        val antes = pieza.parametro(clave)
        val despues = (antes + milimetros / escala).coerceIn(definicion.minimo, definicion.maximo)
        val crecimiento = despues - antes
        if (crecimiento == 0f) return true

        // Un radio crece a los dos lados a la vez: no hay centro que compensar.
        val desplazamiento = if (asa == Asa.CONTORNO) Vec3.ZERO else {
            val local = asa.haciaFuera * (crecimiento * 0.5f)
            pieza.transform.localToWorld(local) - pieza.transform.localToWorld(Vec3.ZERO)
        }

        // Durante un arrastre no se anota historial en cada fotograma: el punto de
        // deshacer se marca al empezar, igual que con los deslizadores.
        return aplicar(registrarEnHistorial = registrarEnHistorial) { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(id) {
                    it.copy(
                        parametros = it.parametros + (clave to despues),
                        transform = it.transform.copy(
                            translation = it.transform.translation + desplazamiento,
                        ),
                    )
                },
            )
        }
    }

    /**
     * Pone un filete en el punto señalado.
     *
     * Un filete no vive en la pieza sino en el **encuentro entre dos**, así que la
     * operación sube desde [id] hasta el primer booleano que tenga dos hijos o más: es
     * lo que hace que baste pinchar el canto sin saber dónde está el nodo que lo produce.
     *
     * El centro llega en coordenadas del mundo porque es el punto de impacto del cursor,
     * y ahí es donde el usuario ha dicho «este canto».
     */
    fun filetear(id: String, x: Float, y: Float, z: Float, radio: Float, tamano: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !radio.isFinite() || !tamano.isFinite()) {
            return rechazar("El filete no es válido")
        }
        if (radio <= 0f) return rechazar("El alcance del filete tiene que ser mayor que cero")

        val booleano = booleanoQueJunta(id)
            ?: return rechazar("Aquí no se juntan dos piezas: no hay canto que filetear")

        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(booleano) {
                    it.copy(
                        parametros = it.parametros + mapOf(
                            "acuerdoRadio" to radio,
                            "acuerdoX" to x,
                            "acuerdoY" to y,
                            "acuerdoZ" to z,
                            "fusion" to tamano.coerceAtMost(radio),
                        ),
                    )
                },
                seleccionado = booleano,
            )
        }
    }

    /**
     * Redondea el canto donde se juntan dos piezas, sin que nadie tenga que señalarlo.
     *
     * Es la puerta para la IA, que no tiene cursor: el canto se nombra por las dos piezas
     * que lo producen y **el punto lo mide Yunkil**, intersecando sus cajas envolventes en
     * el mundo. Que el modelo se invente una coordenada del espacio es justo el fallo que
     * se quiere evitar; que diga «el encuentro de la base y el respaldo» lo hace bien.
     *
     * Con [contraId] nulo se redondean todos los encuentros de ese booleano, que es el
     * `fusion` de siempre: para una figura orgánica es lo correcto.
     */
    fun filetearEntre(
        objetivoId: String,
        contraId: String?,
        radio: Float,
        chaflan: Boolean = false,
    ): Boolean {
        if (!radio.isFinite() || radio <= 0f) return rechazar("El radio del filete tiene que ser positivo")

        // «Redondéale los cantos» a una pieza suelta no habla de ningún encuentro entre
        // dos sólidos: habla de los cantos que la primitiva ya tiene, y esos son un
        // parámetro suyo. Antes esto se rechazaba con «aquí no se juntan dos piezas», y
        // el banco lo cazó: pedido a una tapa cilíndrica, el modelo emitía un `filete`
        // de la pieza contra sí misma —la única forma de decirlo con el vocabulario que
        // se le enseña— y no pasaba nada, ni con aviso.
        //
        // Va antes de buscar el booleano y no en el `else` de su fallo, porque una pieza
        // que además cuelga de una UNION tiene las dos lecturas, y la que pidió el
        // usuario es esta: los cantos *de esta pieza*.
        if (contraId == null || contraId == objetivoId) {
            val propio = redondeoPropioDe(objetivoId)
            if (propio != null) return fijarParametro(objetivoId, propio, radio)
        }

        val booleano = booleanoQueJunta(objetivoId)
            ?: return rechazar(
                "«${nombreDe(objetivoId)}» no tiene cantos redondeables ni se junta con otra pieza",
            )

        if (contraId == null) {
            // Todos los encuentros de ese booleano: es el acuerdo global.
            return aplicar { doc ->
                doc.copy(
                    raiz = doc.raiz.mapear(booleano) {
                        it.copy(parametros = it.parametros + mapOf("fusion" to radio, "acuerdoRadio" to 0f))
                    },
                    seleccionado = booleano,
                )
            }
        }

        val unas = documento.cotasEnMundoDe(objetivoId)
            ?: return rechazar("No se pueden medir las cotas de $objetivoId")
        val otras = documento.cotasEnMundoDe(contraId)
            ?: return rechazar("No se pueden medir las cotas de $contraId")

        // Donde se solapan las dos envolventes está el canto. Si no se solapan, las piezas
        // se tocan justo o no se tocan: el punto medio entre sus centros es lo más cercano
        // al encuentro que se puede decir sin inventar nada.
        val comun = unas.intersect(otras)
        val seSolapan = comun.max.x >= comun.min.x && comun.max.y >= comun.min.y && comun.max.z >= comun.min.z
        val centro = if (seSolapan) comun.center else (unas.center + otras.center) * 0.5f

        // El alcance cubre el canto entero: la diagonal de la zona común cuando la hay, y
        // si no, dos radios, que es lo que ocupa el redondeo.
        val alcance = if (seSolapan) max(comun.radius, radio * 2f) else radio * 2f

        if (!filetear(booleano, centro.x, centro.y, centro.z, alcance, radio)) return false
        return fijarParametro(booleano, "acuerdoChaflan", if (chaflan) 1f else 0f)
    }

    /**
     * El parámetro con el que esta pieza redondea sus propios cantos, si tiene alguno.
     *
     * Sale del catálogo de `TipoPieza` y no de una lista escrita aquí, por lo mismo que
     * el esquema que ve el modelo: una primitiva nueva con canto redondeable funcionaría
     * sola, y una que lo pierda dejaría de ofrecerlo sin que nadie tenga que acordarse.
     */
    private fun redondeoPropioDe(id: String): String? {
        val pieza = documento.buscar(id) ?: return null
        return pieza.tipo.parametrosCon(pieza.forma)
            .firstOrNull { it.clave == "redondeo" }?.clave
    }

    /**
     * Gira una pieza para que la cara nombrada quede contra el plato.
     *
     * Es la misma operación que `apoyarEnElPlato`, pero por nombre de cara en vez de por
     * normal del cursor: un modelo de lenguaje sabe perfectamente cuál es la cara plana
     * grande de lo que acaba de diseñar, y no sabría escribir el cuaternión que la tumba.
     */
    fun apoyarCaraEnElPlato(id: String, asaNombre: String): Boolean {
        val asa = Asa.entries.firstOrNull { it.name == asaNombre }
            ?: return rechazar("«$asaNombre» no es una cara")
        if (asa == Asa.CONTORNO) return rechazar("El contorno no es una cara que se pueda apoyar")

        val enMundo = direccionEnElMundo(id, asa.haciaFuera.x, asa.haciaFuera.y, asa.haciaFuera.z)
            ?: return rechazar("No se puede saber a dónde mira esa cara")
        return apoyarEnElPlato(id, enMundo[0], enMundo[1], enMundo[2])
    }

    /** Quita el filete local de un booleano y devuelve la booleana exacta. */
    fun quitarFilete(id: String): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        if (pieza.tipo !in BOOLEANAS) return rechazar("«${pieza.nombre}» no es una operación booleana")
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(id) {
                    it.copy(parametros = it.parametros + ("acuerdoRadio" to 0f))
                },
            )
        }
    }

    /** ¿Lleva esta pieza un filete local puesto? */
    fun tieneFilete(id: String): Boolean = (documento.buscar(id)?.parametro("acuerdoRadio") ?: 0f) > 0f

    /**
     * El booleano más cercano por encima de [id] que de verdad junte dos cosas.
     *
     * Una unión con un solo hijo no produce ningún canto —compila al hijo tal cual—, así
     * que filetearla no haría nada visible y sería un fallo silencioso.
     */
    private fun booleanoQueJunta(id: String): String? {
        var actual: String? = id
        while (actual != null) {
            val pieza = documento.buscar(actual)
            if (pieza != null && pieza.tipo in BOOLEANAS && pieza.hijos.count { it.visible } >= 2) {
                return actual
            }
            actual = documento.padreDe(actual)
        }
        return null
    }

    // ------------------------------------------------- ver, mover, copiar, apoyar

    fun padreDe(id: String): String? = documento.padreDe(id)

    /** Centro de la caja envolvente de una pieza, en milímetros del mundo. */
    fun centroEnElMundo(id: String): List<Float>? {
        val c = documento.cotasEnMundoDe(id) ?: return null
        return listOf(c.center.x, c.center.y, c.center.z)
    }

    /** A dónde apunta, en el mundo, una dirección del espacio local de la pieza. */
    fun direccionEnElMundo(id: String, x: Float, y: Float, z: Float): List<Float>? {
        val pieza = documento.buscar(id) ?: return null
        val acumulado = documento.transformDelPadreDe(id)?.componer(pieza.transform) ?: pieza.transform
        val v = acumulado.localToWorld(Vec3(x, y, z)) - acumulado.localToWorld(Vec3.ZERO)
        val largo = v.length()
        if (largo < 1e-6f) return null
        val n = v / largo
        return listOf(n.x, n.y, n.z)
    }

    /**
     * Deja visible solo esta pieza y lo que hace falta para verla.
     *
     * Los ancestros se quedan visibles porque una rama oculta no compila: aislar una caja
     * dentro de un grupo escondiendo el grupo dejaría la pantalla en negro. Es la operación
     * que uno busca en cuanto el árbol pasa de cinco piezas.
     */
    fun aislar(id: String): Boolean {
        if (documento.buscar(id) == null) return rechazar("No existe la pieza $id")

        val aLaVista = HashSet<String>()
        aLaVista.add(id)
        var subiendo = documento.padreDe(id)
        while (subiendo != null) {
            aLaVista.add(subiendo)
            subiendo = documento.padreDe(subiendo)
        }
        // Y su descendencia: aislar un grupo tiene que enseñar el grupo entero.
        documento.buscar(id)?.aplanar()?.forEach { (pieza, _) -> aLaVista.add(pieza.id) }

        return aplicar { doc ->
            doc.copy(raiz = doc.raiz.conVisibilidad { pieza -> pieza.id in aLaVista }, seleccionado = id)
        }
    }

    /** Devuelve la visibilidad a todo el documento. */
    fun mostrarTodo(): Boolean = aplicar { doc ->
        doc.copy(raiz = doc.raiz.conVisibilidad { true })
    }

    /**
     * Saca una pieza de su grupo y la deja colgando del abuelo.
     *
     * **Conservando su sitio en el mundo**, que es lo que hace que la operación sirva de
     * algo: si al salir de un grupo desplazado la pieza saltara a otro lado habría que
     * recolocarla a mano y no se habría ganado nada. La transformación del padre se
     * compone en la de la pieza antes de moverla.
     */
    fun extraer(id: String): Boolean {
        if (id == documento.raiz.id) return rechazar("La raíz no se puede extraer")
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        val padre = documento.padreDe(id) ?: return rechazar("No existe el padre de la pieza")
        val abuelo = documento.padreDe(padre)
            ?: return rechazar("«${pieza.nombre}» ya cuelga de la raíz")

        val transformDelPadre = documento.buscar(padre)?.transform ?: Transform.IDENTITY
        val reubicada = pieza.copy(transform = transformDelPadre.componer(pieza.transform))

        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz
                    .mapear(padre) { p -> p.copy(hijos = p.hijos.filter { it.id != id }) }
                    .mapear(abuelo) { a -> a.copy(hijos = a.hijos + reubicada) },
                seleccionado = id,
            )
        }
    }

    /** La pieza como texto, para llevarla al portapapeles. */
    fun copiar(id: String): String? {
        val pieza = documento.buscar(id)
        if (pieza == null) {
            ultimoError = "No existe la pieza $id"
            return null
        }
        ultimoError = null
        return formato.encodeToString(Pieza.serializer(), pieza)
    }

    /**
     * Pega una pieza copiada dentro de [destinoId].
     *
     * Todos los identificadores del árbol pegado se renuevan. Conservarlos dejaría dos
     * piezas con el mismo id en el documento y cualquier operación posterior tocaría la
     * equivocada, que es la clase de fallo que aparece media hora después y no se
     * relaciona con haber pegado.
     */
    fun pegar(texto: String, destinoId: String?): Boolean {
        val leida = try {
            formato.decodeFromString(Pieza.serializer(), texto)
        } catch (e: Exception) {
            return rechazar("Eso no es una pieza de Yunkil")
        }

        fun renovar(pieza: Pieza): Pieza {
            val nueva = Pieza.nueva(pieza.tipo, pieza.nombre)
            return pieza.copy(id = nueva.id, hijos = pieza.hijos.map(::renovar))
        }

        val copia = renovar(leida)
        val destino = destinoValidoPara(destinoId ?: documento.raiz.id)
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(destino) { it.copy(hijos = it.hijos + copia) },
                seleccionado = copia.id,
            )
        }
    }

    /**
     * Gira la pieza para que la cara señalada quede apoyada en el plato.
     *
     * Es la interacción de modelado para impresión que más se echa de menos: se pincha la
     * cara que se quiere abajo y la pieza se orienta. Además se baja al plato, porque una
     * pieza apoyada pero flotando a 60 mm no está apoyada en nada.
     *
     * La normal llega en coordenadas del mundo porque es la del impacto del cursor.
     */
    fun apoyarEnElPlato(id: String, nx: Float, ny: Float, nz: Float): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        val n = Vec3(nx, ny, nz)
        val largo = n.length()
        if (largo < 1e-6f || !largo.isFinite()) return rechazar("Esa cara no tiene una dirección clara")
        val desde = n / largo
        val hacia = Vec3(0f, -1f, 0f)

        val giro = rotacionQueLleva(desde, hacia)
        val girada = aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(id) {
                    it.copy(transform = it.transform.copy(rotation = (giro * it.transform.rotation).normalized()))
                },
                seleccionado = id,
            )
        }

        // Y al plato. Se hace dentro de la misma transacción para que sea un solo ⌘Z.
        val cotas = documento.cotasEnMundoDe(id)
        if (cotas != null && abs(cotas.min.y) > 1e-4f) {
            val bajada = aplicar(registrarEnHistorial = false) { doc ->
                doc.copy(
                    raiz = doc.raiz.mapear(id) {
                        it.copy(
                            transform = it.transform.copy(
                                translation = it.transform.translation - Vec3(0f, cotas.min.y, 0f),
                            ),
                        )
                    },
                )
            }
            return girada || bajada
        }
        return girada
    }

    /**
     * El giro más corto que lleva [desde] a [hacia].
     *
     * El caso de los vectores opuestos hay que tratarlo aparte: el producto vectorial sale
     * cero y no hay eje que sacar de ahí, así que se elige cualquier perpendicular. Sin
     * esta rama, apoyar la cara de arriba —que es lo más natural que se puede pedir— daría
     * un cuaternión con ceros y no giraría nada.
     */
    private fun rotacionQueLleva(desde: Vec3, hacia: Vec3): Quat {
        val coseno = desde.x * hacia.x + desde.y * hacia.y + desde.z * hacia.z
        if (coseno > 0.9999f) return Quat.IDENTITY
        if (coseno < -0.9999f) {
            val perpendicular = if (abs(desde.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
            val eje = cruz(desde, perpendicular)
            return Quat.fromAxisAngle(eje, PI.toFloat())
        }
        val eje = cruz(desde, hacia)
        return Quat.fromAxisAngle(eje, acos(coseno.coerceIn(-1f, 1f)))
    }

    private fun cruz(a: Vec3, b: Vec3) = Vec3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x,
    )

    /** Cierra un arrastre de deslizador: marca un único punto de deshacer. */
    fun confirmarEdicionContinua() {
        historial.addLast(documento)
        recortarHistorial()
        rehechos.clear()
    }

    fun existe(id: String): Boolean = documento.buscar(id) != null

    /**
     * La pieza que se llama así, si no hay dos que se llamen igual.
     *
     * Los identificadores son internos y cambian en cada aplicación de un plan; los
     * nombres son lo que se ve, lo que escribe el modelo en `nombre` y lo que le
     * devuelve el revisor en sus avisos. Exigir el alias convertía una receta
     * correcta —«coloca la Tapa sobre la Base»— en una operación omitida.
     *
     * Con dos piezas del mismo nombre no se elige ninguna: adivinar cuál de las dos
     * quería el modelo es exactamente el tipo de suposición que produce piezas mal
     * montadas sin que nadie se entere.
     */
    fun idPorNombre(nombre: String): String? {
        val buscado = nombre.trim().lowercase()
        val encontradas = ArrayList<String>()
        fun visitar(pieza: Pieza) {
            if (pieza.nombre.trim().lowercase() == buscado) encontradas.add(pieza.id)
            pieza.hijos.forEach(::visitar)
        }
        visitar(documento.raiz)
        return encontradas.singleOrNull()
    }

    // ---------------------------------------------------------------- plantillas

    /**
     * Guarda una pieza del documento como plantilla reutilizable.
     *
     * Una MALLA no se deja guardar a propósito: su campo horneado no viaja —son
     * megas— y una plantilla que al insertarse llega vacía es una trampa. Lo que
     * siempre puede viajar es lo paramétrico: primitivas, perfiles, esculturas con
     * su contrato, y los cables.
     */
    fun guardarComoPlantilla(id: String, nombre: String, ruta: String): Boolean {
        if (id == documento.raiz.id) return rechazar("La raíz no es una pieza: es el documento entero")
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        if (pieza.tipo == TipoPieza.MALLA) {
            return rechazar(
                "${pieza.nombre} es una malla importada; su campo no viaja en la plantilla",
            )
        }
        // Las medidas que este subárbol nombra viajan con él: sin ellas, el encaje
        // insertado quedaría apuntando a un número que nadie sabe en el documento
        // nuevo.
        val idsDeEncaje = pieza.aplanar().flatMap { it.first.encajes }.map { it.medida }.toSet()
        val medidas = documento.medidas.filter { it.id in idsDeEncaje }
        val motivo = BibliotecaDePlantillas.guardar(nombre, pieza, ruta, medidas)
        return if (motivo == null) true else rechazar(motivo)
    }

    /**
     * Inserta una plantilla de la biblioteca como pieza nueva, con identificadores
     * frescos y en una sola transacción deshacible.
     *
     * Los identificadores se **regeneran todos**, no se reutilizan: los ids del
     * documento abierto ya pueden estar usados, y una colisión no da error —hace que
     * `buscar` y `mapear` muevan la pieza equivocada—. Con encaje viaja además la
     * medida del mundo que lo justifica, si el documento no la tiene ya: una pieza
     * que dice encajar con un tubo de 20 mm no puede insertarse sin saber cuánto
     * mide el tubo.
     */
    fun insertarPlantilla(nombre: String, ruta: String, padreId: String? = null): Boolean {
        val plantilla = BibliotecaDePlantillas.leer(nombre, ruta)
            ?: return rechazar("No hay ninguna plantilla llamada «$nombre»")
        if (plantilla.pieza.tipo == TipoPieza.MALLA) {
            return rechazar("La plantilla «$nombre» es una malla y no conserva su geometría")
        }

        fun conIdFresco(pieza: Pieza): Pieza {
            val base = Pieza.nueva(pieza.tipo, pieza.nombre)
            return pieza.copy(id = base.id, hijos = pieza.hijos.map(::conIdFresco))
        }
        val insertada = conIdFresco(plantilla.pieza)

        // Las medidas que la plantilla necesita y el documento no tiene todavía.
        val idsDeEncaje = insertada.aplanar()
            .flatMap { it.first.encajes }
            .map { it.medida }
            .toSet()
        val nuevas = idsDeEncaje.filter { existente ->
            documento.medidas.none { it.id == existente }
        }.mapNotNull { id -> plantilla.medidas.firstOrNull { it.id == id } }
        val destino = destinoValidoPara(padreId ?: documento.raiz.id)

        return aplicar { doc ->
            doc.copy(
                medidas = doc.medidas + nuevas,
                raiz = doc.raiz.mapear(destino) { it.copy(hijos = it.hijos + insertada) },
                seleccionado = insertada.id,
            )
        }
    }

    /** Fija la incertidumbre de una medida del mundo, en mm, como una edición deshacible. */
    fun fijarTolerancia(medidaId: String, toleranciaMm: Float): Boolean {
        val medida = documento.medidas.firstOrNull { it.id == medidaId }
            ?: return rechazar("No existe la medida $medidaId")
        if (!toleranciaMm.isFinite() || toleranciaMm < 0f) {
            return rechazar("La tolerancia no puede ser negativa ni un número vacío")
        }
        if (2f * toleranciaMm >= medida.valor) {
            return rechazar(
                "±${redondeado(toleranciaMm)} mm deja la medida «${medida.nombre}» sin valor posible",
            )
        }
        return aplicar { doc ->
            doc.copy(medidas = doc.medidas.map {
                if (it.id == medidaId) it.copy(tolerancia = toleranciaMm) else it
            })
        }
    }

    /**
     * Advertencias baratas sobre el documento tal y como está: lo que se sabe sin
     * muestrear el campo ni tardar segundos.
     *
     * No sustituye al análisis —no ve voladizos ni paredes interiores—, pero responde
     * en milisegundos y mientras se edita: un radio por debajo de la boquilla, una
     * pieza que no cabe en el plato. Son las mismas reglas de siempre, tomadas de los
     * números del perfil, no una opinión de quien programa.
     */
    fun advertenciasRapidas(): List<String> {
        val avisos = ArrayList<String>()
        val perfil = perfilActivo()
        for ((pieza, _) in documento.raiz.aplanar()) {
            // La raíz compila el documento entero: sus "cotas" serían las del modelo
            // completo y sus avisos, ruido con nombre de raíz. Las piezas hablan.
            if (!pieza.visible || pieza.id == documento.raiz.id) continue
            for ((clave, valor) in pieza.parametros) {
                if (valor <= 0f || !valor.isFinite()) continue
                if (clave.startsWith("acuerdo")) continue
                val definicion = pieza.tipo.parametrosCon(pieza.forma)
                    .firstOrNull { it.clave == clave } ?: continue
                if (definicion.unidad != "mm") continue
                if (valor < perfil.boquilla) {
                    avisos.add(
                        "«${pieza.nombre}»: ${definicion.etiqueta} de ${redondeado(valor)} mm " +
                            "está por debajo del detalle mínimo de la boquilla " +
                            "(${redondeado(perfil.boquilla)} mm); no llegará a existir",
                    )
                }
            }
            val cotas = documento.cotasEnMundoDe(pieza.id) ?: continue
            val plato = perfil.volumenDeImpresion
            if (cotas.size.x > plato.x || cotas.size.y > plato.y || cotas.size.z > plato.z) {
                avisos.add(
                    "«${pieza.nombre}» mide ${redondeado(cotas.size.x)} × " +
                        "${redondeado(cotas.size.y)} × ${redondeado(cotas.size.z)} mm y no cabe " +
                        "en el volumen de impresión de ${perfil.nombre}",
                )
            }
        }
        return avisos
    }

    /** La distancia —o el solape— entre dos piezas cualesquiera, sin declarar nada. */
    fun distanciaEntre(aId: String, bId: String, paso: Float = 0.8f): InterferenciaDeCuerpos {
        val a = documento.buscar(aId)
            ?: return InterferenciaDeCuerpos("(sin ensamblaje)", aId, bId, aId, bId, null, null)
        val b = documento.buscar(bId)
            ?: return InterferenciaDeCuerpos("(sin ensamblaje)", aId, bId, aId, bId, null, null)
        return VerificadorDeEnsamblajes(documento, paso).medirPar(a, b)
    }

    /**
     * Coloca las piezas sobre el plato —base en Y = 0— en filas sin solaparse, dentro
     * del volumen de impresión del perfil, como **una sola transacción**.
     *
     * Es el paso que falta entre «tengo cinco piezas para imprimir» y «mándolas
     * juntas»: sin esto, las variantes salen en fila por su ancho y un ensamblaje
     * llega a la placa como está, con los cuerpos encajados unos en otros. El orden
     * es el de estantería —primero las más profundas— porque deja menos hueco
     * desperdiciado que el orden de llegada.
     */
    fun empaquetarEnPlaca(ids: List<String>, margen: Float = 5f): Boolean {
        if (!margen.isFinite() || margen < 0f) {
            return rechazar("El margen de la placa no puede ser negativo")
        }
        val pedidas = ids.map { id ->
            documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        }.distinct()
        if (pedidas.size < 2) return rechazar("Para empacar hacen falta al menos dos piezas")

        val plato = perfilActivo().volumenDeImpresion
        val huellas = pedidas.map { pieza ->
            val cotas = documento.cotasEnMundoDe(pieza.id)
                ?: return rechazar("«${pieza.nombre}» no aporta material: está oculta o vacía")
            Triple(pieza.id, cotas, cotas.size)
        }

        // Estantería: filas a lo ancho del plato, cada fila tan honda como su pieza
        // más honda. Primero se decide dónde va cada una, y solo si cabe todo se
        // toca el documento: no se puede dejar la mitad de la placa colocada.
        var x = 0f
        var z = 0f
        var fondoDeFila = 0f
        val destinos = LinkedHashMap<String, Vec3>()
        for ((id, cotas, _) in huellas.sortedByDescending { it.third.z }) {
            val nombre = pedidas.first { it.id == id }.nombre
            if (cotas.size.x > plato.x || cotas.size.z > plato.z) {
                return rechazar(
                    "«$nombre» es más ancha de lo que el plato permite: " +
                        "${redondeado(cotas.size.x)} × ${redondeado(cotas.size.z)} mm frente a " +
                        "${redondeado(plato.x)} × ${redondeado(plato.z)} mm",
                )
            }
            if (x > 0f && x + cotas.size.x > plato.x) {
                x = 0f
                z += fondoDeFila + margen
                fondoDeFila = 0f
            }
            if (z + cotas.size.z > plato.z) {
                return rechazar("Las piezas no caben todas en el plato de ${perfilActivo().nombre}")
            }
            destinos[id] = Vec3(x, 0f, z)
            x += cotas.size.x + margen
            fondoDeFila = maxOf(fondoDeFila, cotas.size.z)
        }

        val origenes = huellas.associate { (id, cotas, _) -> id to cotas.min }
        return aplicar { doc ->
            var raiz = doc.raiz
            for ((id, destinoMin) in destinos) {
                val origen = origenes.getValue(id)
                val deltaMundo = destinoMin - origen
                raiz = raiz.mapear(id) { pieza ->
                    // La traslación vive en el espacio del padre: el delta del mundo
                    // hay que traerlo aquí, y una rotación o escala del padre entran
                    // solas por la misma cuenta que el resto de movimientos.
                    val acumulado = doc.transformDelPadreDe(pieza.id)
                        ?.componer(pieza.transform) ?: pieza.transform
                    val deltaLocal = acumulado.worldToLocal(deltaMundo) - acumulado.worldToLocal(Vec3.ZERO)
                    pieza.copy(transform = pieza.transform.copy(
                        translation = pieza.transform.translation + deltaLocal,
                    ))
                }
            }
            doc.copy(raiz = raiz)
        }
    }

    /**
     * Separa los cuerpos de un ensamblaje sobre el plato, con la holgura de montaje
     * pedida entre cada par.
     *
     * Es la salida a impresora del ensamblaje: los cuerpos llegan encajados en el
     * modelo porque así se diseñan, y hay que repartirlos para que salgan separados
     * de una sola placa y monten después. Empaquetar **es** separar: la misma
     * colocación en filas, con el margen que pida el montaje.
     */
    fun separarEnsamblaje(ensamblajeId: String, holguraDeMontaje: Float = 0.5f): Boolean {
        val ensamblaje = documento.ensamblajes.firstOrNull { it.id == ensamblajeId }
            ?: return rechazar("No existe el ensamblaje $ensamblajeId")
        if (!ensamblaje.piezas.all { documento.buscar(it) != null }) {
            return rechazar("El ensamblaje «${ensamblaje.nombre}» tiene piezas que ya no están")
        }
        return empaquetarEnPlaca(ensamblaje.piezas, holguraDeMontaje)
    }

    // ------------------------------------------------------------------- hitos

    /**
     * Un punto del historial con nombre, para volver a él sin contar deshaceres.
     *
     * «antes de los agujeros» vale más que veinte ⌘Z. La marca guarda la profundidad
     * del historial en el momento —los documentos son inmutables, así que volver es
     * deshacer hasta esa altura—, y el cursor funciona en los dos sentidos mientras
     * el camino exista: deshacer y rehacer lo recorren.
     */
    private val hitos = LinkedHashMap<String, Documento>()

    fun marcarHito(nombre: String): Boolean {
        val limpio = nombre.trim()
        if (limpio.isEmpty()) return rechazar("Un hito sin nombre no se puede volver a buscar")
        // Los documentos son inmutables y el historial guarda referencias: guardar el
        // documento es guardar el estado exacto, sin números de profundidad que se
        // desincronicen si alguien deshace más allá y edita por encima.
        hitos[limpio] = documento
        return true
    }

    fun hitos(): List<String> = hitos.keys.toList()

    /**
     * Vuelve al documento que había cuando se marcó el hito.
     *
     * Puede fallar con honestidad: si después de marcar se deshizo más allá y se
     * editó por encima, el camino que llevaba al hito se perdió, y decirlo vale más
     * que fingir que se llegó.
     */
    fun deshacerHasta(nombre: String): Boolean {
        val objetivo = hitos[nombre] ?: return rechazar("No hay ningún hito llamado «$nombre»")
        if (documento === objetivo) return true
        // Los snapshots son referencias compartidas, así que basta comparar identidad.
        val estaAtras = historial.any { it === objetivo }
        val estaDelante = rehechos.any { it === objetivo }
        return when {
            estaAtras -> {
                while (documento !== objetivo) {
                    if (!deshacer()) return rechazar("El hito «$nombre» ya no se puede alcanzar")
                }
                true
            }
            estaDelante -> {
                while (documento !== objetivo) {
                    if (!rehacer()) return rechazar("El hito «$nombre» ya no se puede alcanzar")
                }
                true
            }
            else -> rechazar("El hito «$nombre» ya no se puede alcanzar: se editó por debajo de él")
        }
    }

    // ------------------------------------------------------------------- medidas

    /** Las procedencias que una medida puede declarar, en el orden del enum. */
    fun procedenciasDeMedida(): List<String> =
        ProcedenciaDeMedida.entries.map { it.name }

    /**
     * Fija de dónde salió una medida del mundo.
     *
     * La procedencia era un dato de nacimiento: la IA da de alta sus medidas «a ojo»
     * porque se las han dicho y no las ha medido, y hasta ahora subirla a «con
     * calibre» exigía tocar el núcleo a mano. Quien midió es exactamente quien debe
     * poder decirlo, y un encaje verificado contra una medida a ojo no vale lo mismo
     * que uno verificado contra una medida con calibre.
     */
    fun fijarProcedencia(medidaId: String, procedenciaNombre: String): Boolean {
        val procedencia = ProcedenciaDeMedida.entries.firstOrNull {
            it.name == procedenciaNombre.uppercase()
        } ?: return rechazar("Procedencia desconocida: $procedenciaNombre")
        if (documento.medidas.none { it.id == medidaId }) {
            return rechazar("No existe la medida $medidaId")
        }
        return aplicar { doc ->
            doc.copy(medidas = doc.medidas.map {
                if (it.id == medidaId) it.copy(procedencia = procedencia) else it
            })
        }
    }

    /**
     * Las medidas que ningún encaje usa, por identificador.
     *
     * Una medida huérfana no estorba, pero tampoco justifica nada: conviene saber
     * cuáles están ahí solo porque alguien dejó de declarar el encaje que las iba a
     * usar, o porque se soltó.
     */
    fun medidasSinUso(): List<String> {
        val usadas = documento.raiz.aplanar().flatMap { it.first.encajes }.map { it.medida }.toSet()
        return documento.medidas.filter { it.id !in usadas }.map { it.id }
    }

    /**
     * Activa —o quita— la parte de holgura proporcional al diámetro de un encaje ya
     * declarado. Una edición como cualquier otra: transaccional y deshacible.
     */
    fun fijarHolguraProporcional(piezaId: String, activa: Boolean): Boolean {
        val pieza = documento.buscar(piezaId) ?: return rechazar("No existe la pieza $piezaId")
        if (pieza.encajes.isEmpty()) return rechazar("${pieza.nombre} no tiene encaje declarado")
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(piezaId) {
                    it.copy(encajes = it.encajes.map { e -> e.copy(holguraProporcional = activa) })
                },
            )
        }
    }

    /** Los ensamblajes declarados en el documento, tal y como están. */
    fun ensamblajes(): List<Ensamblaje> = documento.ensamblajes

    /** El documento completo, para la frontera de persistencia y las pruebas. */
    fun documento(): Documento = documento

    /** El valor actual de un parámetro de una pieza, o `null` si la clave no existe. */
    fun parametro(id: String, clave: String): Float? {
        val pieza = documento.buscar(id) ?: return null
        if (pieza.tipo.parametrosCon(pieza.forma).none { it.clave == clave }) return null
        return pieza.parametro(clave)
    }

    /** Las cotas en el mundo de una pieza, o `null` si no aporta geometría. */
    fun cotasDe(id: String): Aabb? = documento.cotasEnMundoDe(id)

    /** La pieza pedida, para lecturas puntuales del documento. */
    fun pieza(id: String): Pieza? = documento.buscar(id)

    // ----------------------------------------------------------------- ensamblajes

    /**
     * Declara que estas piezas tienen que ir separadas y montarse después.
     *
     * Sin esta declaración una interferencia no significa nada —la raíz es una unión
     * y solapar piezas es como se construye una pieza— y con ella es siempre un
     * problema: nadie la declara para cuerpos que no tengan que ensamblar. Ver
     * [yunkil.fabricacion.VerificadorDeEnsamblajes].
     */
    fun declararEnsamblaje(nombre: String, piezas: List<String>): Boolean {
        val limpio = nombre.trim().ifEmpty { "Ensamblaje" }
        val unicas = piezas.distinct()
        if (unicas.size < 2) return rechazar("Un ensamblaje necesita al menos dos cuerpos")
        for (id in unicas) {
            val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
            if (pieza.id == documento.raiz.id) {
                return rechazar("La raíz no puede ser un cuerpo de un ensamblaje")
            }
        }
        val numero = documento.ensamblajes
            .mapNotNull { it.id.substringAfterLast('-').toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 1
        val ensamblaje = Ensamblaje("ensamblaje-$numero", limpio, unicas)
        return aplicar { doc -> doc.copy(ensamblajes = doc.ensamblajes + ensamblaje) }
    }

    fun quitarEnsamblaje(id: String): Boolean {
        if (documento.ensamblajes.none { it.id == id }) {
            return rechazar("No existe el ensamblaje $id")
        }
        return aplicar { doc ->
            doc.copy(ensamblajes = doc.ensamblajes.filter { it.id != id })
        }
    }

    /**
     * Mide las interferencias de cada ensamblaje declarado. No toca el documento.
     *
     * Lo que no se puede medir no se aprueba: el par llega con `solape = null` y el
     * motivo de que no hay campo se lo debe contar quien enseñe la lista.
     */
    fun verificarEnsamblajes(paso: Float = 0.8f): List<InterferenciaDeCuerpos> =
        VerificadorDeEnsamblajes(documento, paso).verificar()

    // ----------------------------------------------------------------- variantes

    /**
     * Clona la pieza una vez por valor del parámetro, en una sola transacción.
     *
     * Es la operación del trabajo de encaje de toda la vida: «dame el tapón para 19,
     * para 20 y para 21, y pruebo cuál va». Hacerlo a mano son tres duplicaciones,
     * tres retoces de parámetro y tres renombrados con seis puntos de deshacer.
     *
     * Las variantes salen **en fila**, separadas por el ancho de la pieza más un
     * margen, para que se puedan mandar a imprimir juntas y probarlas sin confundir
     * cuál es cuál: el valor va en el nombre de cada una. El encaje **no** viaja a las
     * copias a propósito: todas las variantes comparten la misma medida del mundo y
     * el resolver las aplanaría todas al mismo tamaño, que es justo lo que la prueba
     * quiere distinguir.
     */
    fun generarVariantes(id: String, clave: String, valores: List<Float>): Boolean {
        if (id == documento.raiz.id) return rechazar("La raíz no tiene parámetros que variar")
        val original = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        val definicion = original.tipo.parametrosCon(original.forma)
            .firstOrNull { it.clave == clave }
            ?: return rechazar("«$clave» no es un parámetro de ${original.nombre}")
        if (valores.isEmpty() || valores.size > 12) {
            return rechazar("Entre 1 y 12 variantes; llegaron ${valores.size}")
        }
        for (valor in valores) {
            if (!valor.isFinite() || valor < definicion.minimo || valor > definicion.maximo) {
                return rechazar(
                    "${definicion.etiqueta} tiene que estar entre ${redondeado(definicion.minimo)} " +
                        "y ${redondeado(definicion.maximo)}; llegó ${redondeado(valor)}",
                )
            }
        }
        val ancho = documento.cotasEnMundoDe(id)?.size?.x ?: 0f
        val paso = ancho + 4f
        val destino = documento.padreDe(id) ?: documento.raiz.id

        val variantes = valores.mapIndexed { indice, valor ->
            val base = Pieza.nueva(original.tipo, "${original.nombre} ${redondeado(valor)} mm")
            base.copy(
                parametros = original.parametros + (clave to valor),
                transform = original.transform.copy(
                    translation = original.transform.translation + Vec3(paso * (indice + 1), 0f, 0f),
                ),
                hijos = original.hijos,
                forma = original.forma,
                puntos = original.puntos,
                contratoOrganico = original.contratoOrganico,
                // Los encajes se quedan en la original: ver arriba.
                encajes = emptyList(),
            )
        }
        val primera = variantes.first().id
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(destino) { it.copy(hijos = it.hijos + variantes) },
                seleccionado = primera,
            )
        }
    }

    // -------------------------------------------------- partes orgánicas semánticas

    /**
     * Aplica una edición semántica a una parte del contrato de una escultura, como
     * una transacción deshacible.
     *
     * El cambio lo resuelve `MotorOrganico` con el mismo validador que aceptó el
     * contrato: si la parte editada deja de tocar a su padre, se rechaza con el motivo
     * y el documento queda exactamente como estaba. Mover la cola no debe poder
     * partir la figura por la mitad.
     */
    private fun editarContratoOrganico(
        id: String,
        cambio: (contrato: String) -> ResultadoContratoOrganico,
    ): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        val contrato = pieza.contratoOrganico
            ?: return rechazar("La escultura no conserva su anatomía")
        if (pieza.tipo != TipoPieza.ESCULTURA) return rechazar("La edición semántica es de esculturas")
        val resultado = cambio(contrato)
        val canonico = resultado.contratoCanonico
            ?: return rechazar(resultado.motivo ?: "No se pudo editar la parte")
        return aplicar { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(contratoOrganico = canonico) })
        }
    }

    /**
     * Mueve una parte orgánica —«la cola», «la oreja izquierda»— por su identificador
     * semántico, en milímetros del espacio local de la escultura.
     *
     * Es el primer paso de la modificación directa de partes orgánicas: hasta ahora
     * mover una cola exigía reescribir el contrato o pedírselo otra vez a la IA, y el
     * identificador ya sabía a qué parte se refería.
     */
    fun moverParteOrganica(id: String, parteId: String, dx: Float, dy: Float, dz: Float): Boolean =
        editarContratoOrganico(id) { MotorOrganico.moverParte(it, parteId, dx, dy, dz) }

    /** Engorda —o afina, con delta negativo— una parte orgánica sin mover su eje. */
    fun engordarParteOrganica(id: String, parteId: String, deltaMm: Float): Boolean =
        editarContratoOrganico(id) { MotorOrganico.engordarParte(it, parteId, deltaMm) }

    /** Quita una parte orgánica y reataja a las que colgaban de ella. */
    fun quitarParteOrganica(id: String, parteId: String): Boolean =
        editarContratoOrganico(id) { MotorOrganico.quitarParte(it, parteId) }

    /**
     * Qué parte de la anatomía hay bajo un punto **del mundo**.
     *
     * Convierte el punto al espacio local de la escultura con la misma transformación
     * que usan las brochas —girar o escalar la figura gira el gesto con ella— y deja
     * en manos del motor decir qué parte está debajo. Es lo que un clic de selección
     * necesita para responder «la cola» en vez de «la escultura».
     */
    fun parteOrganicaBajo(id: String, x: Float, y: Float, z: Float): String? {
        val pieza = documento.buscar(id) ?: return null
        val contrato = pieza.contratoOrganico ?: return null
        if (pieza.tipo != TipoPieza.ESCULTURA) return null
        val acumulado = documento.transformDelPadreDe(id)?.componer(pieza.transform) ?: pieza.transform
        val local = acumulado.worldToLocal(Vec3(x, y, z))
        return MotorOrganico.parteEnPunto(contrato, local.x, local.y, local.z)
    }

    // -------------------------------------------------- perfiles

    fun formasDePerfil(): List<String> = FormaDePerfil.entries.map { it.name }

    fun formaDe(id: String): String =
        documento.buscar(id)?.forma?.name ?: FormaDePerfil.RECTANGULO.name

    fun tienePerfil(id: String): Boolean {
        val tipo = documento.buscar(id)?.tipo ?: return false
        return tipo == TipoPieza.EXTRUSION || tipo == TipoPieza.REVOLUCION ||
            tipo == TipoPieza.BARRIDO
    }

    fun esEscultura(id: String): Boolean = documento.buscar(id)?.tipo == TipoPieza.ESCULTURA
    fun contratoDeEscultura(id: String): String? =
        documento.buscar(id)?.takeIf { it.tipo == TipoPieza.ESCULTURA }?.contratoOrganico

    fun fusionDeEscultura(id: String): Float =
        contratoDeEscultura(id)?.let(MotorOrganico::fusionDe) ?: 0f

    fun fijarFusionDeEscultura(id: String, fusionMm: Float): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la escultura")
        val contrato = pieza.contratoOrganico ?: return rechazar("La escultura no conserva su anatomía")
        val resultado = MotorOrganico.fijarFusion(contrato, fusionMm)
        val canonico = resultado.contratoCanonico
            ?: return rechazar(resultado.motivo ?: "No se pudo cambiar la suavidad")
        return aplicar(registrarEnHistorial = false) { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(contratoOrganico = canonico) })
        }
    }

    /**
     * Añade un cable —tubo de radio variable sobre una polilínea 3D— como pieza
     * paramétrica del documento.
     *
     * Es el mismo nodo que las curvas del motor orgánico, pero aquí la curva es de
     * las de siempre: editable, transformable, taladrable, exportable con su
     * certificado y deshacible en un paso. Los radios viajan punto a punto; el último
     * puede ser 0 para afilar la punta, y un radio intermedio a 0 no se acepta porque
     * no es una punta sino una curva partida en dos.
     */
    fun anadirCable(
        puntos: List<Vec3>,
        radios: List<Float>,
        nombre: String = "Cable",
        padreId: String? = null,
    ): Boolean {
        if (puntos.size < 2) return rechazar("Un cable necesita al menos dos puntos")
        if (puntos.size > Cordon.MAXIMO_DE_PUNTOS) {
            return rechazar("Un cable admite hasta ${Cordon.MAXIMO_DE_PUNTOS} puntos; llegaron ${puntos.size}")
        }
        if (radios.size != puntos.size) {
            return rechazar("Cada punto del cable necesita su radio: ${puntos.size} puntos, ${radios.size} radios")
        }
        if (puntos.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) {
            return rechazar("Un punto del cable no es un punto: hay coordenadas infinitas o vacías")
        }
        if (radios.any { !it.isFinite() || it < 0f }) {
            return rechazar("Un radio del cable es negativo o no es un número")
        }
        if (radios.dropLast(1).any { it <= 0f }) {
            return rechazar("Solo el último radio puede ser 0: un radio intermedio parte el cable en dos")
        }
        val cable = Pieza.nueva(TipoPieza.CABLE, nombre).copy(
            puntosDeCable = puntos,
            radiosDeCable = radios,
        )
        val destino = destinoValidoPara(padreId ?: documento.raiz.id)
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(destino) { it.copy(hijos = it.hijos + cable) },
                seleccionado = cable.id,
            )
        }
    }

    /**
     * Reescribe la polilínea de un cable existente, como una sola edición deshacible.
     *
     * La curva se agarra punto a punto en el viewport o se pide de nuevo; los dos
     * caminos acaban aquí, que es donde la validación vive una sola vez.
     */
    fun fijarCable(id: String, puntos: List<Vec3>, radios: List<Float>): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        if (pieza.tipo != TipoPieza.CABLE) return rechazar("${pieza.nombre} no es un cable")
        val provisional = pieza.copy(puntosDeCable = puntos, radiosDeCable = radios)
        if (!provisional.esCableValido) {
            return rechazar("El cable nuevo no se puede construir")
        }
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(id) {
                    it.copy(puntosDeCable = puntos, radiosDeCable = radios)
                },
            )
        }
    }

    /** Inserta una escultura semántica sin convertirla en una malla opaca. */
    fun anadirEscultura(contratoCanonico: String, padreId: String? = null): Boolean {        val interpretado = MotorOrganico.interpretar(contratoCanonico)
        val canonico = interpretado.contratoCanonico
            ?: return rechazar(interpretado.motivo ?: "Contrato orgánico inválido")
        if (MotorOrganico.nodoDeContrato(canonico) == null) {
            return rechazar("La escultura no produce geometría válida")
        }
        val nueva = Pieza.nueva(TipoPieza.ESCULTURA).copy(
            nombre = interpretado.nombre.ifBlank { "Escultura" },
            contratoOrganico = canonico,
        )
        val destino = destinoValidoPara(padreId ?: documento.raiz.id)
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(destino) { it.copy(hijos = it.hijos + nueva) },
                seleccionado = nueva.id,
            )
        }
    }

    fun reemplazarEscultura(id: String, contratoCanonico: String): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la escultura")
        if (pieza.tipo != TipoPieza.ESCULTURA) return rechazar("La pieza seleccionada no es una escultura")
        val interpretado = MotorOrganico.interpretar(contratoCanonico)
        val canonico = interpretado.contratoCanonico
            ?: return rechazar(interpretado.motivo ?: "Contrato orgánico inválido")
        if (MotorOrganico.nodoDeContrato(canonico) == null) return rechazar("La escultura no produce geometría válida")
        return aplicar { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) {
                it.copy(nombre = interpretado.nombre.ifBlank { it.nombre }, contratoOrganico = canonico)
            })
        }
    }

    /** Añade una escultura solo sobre el estado sobre el que se generó su propuesta. */
    fun anadirEsculturaEnVersion(
        contratoCanonico: String,
        versionEsperada: Long,
        padreId: String? = null,
    ): Boolean {
        if (versionDocumento != versionEsperada) return rechazar(DOCUMENTO_CAMBIADO)
        return anadirEscultura(contratoCanonico, padreId)
    }

    /** Reemplaza una escultura solo sobre el estado sobre el que se generó su propuesta. */
    fun reemplazarEsculturaEnVersion(id: String, contratoCanonico: String, versionEsperada: Long): Boolean {
        if (versionDocumento != versionEsperada) return rechazar(DOCUMENTO_CAMBIADO)
        return reemplazarEscultura(id, contratoCanonico)
    }

    /** Brocha esférica en coordenadas del mundo; se persiste como operación semántica. */
    fun aplicarBrochaOrganica(
        id: String,
        modo: String,
        x: Float,
        y: Float,
        z: Float,
        radio: Float,
        simetriaX: Boolean = false,
    ): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la escultura")
        val contrato = pieza.contratoOrganico ?: return rechazar("La escultura no conserva su anatomía")
        if (pieza.tipo != TipoPieza.ESCULTURA) return rechazar("La brocha solo se aplica a esculturas")
        val acumulado = documento.transformDelPadreDe(id)?.componer(pieza.transform) ?: pieza.transform
        val local = acumulado.worldToLocal(Vec3(x, y, z))
        val resultado = MotorOrganico.aplicarBrocha(
            contrato, modo, local.x, local.y, local.z, radio / acumulado.scale, simetriaX,
        )
        val canonico = resultado.contratoCanonico
            ?: return rechazar(resultado.motivo ?: "No se pudo aplicar la brocha")
        return aplicar { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(contratoOrganico = canonico) })
        }
    }

    /**
     * Alisar, pellizcar o arrastrar sobre una escultura, en coordenadas del mundo.
     *
     * El vector de arrastre viaja como dirección, no como punto: girar la escultura tiene
     * que girar el gesto con ella, y escalarla tiene que escalarlo. Pasarlo por el mismo
     * `worldToLocal` que el centro lo trasladaría además por la posición de la pieza, y
     * tirar de una oreja en una figura colocada a 40 mm del origen mandaría el material a
     * cuarenta milímetros de donde apunta el ratón.
     */
    fun aplicarDeformacionOrganica(
        id: String,
        modo: String,
        x: Float,
        y: Float,
        z: Float,
        radio: Float,
        intensidad: Float,
        dx: Float,
        dy: Float,
        dz: Float,
        simetriaX: Boolean,
        continuandoTrazo: Boolean,
    ): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la escultura")
        if (pieza.tipo != TipoPieza.ESCULTURA) return rechazar("La brocha solo se aplica a esculturas")
        val contrato = pieza.contratoOrganico ?: return rechazar("La escultura no conserva su anatomía")
        val acumulado = documento.transformDelPadreDe(id)?.componer(pieza.transform) ?: pieza.transform
        val local = acumulado.worldToLocal(Vec3(x, y, z))
        val gesto = acumulado.worldToLocal(Vec3(x + dx, y + dy, z + dz)) - local
        val resultado = MotorOrganico.aplicarDeformacion(
            contratoCanonico = contrato,
            modo = modo,
            x = local.x, y = local.y, z = local.z,
            radio = radio / acumulado.scale,
            intensidad = intensidad,
            dx = gesto.x, dy = gesto.y, dz = gesto.z,
            simetriaX = simetriaX,
            continuandoTrazo = continuandoTrazo,
        )
        val canonico = resultado.contratoCanonico
            ?: return rechazar(resultado.motivo ?: "No se pudo aplicar la brocha")
        // Devuelve **si se aplicó**, no si hay que recompilar, que es lo que devuelven las
        // demás ediciones. La diferencia importa aquí y en ningún otro sitio: una muestra
        // de brocha que se funde con la zona anterior cambia uniforms sin tocar la
        // topología, y con el criterio de siempre eso saldría `false` —o sea, «rechazada»—
        // en mitad de un arrastre que va perfectamente. Recompilar lo decide luego la
        // huella, que el renderizador ya compara antes de rehacer el pipeline.
        if (canonico == contrato) return true
        aplicar { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(contratoOrganico = canonico) })
        }
        return documento.buscar(id)?.contratoOrganico == canonico
    }

    /** Quita todas las zonas de una brocha de campo sin tocar la anatomía. */
    fun limpiarDeformacionOrganica(id: String, modo: String): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la escultura")
        val contrato = pieza.contratoOrganico ?: return rechazar("La escultura no conserva su anatomía")
        val resultado = MotorOrganico.limpiarDeformaciones(contrato, modo)
        val canonico = resultado.contratoCanonico
            ?: return rechazar(resultado.motivo ?: "No se pudieron quitar las zonas")
        aplicar { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(contratoOrganico = canonico) })
        }
        return documento.buscar(id)?.contratoOrganico == canonico
    }

    /**
     * El plano espejo de una escultura, en coordenadas del mundo: normal (3) y punto (3).
     *
     * Cruza a Swift en flotantes sueltos como el resto de la frontera. Sale en el espacio
     * del mundo y no en el de la pieza porque es donde el usuario lo señala: se elige
     * pinchando una cara, y esa normal viene del picking.
     */
    fun planoDeSimetriaDe(id: String): List<Float> {
        val contrato = contratoDeEscultura(id) ?: return listOf(1f, 0f, 0f, 0f, 0f, 0f)
        val local = MotorOrganico.simetriaDe(contrato)
        val acumulado = transformAcumuladoDe(id)
        val nLocal = Vec3(local[0], local[1], local[2])
        val n = applyMatrix(acumulado.rotation.toMatrixRowMajor(), nLocal)
        val enElPlano = acumulado.localToWorld(nLocal * local[3])
        return listOf(n.x, n.y, n.z, enElPlano.x, enElPlano.y, enElPlano.z)
    }

    /**
     * Fija el plano espejo desde una normal y un punto del mundo.
     *
     * Se recibe un punto y no una distancia porque así es como se elige: se pincha una
     * cara y el picking devuelve dónde y hacia dónde. Convertir eso a un desplazamiento a
     * mano en la interfaz sería repetir la misma cuenta en Swift y en Kotlin.
     */
    fun fijarPlanoDeSimetria(
        id: String,
        nx: Float,
        ny: Float,
        nz: Float,
        px: Float,
        py: Float,
        pz: Float,
    ): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la escultura")
        val contrato = pieza.contratoOrganico ?: return rechazar("La escultura no conserva su anatomía")
        val acumulado = transformAcumuladoDe(id)
        val n = applyMatrix(acumulado.rotation.conjugate().toMatrixRowMajor(), Vec3(nx, ny, nz))
        val enLocal = acumulado.worldToLocal(Vec3(px, py, pz))
        val largo = n.length()
        if (!largo.isFinite() || largo < 1e-3f) return rechazar("El plano de simetría necesita una normal")
        val unitaria = n / largo
        val desplazamiento = unitaria.x * enLocal.x + unitaria.y * enLocal.y + unitaria.z * enLocal.z
        val resultado = MotorOrganico.fijarSimetria(
            contrato, unitaria.x, unitaria.y, unitaria.z, desplazamiento,
        )
        val canonico = resultado.contratoCanonico
            ?: return rechazar(resultado.motivo ?: "No se pudo fijar el plano de simetría")
        aplicar { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(contratoOrganico = canonico) })
        }
        return documento.buscar(id)?.contratoOrganico == canonico
    }

    /**
     * El plano espejo por uno de los tres ejes de la figura, pasando por su origen.
     *
     * Es el caso de siempre —una figura de pie y centrada— y no debe obligar a pinchar
     * nada. Va aparte de [fijarPlanoDeSimetria] porque su normal es local: convertirla a
     * mundo en la interfaz para que el núcleo la devolviera a local sería dar dos vueltas
     * a un dato que ya está en el sitio correcto.
     */
    fun fijarEjeDeSimetria(id: String, eje: String): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la escultura")
        val contrato = pieza.contratoOrganico ?: return rechazar("La escultura no conserva su anatomía")
        val n = when (eje.uppercase()) {
            "X" -> Vec3(1f, 0f, 0f)
            "Y" -> Vec3(0f, 1f, 0f)
            "Z" -> Vec3(0f, 0f, 1f)
            else -> return rechazar("«$eje» no es un eje de simetría")
        }
        val resultado = MotorOrganico.fijarSimetria(contrato, n.x, n.y, n.z, 0f)
        val canonico = resultado.contratoCanonico
            ?: return rechazar(resultado.motivo ?: "No se pudo fijar el plano de simetría")
        aplicar { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(contratoOrganico = canonico) })
        }
        return documento.buscar(id)?.contratoOrganico == canonico
    }

    private fun transformAcumuladoDe(id: String) =
        documento.transformDelPadreDe(id)?.componer(documento.buscar(id)?.transform ?: Transform.IDENTITY)
            ?: documento.buscar(id)?.transform ?: Transform.IDENTITY

    /** Cuántas zonas de una brocha de campo tiene la escultura. Es lo que enseña el panel. */
    fun zonasDeEscultura(id: String, modo: String): Int =
        contratoDeEscultura(id)?.let { MotorOrganico.cuentaDeZonas(it, modo) } ?: 0

    /**
     * Trae un STL de fuera y lo deja como una pieza más del árbol.
     *
     * Es el punto por el que entra la geometría que no se puede escribir. A partir de
     * aquí la malla deja de ser especial: se le resta, se ahueca, se taladra, se acota
     * y se exporta por la misma cadena verificada que todo lo demás.
     *
     * Se comprueba la topología antes de hornear y **se avisa sin bloquear**. El signo
     * del campo sale de contar cruces, y eso da por supuesto que la superficie cierra;
     * con agujeros el resultado es poco fiable. Pero negarse dejaría fuera la mitad de
     * lo que circula por internet, así que se importa y se dice.
     */
    fun importarMalla(ruta: String, resolucion: Float = 0f, padreId: String? = null): Boolean =
        when (val horneada = hornearMallaDesde(ruta, resolucion)) {
            is MallaImportada.Fallo -> rechazar(horneada.motivo)
            is MallaImportada.Lista -> colocarMalla(horneada, padreId)
        }

    /**
     * Lee el STL y hornea su campo, **sin tocar el editor**.
     *
     * Es la parte cara —rasterizar cada triángulo contra una rejilla— y la única que se
     * puede hacer fuera del hilo de la interfaz, así que se separa de la parte que muta el
     * documento. Por eso devuelve el motivo del fallo en el resultado en vez de escribirlo
     * en `ultimoError`: escribir estado compartido desde otro hilo es justo lo que esta
     * división existe para evitar.
     *
     * Se comprueba la topología antes de hornear y **se avisa sin bloquear**. El signo del
     * campo sale de contar cruces, y eso da por supuesto que la superficie cierra; con
     * agujeros el resultado es poco fiable. Pero negarse dejaría fuera la mitad de lo que
     * circula por internet, así que se importa y se dice.
     */
    fun hornearMallaDesde(ruta: String, resolucion: Float = 0f): MallaImportada {
        val bytes = leerArchivo(ruta) ?: return MallaImportada.Fallo("No se pudo leer $ruta")

        val leido = LectorStl.leer(bytes)
        if (leido is LectorStl.Resultado.Fallo) {
            return MallaImportada.Fallo("No es un STL válido: ${leido.motivo}")
        }
        val malla = (leido as LectorStl.Resultado.Leida).malla
        if (malla.numeroDeTriangulos == 0) {
            return MallaImportada.Fallo("El archivo no trae ningún triángulo")
        }

        val topologia = malla.revisarTopologia()
        val aviso = if (topologia.esCerrada) null else
            "la malla trae ${topologia.aristasAbiertas} aristas abiertas; el interior puede salir mal"

        // Resolución por omisión: el lado mayor entre 120. Es fino para ver la forma y
        // no dispara ni la memoria ni la espera.
        val tamano = malla.cotas().size
        val mayor = maxOf(tamano.x, maxOf(tamano.y, tamano.z))
        val paso = if (resolucion > 0f) resolucion else maxOf(mayor / 120f, 0.05f)

        val campo = try {
            CampoDeMalla.hornear(malla.vertices, malla.triangulos, paso, origen = ruta)
        } catch (e: Throwable) {
            return MallaImportada.Fallo("No se pudo hornear la malla: ${e.message}")
        }

        return MallaImportada.Lista(
            campo = campo,
            ruta = ruta,
            nombre = ruta.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Malla" },
            aviso = aviso,
        )
    }

    /** Coloca en el documento un campo ya horneado. Esto es lo barato, y va en el hilo de siempre. */
    fun colocarMalla(horneada: MallaImportada.Lista, padreId: String? = null): Boolean {
        val nueva = Pieza.nueva(TipoPieza.MALLA).copy(
            nombre = horneada.nombre,
            rutaDeMalla = horneada.ruta,
            campoDeMalla = horneada.campo,
        )
        val destino = destinoValidoPara(padreId ?: documento.raiz.id)

        val aplicado = aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(destino) { it.copy(hijos = it.hijos + nueva) },
                seleccionado = nueva.id,
            )
        }
        // El aviso viaja por `ultimoError` a propósito: la pieza ya está puesta, pero
        // quien la mire tiene que enterarse de que su interior es una suposición.
        if (aplicado && horneada.aviso != null) ultimoError = horneada.aviso
        return aplicado
    }

    /**
     * Vuelve a hornear las mallas de un documento recién abierto.
     *
     * El campo no se guarda en el archivo, así que al abrir llega a nulo y la pieza
     * compilaría a nada. Devuelve las que no se pudieron recuperar, para poder decir
     * cuáles y no dejar huecos silenciosos en el árbol.
     */
    fun rehornearMallas(): List<String> {
        val perdidas = ArrayList<String>()
        for (id in mallasSinHornear()) {
            val ruta = rutaDeMalla(id)
            val horneada = ruta?.let { hornearMallaDesde(it) }
            if (horneada is MallaImportada.Lista) {
                ponerCampoHorneado(id, horneada)
            } else {
                perdidas.add(documento.buscar(id)?.nombre ?: id)
            }
        }
        return perdidas
    }

    /**
     * Las mallas que están en el árbol sin su campo, que son las que hay que hornear.
     *
     * Existe para poder hacerlo **fuera del hilo de la interfaz**: abrir un proyecto con dos
     * STL importados es rasterizar millones de triángulos contra una rejilla, y hasta que
     * esto se pudo repartir, la ventana se quedaba muerta durante todo el rato. Quien llama
     * pide los identificadores aquí, hornea cada uno donde quiera —[hornearMallaDesde] no
     * toca el documento— y vuelve con [ponerCampoHorneado].
     */
    fun mallasSinHornear(): List<String> {
        val pendientes = ArrayList<String>()
        fun recorrer(pieza: Pieza) {
            if (pieza.tipo == TipoPieza.MALLA && pieza.campoDeMalla == null) pendientes.add(pieza.id)
            pieza.hijos.forEach(::recorrer)
        }
        recorrer(documento.raiz)
        return pendientes
    }

    /** De qué archivo salió una malla importada. */
    fun rutaDeMalla(id: String): String? = documento.buscar(id)?.rutaDeMalla

    /**
     * Pone un campo ya horneado en una malla que ya está en el árbol.
     *
     * Sin registrar en el historial: rehornear no es una edición que nadie quiera deshacer
     * —el documento dice lo mismo antes y después—, pero sí tiene que recompilar el shader,
     * porque el árbol pasa de no tener nada que dibujar a tenerlo.
     */
    fun ponerCampoHorneado(id: String, horneada: MallaImportada.Lista): Boolean {
        if (documento.buscar(id) == null) return rechazar("No existe la pieza $id")
        return aplicar(registrarEnHistorial = false) { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(campoDeMalla = horneada.campo) })
        }
    }

    /**
     * Taladra una pieza con un patrón de montaje normalizado.
     *
     * Es la diferencia entre «se parece» y «encaja». Un soporte de rack con los
     * agujeros a la separación equivocada no es un soporte peor: no entra. Aquí el
     * modelo dice `RACK_19` con sus unidades y el núcleo pone los agujeros donde los
     * pone la norma, con la rosca que espera y la holgura de esta impresora.
     *
     * Cada agujero se abre sobre lo que dejó el anterior: tras el primer taladro la
     * pieza ya es una DIFERENCIA, y seguir apuntando a la original dejaría los
     * agujeros colgando fuera del árbol.
     */
    fun aplicarPatron(
        objetivoId: String,
        estandar: String,
        unidades: Int = 1,
        ejeNombre: String = "Y",
        ajusteNombre: String = AjusteDeTaladro.PASANTE.name,
        nombrePerfil: String? = null,
    ): Boolean {
        val patron = Estandares.porNombre(estandar, unidades)
            ?: return rechazar(
                "Patrón de montaje desconocido: $estandar. Los que hay son ${Estandares.designaciones}",
            )
        if (patron.puntos.isEmpty()) return rechazar("El patrón $estandar no tiene agujeros")

        val abierta = enTransaccion
        if (!abierta) abrirTransaccion()

        var destino = objetivoId
        for (punto in patron.puntos) {
            taladrar(
                objetivoId = destino,
                designacion = patron.rosca,
                ajusteNombre = ajusteNombre,
                ejeNombre = ejeNombre,
                desplazamientoA = punto.x,
                desplazamientoB = punto.y,
                nombrePerfil = nombrePerfil,
            )
            ultimoError?.let {
                if (!abierta) revertirTransaccion(historial.lastOrNull() ?: documento)
                return rechazar("no se pudo abrir el patrón $estandar: $it")
            }
            destino = seleccionado ?: destino
        }

        if (!abierta) cerrarTransaccion()
        return true
    }

    /**
     * El contorno de la pieza, o el de la primera descendiente que tenga uno.
     *
     * Hace falta descender porque el segundo taladro de una pieza no apunta ya a la
     * extrusión sino a la DIFERENCIA que la envolvió al abrir el primer agujero. Sin
     * esto, «dos agujeros con punto de contorno» funcionaría en el primero y fallaría
     * en el segundo, que es la peor forma posible de fallar.
     */
    private fun perfilDelSubarbol(pieza: Pieza): Perfil2D? {
        if (tienePerfil(pieza.id)) return pieza.perfil()
        for (hijo in pieza.hijos) perfilDelSubarbol(hijo)?.let { return it }
        return null
    }

    /** Desviación del contorno teselado respecto a la curva ideal, en milímetros. */
    fun desviacionDelPerfil(id: String): Float =
        documento.buscar(id)?.takeIf { tienePerfil(id) }?.perfil()?.desviacionMaxima ?: 0f

    fun areaDelPerfil(id: String): Float =
        documento.buscar(id)?.takeIf { tienePerfil(id) }?.perfil()?.area() ?: 0f

    /** Contorno aplanado en pares (x, y), para que la interfaz lo pueda dibujar. */
    fun contornoDe(id: String): List<Float> {
        val pieza = documento.buscar(id) ?: return emptyList()
        if (!tienePerfil(id)) return emptyList()
        return pieza.perfil().poligono.flatMap { listOf(it.x, it.y) }
    }

    /**
     * Cambia la familia del contorno.
     *
     * Los parámetros de la forma nueva se siembran con sus valores por defecto,
     * pero **se conservan los que ya existían con el mismo nombre**: pasar de
     * rectángulo a ranura mantiene el ancho que el usuario ya había acotado en vez
     * de tirarlo, que es lo que espera cualquiera al probar formas.
     */
    fun fijarForma(id: String, nombreDeForma: String): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        if (!tienePerfil(id)) return rechazar("«${pieza.tipo.etiqueta}» no se construye con un perfil")
        val forma = FormaDePerfil.entries.firstOrNull { it.name == nombreDeForma.uppercase() }
            ?: return rechazar("Forma de perfil desconocida: $nombreDeForma")

        val defectos = pieza.tipo.parametrosCon(forma).associate { it.clave to it.defecto }
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(id) {
                    it.copy(forma = forma, parametros = defectos + it.parametros)
                },
            )
        }
    }

    /** Contorno libre a partir de pares (x, y) en milímetros. */
    fun fijarPuntosDelPerfil(id: String, coordenadas: List<Float>): Boolean {
        if (!tienePerfil(id)) return rechazar("Esa pieza no se construye con un perfil")
        if (coordenadas.size < 6 || coordenadas.size % 2 != 0) {
            return rechazar("Un contorno necesita al menos tres puntos en pares (x, y)")
        }
        if (coordenadas.any { !it.isFinite() || abs(it) > 10_000f }) {
            return rechazar("Alguna coordenada del contorno no es un número válido")
        }
        val recibidos = coordenadas.chunked(2).map { Punto2(it[0], it[1]) }
        val puntos = if (
            recibidos.size > 3 &&
            abs(recibidos.first().x - recibidos.last().x) < 1e-5f &&
            abs(recibidos.first().y - recibidos.last().y) < 1e-5f
        ) recibidos.dropLast(1) else recibidos
        if (puntos.size < 3) return rechazar("Un contorno necesita al menos tres puntos distintos")

        fun cruz(a: Punto2, b: Punto2, c: Punto2): Float =
            (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        fun seCruzan(a: Punto2, b: Punto2, c: Punto2, d: Punto2): Boolean {
            val abC = cruz(a, b, c)
            val abD = cruz(a, b, d)
            val cdA = cruz(c, d, a)
            val cdB = cruz(c, d, b)
            return abC * abD < -1e-8f && cdA * cdB < -1e-8f
        }
        for (i in puntos.indices) {
            val siguienteI = (i + 1) % puntos.size
            for (j in i + 1 until puntos.size) {
                val siguienteJ = (j + 1) % puntos.size
                if (siguienteI == j || siguienteJ == i) continue
                if (seCruzan(puntos[i], puntos[siguienteI], puntos[j], puntos[siguienteJ])) {
                    return rechazar("El contorno se cruza consigo mismo entre los tramos ${i + 1} y ${j + 1}")
                }
            }
        }
        val perfil = Perfil2D.poligono(puntos)
        if (perfil.area() < 1e-4f) {
            return rechazar("El contorno no encierra área: revisa que los puntos no estén alineados")
        }
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(id) {
                    it.copy(forma = FormaDePerfil.LIBRE, puntos = puntos)
                },
            )
        }
    }

    val idDeLaRaiz: String get() = documento.raiz.id

    // ------------------------------------------------------------------ transacciones

    /**
     * Abre un bloque de ediciones que se deshace de una sola vez.
     *
     * Un plan de treinta operaciones que dejara treinta puntos de deshacer sería
     * inservible: el usuario pulsó una vez «aplicar» y espera poder pulsar una vez
     * deshacer. Devuelve el documento previo para poder revertir si algo falla a
     * mitad de camino.
     */
    fun abrirTransaccion(): Documento {
        historial.addLast(documento)
        recortarHistorial()
        rehechos.clear()
        enTransaccion = true
        return documento
    }

    fun cerrarTransaccion() {
        enTransaccion = false
    }

    /** Deshace la transacción entera y borra su punto de deshacer: nunca existió. */
    fun revertirTransaccion(punto: Documento) {
        enTransaccion = false
        historial.removeLastOrNull()
        documento = punto
        regenerar()
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

    /**
     * Mueve la pieza a lo largo de una dirección **del mundo**, en milímetros.
     *
     * Es lo único que sabe decir un gizmo: dibuja una flecha que apunta a X, Y o Z del
     * mundo y entrega los milímetros que se ha arrastrado por ella. La pieza, en cambio,
     * guarda su traslación en el marco de su padre, y con un grupo girado los dos marcos no
     * coinciden: escribir el avance directamente en la traslación movería la pieza por otro
     * eje, y quien tira de la flecha vería la pieza irse por donde no ha tirado.
     *
     * La conversión es geometría, así que se hace aquí y no en la aplicación, que solo pone
     * píxeles. No se anota en el historial: un arrastre son cien de estas llamadas y son un
     * solo ⌘Z, que abre y cierra quien maneja el gesto.
     */
    fun moverEnElMundo(id: String, x: Float, y: Float, z: Float, milimetros: Float): Boolean {
        if (documento.buscar(id) == null) return rechazar("No existe la pieza $id")
        if (!milimetros.isFinite()) return rechazar("El avance no es un número")
        val direccion = normalizada(Vec3(x, y, z))
            ?: return rechazar("Esa dirección no apunta a ninguna parte")

        val padre = documento.transformDelPadreDe(id) ?: Transform.IDENTITY
        val avance = enElMarcoDelPadre(padre, direccion * milimetros)
        return aplicar(registrarEnHistorial = false) { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(id) {
                    it.copy(transform = it.transform.copy(translation = it.transform.translation + avance))
                },
            )
        }
    }

    /**
     * Gira la pieza alrededor de un eje **del mundo** que pasa por el centro de su caja.
     *
     * Por el centro y no por su origen: una pieza cuyo origen esté a 200 mm giraría
     * describiendo un arco de 200 mm de radio y se iría de la pantalla, cuando lo que se ha
     * pedido es orientarla. Es de los defectos que se notan a la primera y no se perdonan.
     *
     * Igual que [moverEnElMundo], el eje llega en coordenadas del mundo y se traduce al
     * marco del padre antes de componerlo con la rotación que ya tenía la pieza.
     */
    fun girarEnElMundo(id: String, x: Float, y: Float, z: Float, grados: Float): Boolean {
        if (documento.buscar(id) == null) return rechazar("No existe la pieza $id")
        if (!grados.isFinite()) return rechazar("El giro no es un número")
        val eje = normalizada(Vec3(x, y, z)) ?: return rechazar("Ese eje no apunta a ninguna parte")

        val padre = documento.transformDelPadreDe(id) ?: Transform.IDENTITY
        val giro = Quat.fromAxisAngle(
            enElMarcoDelPadre(padre, eje), grados * PI.toFloat() / 180f
        )
        val matriz = giro.toMatrixRowMajor()
        // El pivote, dicho también en el marco del padre. Sin caja —una pieza que no
        // compila— se gira alrededor del origen local, que es lo único que queda.
        val pivote = documento.cotasEnMundoDe(id)?.center?.let { padre.worldToLocal(it) }

        return aplicar(registrarEnHistorial = false) { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(id) { pieza ->
                    val t = pieza.transform
                    pieza.copy(
                        transform = t.copy(
                            rotation = (giro * t.rotation).normalized(),
                            translation = if (pivote == null) t.translation
                            else pivote + applyMatrix(matriz, t.translation - pivote),
                        ),
                    )
                },
            )
        }
    }

    /**
     * Agranda o encoge la pieza **alrededor del centro de su caja**, multiplicando.
     *
     * Por el mismo motivo que el giro pivota ahí: escalar respecto del origen local mandaría
     * de viaje a cualquier pieza descentrada, y quien arrastra un asa de escala espera que la
     * pieza crezca donde está. Se multiplica y no se fija un valor porque el gesto es
     * relativo: cien fotogramas de un arrastre son cien factores pequeños encadenados.
     *
     * La escala es uniforme en todo el sistema —una escala por ejes deformaría el campo y las
     * distancias dejarían de ser distancias, con lo que el analizador de fabricación mediría
     * mentiras—, así que esto es un solo número y no tres.
     */
    fun escalarEnElMundo(id: String, factor: Float): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        if (!factor.isFinite() || factor <= 0f) return rechazar("El factor de escala debe ser positivo")
        val nueva = (pieza.transform.scale * factor).coerceIn(0.01f, 1000f)
        if (nueva == pieza.transform.scale) return false

        val padre = documento.transformDelPadreDe(id) ?: Transform.IDENTITY
        val pivote = documento.cotasEnMundoDe(id)?.center?.let { padre.worldToLocal(it) }
        // El factor que de verdad se aplica, después de topar: la traslación tiene que
        // moverse con él o la pieza se separaría de su propio centro al llegar al límite.
        val real = nueva / pieza.transform.scale

        return aplicar(registrarEnHistorial = false) { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(id) {
                    val t = it.transform
                    it.copy(
                        transform = t.copy(
                            scale = nueva,
                            translation = if (pivote == null) t.translation
                            else pivote + (t.translation - pivote) * real,
                        ),
                    )
                },
            )
        }
    }

    /** Un vector del mundo, dicho en el marco del padre de una pieza. */
    private fun enElMarcoDelPadre(padre: Transform, v: Vec3): Vec3 =
        applyMatrix(padre.rotation.conjugate().toMatrixRowMajor(), v) / padre.scale

    private fun normalizada(v: Vec3): Vec3? {
        val largo = v.length()
        if (largo < 1e-6f || !largo.isFinite()) return null
        return v / largo
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

    // ------------------------------------------------------------------ colocación

    /**
     * Desplaza una pieza sumando a lo que ya tenía, o fijando la posición si
     * [absoluto]. Sumar es lo natural para un ajuste; fijar, para recolocar.
     */
    fun mover(id: String, x: Float, y: Float, z: Float, absoluto: Boolean = false): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return rechazar("La posición no es válida")
        val destino = if (absoluto) Vec3(x, y, z) else pieza.transform.translation + Vec3(x, y, z)
        return aplicar { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(transform = it.transform.copy(translation = destino)) })
        }
    }

    /** Gira una pieza sobre su propio origen, acumulando o fijando el giro. */
    fun girarPieza(id: String, x: Float, y: Float, z: Float, absoluto: Boolean = false): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return rechazar("El giro no es válido")
        val giro = deEulerGrados(x, y, z)
        val destino = if (absoluto) giro else (giro * pieza.transform.rotation).normalized()
        return aplicar { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(transform = it.transform.copy(rotation = destino)) })
        }
    }

    fun escalarPieza(id: String, factor: Float): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        if (!factor.isFinite() || factor <= 0f) return rechazar("La escala debe ser positiva")
        val destino = (pieza.transform.scale * factor).coerceIn(1e-3f, 1e4f)
        return aplicar { doc ->
            doc.copy(raiz = doc.raiz.mapear(id) { it.copy(transform = it.transform.copy(scale = destino)) })
        }
    }

    /**
     * Escala una pieza —o el modelo entero— hasta que su cota en un eje mida lo pedido.
     *
     * Es la operación que convierte una proporción en una pieza real, y existe por un
     * fallo concreto de los modelos de lenguaje: aciertan las proporciones y fallan
     * los milímetros. Mirando una foto es peor todavía, porque en una imagen **no hay
     * ninguna escala**: el mismo soporte puede ser de móvil o de camión.
     *
     * Pedirle al modelo que adivine cada cota es garantizar que se equivoque en todas.
     * Con esto la persona dice la única medida que sí conoce —«esta pestaña son
     * 40 mm»— y el resto del modelo la sigue en proporción. Se mide en el mundo, así
     * que funciona igual sobre una primitiva suelta que sobre un conjunto entero.
     */
    fun escalarACota(id: String, eje: EjeNombrado, medida: Float): Boolean {
        if (!medida.isFinite() || medida <= 0f) return rechazar("La medida debe ser positiva")
        val cotas = documento.cotasEnMundoDe(id) ?: return rechazar("No se pueden medir las cotas de $id")
        val actual = when (eje) {
            EjeNombrado.X -> cotas.size.x
            EjeNombrado.Y -> cotas.size.y
            EjeNombrado.Z -> cotas.size.z
        }
        // Una pieza plana no se puede acotar por su eje plano: el factor sería
        // infinito y saldría un sólido de tamaño arbitrario sin que nadie lo pidiera.
        if (actual <= 1e-4f) {
            return rechazar("La pieza no mide nada en $eje; elige otro eje para acotarla")
        }
        return escalarPieza(id, medida / actual)
    }

    // ------------------------------------------------------- medidas y encajes

    // Las operaciones de medidas y encajes devuelven «salió bien», no «hay que
    // recompilar», que es lo que devuelve `aplicar`. Cambiar una medida de 20 a 25
    // escala la pieza sin tocar la topología del shader, así que `aplicar` diría false
    // con todo correcto; y soltar un encaje no mueve ni un vértice. La señal buena es
    // `ultimoError`, igual que en la aplicación de planes.

    /** Las medidas del mundo declaradas en este documento, en orden de alta. */
    fun medidas(): List<Medida> = documento.medidas

    /** El primer encaje que gobierna una pieza, o `null` si su cota es libre. */
    fun encajeDe(id: String): Encaje? = documento.buscar(id)?.encajes?.firstOrNull()

    /** Todas las declaraciones que gobiernan las cotas de una pieza. */
    fun encajesDe(id: String): List<Encaje> = documento.buscar(id)?.encajes ?: emptyList()

    /**
     * Si tocar ese parámetro movería la cota que manda el encaje.
     *
     * Una cota gobernada no se edita a mano: dejar pasar el arrastre y recolocar por
     * detrás sería peor que negarse, porque el deslizador enseñaría un número y la pieza
     * acabaría en otro cuando el encaje la vuelva a escalar. Lo que se edita es la medida
     * del mundo; para editar la pieza hay que soltar el encaje.
     *
     * Se resuelve **midiendo**, no con una tabla de qué parámetro toca qué eje: se aplica
     * el cambio a un documento de prueba sin resolver encajes y se mira si la extensión
     * en el eje gobernado se mueve. Así vale igual para un cilindro, una extrusión o un
     * grupo, y no hay nada que actualizar cuando aparezca una primitiva nueva.
     *
     * Es público porque el inspector tiene que saberlo **antes** de dibujar el control:
     * un deslizador que se rechaza al soltarlo es peor que un deslizador que no está.
     */
    fun gobernadaPorEncaje(id: String, clave: String): Boolean {
        val pieza = documento.buscar(id) ?: return false
        if (pieza.encajes.isEmpty()) return false
        val ensayo = documento.copy(
            raiz = documento.raiz.mapear(id) {
                // Un valor de prueba claramente distinto del actual, para que el efecto
                // se vea aunque el parámetro entre con el mismo número que ya tenía.
                it.copy(parametros = it.parametros + (clave to (it.parametro(clave) + 1f)))
            },
        )
        return pieza.encajes.any { encaje ->
            val antes = documento.cotasEnMundoDe(id)?.let { extension(it, encaje.eje) } ?: return@any false
            val despues = ensayo.cotasEnMundoDe(id)?.let { extension(it, encaje.eje) } ?: return@any false
            abs(despues - antes) > 1e-4f
        }
    }

    /** El nombre del perfil de fabricación vigente: el que fija todas las holguras. */
    fun perfilDeTrabajo(): String = perfilDeFabricacion.nombre

    /** De dónde salen sus umbrales: de fábrica, editados, o calibrados en esta máquina. */
    fun origenDelPerfil(): String = perfilDeFabricacion.origen.etiqueta

    /** De qué perfil salió el activo, si salió de otro. Vacío si es uno de fábrica. */
    fun baseDelPerfil(): String = perfilDeFabricacion.derivadoDe ?: ""

    /** La holgura que el perfil vigente aplica a un encaje deslizante, en milímetros. */
    fun holguraDelPerfil(): Float = perfilDeFabricacion.holguraEncaje

    /**
     * Cambia de perfil y vuelve a derivar todas las cotas gobernadas por un encaje.
     *
     * Es la prueba visible de que el encaje está vivo: pasar de una boquilla de 0,4 a
     * una de 0,6 cambia la holgura tabulada, y las piezas que tienen que encajar se
     * mueven solas sin que nadie toque un número.
     */
    fun usarPerfil(nombre: String): Boolean {
        val perfil = PerfilFabricacion.porNombre(nombre)
            ?: return rechazar("No existe el perfil «$nombre»")
        return usarPerfilCalibrado(perfil)
    }

    /**
     * Pone un perfil concreto, venga de la lista de fábrica o de un cupón medido.
     *
     * Es la puerta por la que entra la calibración: [PerfilFabricacion.calibradoCon]
     * produce un perfil que no está en ninguna lista, y todas las piezas que encajan se
     * vuelven a derivar con la holgura que ha dado la máquina real.
     */
    fun usarPerfilCalibrado(perfil: PerfilFabricacion): Boolean {
        if (perfil == perfilDeFabricacion) {
            ultimoError = null
            return false
        }
        val anterior = perfilDeFabricacion
        perfilDeFabricacion = perfil
        // Va al historial porque puede mover cotas, y deshacer tiene que devolver la
        // pieza que había. Si el documento no cambia, `aplicar` devuelve false y el
        // perfil se queda puesto igualmente: el cambio de perfil sí ha ocurrido.
        aplicar { doc -> doc.resolverEncajes(perfil) }
        if (ultimoError != null) {
            perfilDeFabricacion = anterior
            return false
        }
        return true
    }

    /**
     * Corrige una medida y arrastra con ella todas las piezas que encajan contra ella.
     *
     * Se rechaza cuando el valor nuevo dejaría algún encaje sin cumplir —la holgura se
     * comería la cota entera— y el documento se queda exactamente como estaba. Aceptar
     * el número y dejar las piezas atrás sería peor: la medida diría una cosa y la
     * geometría otra, que es justo el estado que este trabajo existe para hacer
     * imposible.
     */
    fun fijarMedida(id: String, valor: Float): Boolean {
        val medida = documento.medidaDe(id) ?: return rechazar("No existe la medida «$id»")
        if (!valor.isFinite() || valor <= 0f) {
            return rechazar("«${medida.nombre}» tiene que ser positiva")
        }
        val perfil = perfilActivo()
        val propuesto = documento.copy(
            medidas = documento.medidas.map { if (it.id == id) it.copy(valor = valor) else it },
        )
        for ((pieza, _) in propuesto.raiz.aplanar()) {
            for (encaje in pieza.encajes) {
                if (encaje.medida != id) continue
                propuesto.motivoParaNoEncajar(encaje, perfil)?.let {
                    return rechazar("«${pieza.nombre}» no podría encajar: $it")
                }
            }
        }
        aplicar { propuesto }
        return ultimoError == null
    }

    /**
     * Quita el encaje y deja la cota donde estaba.
     *
     * Existe porque la cota gobernada no se edita a mano: sin una salida explícita, una
     * pieza que dejó de tener que encajar quedaría atada para siempre. Soltar no mueve
     * nada —la geometría es la que es—, solo deja de derivarla.
     */
    fun soltarEncaje(piezaId: String): Boolean {
        val pieza = documento.buscar(piezaId) ?: return rechazar("No existe la pieza $piezaId")
        if (pieza.encajes.isEmpty()) return rechazar("«${pieza.nombre}» no tiene ningún encaje")
        aplicar { doc -> doc.copy(raiz = doc.raiz.mapear(piezaId) { it.copy(encajes = emptyList()) }) }
        return ultimoError == null
    }

    /**
     * Cómo se lee un encaje en el inspector, con su procedencia.
     *
     * «entra en agujero del tubo: 20 mm con calibre · holgura 0,2 mm deslizante». Es lo
     * que convierte una cota rara en una cota justificada, y lo que hace que abrir el
     * archivo dentro de un mes siga teniendo sentido.
     */
    fun descripcionDeEncaje(piezaId: String): String? {
        val encaje = documento.buscar(piezaId)?.encajes?.firstOrNull() ?: return null
        val medida = documento.medidaDe(encaje.medida) ?: return null
        val perfil = perfilActivo()
        return "${encaje.sentido.etiqueta} ${medida.descripcion()} · holgura " +
            "${redondeado(encaje.holguraCon(perfil))} mm ${encaje.clase.etiqueta} " +
            "(${perfil.origen.etiqueta})"
    }


    /**
     * Da de alta una medida del objeto real. Devuelve su identificador, o `null`.
     *
     * El identificador se deduce del propio documento y no de un contador global: es
     * la misma lección que dejó el contador de piezas, donde abrir un proyecto de otra
     * sesión producía identificadores ya usados y `buscar` acertaba al primero que
     * encontrara sin dar error en ningún sitio.
     */
    fun declararMedida(
        nombre: String,
        valor: Float,
        procedencia: ProcedenciaDeMedida = ProcedenciaDeMedida.A_OJO,
    ): String? {
        if (nombre.isBlank()) {
            rechazar("Una medida sin nombre no se puede volver a encontrar")
            return null
        }
        if (!valor.isFinite() || valor <= 0f) {
            rechazar("«$nombre» vale $valor y una medida del mundo tiene que ser positiva")
            return null
        }
        val id = documento.idDeMedidaLibre()
        val medida = Medida(id, nombre.trim(), valor, procedencia)
        aplicar { doc -> doc.copy(medidas = doc.medidas + medida) }
        return if (ultimoError == null) id else null
    }


    /**
     * Declara que la cota de una pieza la manda un encaje contra una medida del mundo.
     *
     * A partir de aquí la extensión de la pieza en ese eje deja de ser un número que
     * alguien escribió. Se rechaza —dejando el documento intacto— cuando la medida no
     * existe o cuando la holgura se comería la cota entera; es la misma regla que ya
     * cumple cualquier edición del editor.
     */
    fun declararEncaje(
        piezaId: String,
        medidaId: String,
        eje: EjeNombrado = EjeNombrado.X,
        sentido: SentidoDeEncaje = SentidoDeEncaje.ENTRA,
        clase: ClaseDeAjuste = ClaseDeAjuste.DESLIZANTE,
        porParametro: Boolean = false,
    ): Boolean {
        val pieza = documento.buscar(piezaId) ?: return rechazar("No existe la pieza $piezaId")
        val encaje = Encaje(medidaId, eje, sentido, clase, porParametro = porParametro)
        documento.motivoParaNoEncajar(encaje, perfilActivo())?.let {
            return rechazar("«${pieza.nombre}» no puede encajar: $it")
        }
        if (porParametro) {
            if (pieza.parametroQueGobierna(eje) == null) {
                return rechazar(
                    "«${pieza.nombre}» no tiene un parámetro que gobierne $eje; " +
                        "su encaje se derivaría por escala",
                )
            }
            // Mezclar modos en una pieza haría que la escala del encaje viejo pisara al
            // por parámetro, o al revés: o todos por parámetro o ninguno.
            if (pieza.encajes.any { !it.porParametro }) {
                return rechazar(
                    "«${pieza.nombre}» ya deriva sus cotas por escala; suéltalas antes de " +
                        "declarar una por parámetro",
                )
            }
        }
        val cotas = documento.cotasEnMundoDe(piezaId)
            ?: return rechazar("No se pueden medir las cotas de «${pieza.nombre}»")
        val actual = when (eje) {
            EjeNombrado.X -> cotas.size.x
            EjeNombrado.Y -> cotas.size.y
            EjeNombrado.Z -> cotas.size.z
        }
        if (actual <= 1e-4f) {
            return rechazar("«${pieza.nombre}» no mide nada en $eje; elige otro eje")
        }
        aplicar { doc -> doc.copy(raiz = doc.raiz.mapear(piezaId) { it.copy(encajes = it.encajes + encaje) }) }
        return ultimoError == null
    }



    /**
     * Ata una pieza a una medida del mundo para que encaje, y la deja atada.
     *
     * `acotar` da la cota nominal; esto declara la relación de la que **se deriva** la
     * cota real. La cuenta es la misma que ya hace [Roscas] para el agujero de paso
     * —dos holguras, una por cada lado— y el número sale del perfil de fabricación, que
     * es donde está tabulado y no en la cabeza del modelo.
     *
     * La medida se da de alta en el documento y el encaje se ata a ella en el **mismo**
     * cambio, así que deshacer devuelve las dos cosas y no queda nunca una medida
     * huérfana ni un encaje apuntando a algo que no existe.
     *
     * Se rechaza cuando la holgura se comería la medida entera: el factor saldría
     * negativo y la pieza saldría del revés sin que nadie se entere, que es peor que no
     * hacer nada.
     */
    fun holgar(
        id: String,
        eje: EjeNombrado,
        medida: Float,
        sentido: SentidoDeEncaje,
        clase: ClaseDeAjuste = ClaseDeAjuste.DESLIZANTE,
        nombreDeLaMedida: String? = null,
    ): Boolean {
        val pieza = documento.buscar(id) ?: return rechazar("No existe la pieza $id")
        if (!medida.isFinite() || medida <= 0f) return rechazar("La medida debe ser positiva")

        val idMedida = documento.idDeMedidaLibre()
        val nombre = nombreDeLaMedida?.trim()?.takeIf { it.isNotEmpty() }
            ?: "lo que encaja con «${pieza.nombre}»"
        // El modelo no mide nada: le han dicho el número. Decir «a ojo» y dejar que la
        // persona lo suba a «con calibre» es más honesto que estrenar la medida con una
        // procedencia que nadie ha comprobado.
        val nueva = Medida(idMedida, nombre, medida, ProcedenciaDeMedida.A_OJO)
        val encaje = Encaje(idMedida, eje, sentido, clase)
        if (pieza.encajes.any { it.porParametro }) {
            return rechazar(
                "«${pieza.nombre}» deriva sus cotas por parámetro; decláralo con " +
                    "declararEncaje en vez de escalarla",
            )
        }

        val propuesto = documento.copy(medidas = documento.medidas + nueva)
        propuesto.motivoParaNoEncajar(encaje, perfilActivo())?.let {
            return rechazar("«${pieza.nombre}» no puede encajar: $it")
        }
        val cotas = documento.cotasEnMundoDe(id)
            ?: return rechazar("No se pueden medir las cotas de «${pieza.nombre}»")
        val actual = when (eje) {
            EjeNombrado.X -> cotas.size.x
            EjeNombrado.Y -> cotas.size.y
            EjeNombrado.Z -> cotas.size.z
        }
        if (actual <= 1e-4f) {
            return rechazar("«${pieza.nombre}» no mide nada en $eje; elige otro eje para encajarla")
        }

        aplicar { doc ->
            doc.copy(
                medidas = doc.medidas + nueva,
                raiz = doc.raiz.mapear(id) { it.copy(encajes = it.encajes + encaje) },
            )
        }
        return ultimoError == null
    }

    /**
     * Apoya una pieza contra una cara de otra, con holgura opcional.
     *
     * Resuelve las cotas reales de ambas en el mundo, así que funciona con piezas
     * giradas, anidadas o escaladas. Es la operación que evita que quien monta el
     * modelo —persona o modelo de lenguaje— tenga que hacer aritmética de semilados,
     * que es exactamente donde se cometen los errores.
     */
    /**
     * Abre un agujero para tornillo que **atraviesa** la pieza de lado a lado.
     *
     * Concentra tres cosas que, por separado, un modelo de lenguaje falla casi
     * siempre. El diámetro sale de tabla y no de memoria. El largo se calcula desde
     * las cotas reales de la pieza más un margen, así que la resta no puede quedarse
     * corta —el fallo silencioso por excelencia: el plan es válido, la operación se
     * aplica y no hay agujero—. Y la posición se da respecto al centro de la pieza,
     * no en coordenadas del mundo.
     *
     * El cilindro entra como hermano de la pieza dentro de una `DIFERENCIA` nueva,
     * de modo que ambos comparten el mismo espacio y las cotas locales del objetivo
     * son directamente las coordenadas en las que hay que situarlo.
     */
    fun taladrar(
        objetivoId: String,
        designacion: String? = null,
        diametro: Float = 0f,
        ajusteNombre: String = AjusteDeTaladro.PASANTE.name,
        ejeNombre: String = "Y",
        desplazamientoA: Float = 0f,
        desplazamientoB: Float = 0f,
        /** Si `true`, (A, B) es un punto del contorno y no un desplazamiento. */
        enCoordenadasDePerfil: Boolean = false,
        nombrePerfil: String? = null,
    ): Boolean {
        avisoDeTaladro = null
        if (objetivoId == documento.raiz.id) return rechazar("La raíz no se puede taladrar")
        val pieza = documento.buscar(objetivoId) ?: return rechazar("No existe la pieza $objetivoId")
        val cotas = pieza.compilar()?.cotas()
            ?: return rechazar("«${pieza.nombre}» no tiene material que taladrar")

        val ajuste = AjusteDeTaladro.entries.firstOrNull { it.name == ajusteNombre.uppercase() }
            ?: return rechazar("Ajuste de taladro desconocido: $ajusteNombre")
        val eje = Axis.entries.firstOrNull { it.name == ejeNombre.uppercase() }
            ?: return rechazar("Eje desconocido: $ejeNombre")

        val perfil = PerfilFabricacion.porNombre(nombrePerfil ?: "") ?: PerfilFabricacion.PREDETERMINADO
        val calibre = when {
            designacion != null -> {
                val rosca = Roscas.porDesignacion(designacion)
                    ?: return rechazar(
                        "Rosca desconocida: $designacion. Las que hay son ${Roscas.designaciones}",
                    )
                Roscas.diametroPara(rosca, ajuste, perfil)
            }

            diametro > 0f -> diametro
            else -> return rechazar("Hace falta una designación métrica o un diámetro")
        }
        if (!calibre.isFinite() || calibre <= 0f) return rechazar("El diámetro del taladro no es válido")
        if (calibre < perfil.detalleMinimo) {
            return rechazar(
                "Un agujero de ${redondeado(calibre)} mm no llega a existir con una boquilla de " +
                    "${redondeado(perfil.boquilla)} mm",
            )
        }

        // Dos milímetros de sobrante por cada extremo: el corte tiene que salir por
        // fuera de la cara, porque una resta que acaba justo en la superficie deja
        // una película de material que el laminador convierte en un agujero ciego.
        val largo = when (eje) {
            Axis.X -> cotas.size.x
            Axis.Y -> cotas.size.y
            Axis.Z -> cotas.size.z
        } + 4f

        // Un punto del contorno se pasa a desplazamiento aquí, que es el único sitio
        // donde se conoce el contorno. Se hace antes de calcular la posición porque a
        // partir de ahí ya es un desplazamiento normal y corriente.
        var a = desplazamientoA
        var b = desplazamientoB
        if (enCoordenadasDePerfil) {
            if (eje != Axis.Y) {
                return rechazar(
                    "Un punto del contorno solo tiene sentido taladrando en Y, que es " +
                        "la dirección en la que se levanta la extrusión",
                )
            }
            val contorno = perfilDelSubarbol(pieza)
            if (contorno != null) {
                val (lo, hi) = contorno.cotas()
                a = desplazamientoA - (lo.x + hi.x) * 0.5f
                b = desplazamientoB - (lo.y + hi.y) * 0.5f
            } else {
                // Sin contorno, el punto no se tira: se traduce. Rechazar la operación
                // se llevaba por delante el resto del plan —nueve operaciones buenas
                // por una coordenada mal encuadrada—, y aquí hay información de sobra
                // para deducir qué quiso decir.
                //
                // Cuál de las dos lecturas era no se supone, **se mide**: primero desde
                // la esquina, que es como se escriben las coordenadas de un contorno, y
                // solo si el agujero cae fuera de la pieza se prueba desde el centro.
                val semiA = cotas.size.x * 0.5f
                val semiB = cotas.size.z * 0.5f
                val desdeEsquina = (desplazamientoA - semiA) to (desplazamientoB - semiB)
                val desdeCentro = desplazamientoA to desplazamientoB
                fun dentro(p: Pair<Float, Float>) =
                    abs(p.first) <= semiA + 1e-3f && abs(p.second) <= semiB + 1e-3f
                val origen = when {
                    dentro(desdeEsquina) -> "la esquina"
                    dentro(desdeCentro) -> "el centro"
                    else -> return rechazar(
                        "«${pieza.nombre}» no tiene contorno y el punto " +
                            "(${redondeado(desplazamientoA)}, ${redondeado(desplazamientoB)}) no cae " +
                            "dentro de la pieza ni contado desde su esquina ni desde su centro; " +
                            "usa «desplazamiento»",
                    )
                }
                val elegido = if (origen == "la esquina") desdeEsquina else desdeCentro
                a = elegido.first
                b = elegido.second
                avisoDeTaladro = "«${pieza.nombre}» no tiene contorno: el punto se ha contado desde " +
                    "$origen de la pieza. Si el agujero no está donde querías, dilo con «desplazamiento»"
            }
        }

        val centro = cotas.center
        val posicion = when (eje) {
            Axis.X -> Vec3(centro.x, centro.y + a, centro.z + b)
            Axis.Y -> Vec3(centro.x + a, centro.y, centro.z + b)
            Axis.Z -> Vec3(centro.x + a, centro.y + b, centro.z)
        }
        // El cilindro nace a lo largo de Y; para los otros ejes se tumba 90°.
        val giro = when (eje) {
            Axis.X -> Vec3(0f, 0f, 90f)
            Axis.Y -> Vec3.ZERO
            Axis.Z -> Vec3(90f, 0f, 0f)
        }

        val abierta = enTransaccion
        if (!abierta) abrirTransaccion()

        // Taladrar algo que ya es una diferencia añade la broca a la que hay, en vez
        // de envolverla en otra. El sólido es el mismo —restar A y luego B es restar
        // los dos— pero el árbol crece a lo ancho y no a lo hondo. Anidando, una
        // pieza con seis agujeros salía con seis niveles de diferencia, ilegible para
        // quien la edita a mano y cara en tokens para el modelo, que la recibe entera
        // en cada vuelta de corrección.
        val diferencia: String
        if (pieza.tipo == TipoPieza.DIFERENCIA && pieza.hijos.isNotEmpty()) {
            diferencia = objetivoId
        } else {
            // Se mira `ultimoError` y no lo que devuelven estas llamadas: su booleano
            // significa «hay que recompilar el shader», no «salió bien». Una
            // DIFERENCIA recién creada tiene un solo hijo y compila exactamente igual
            // que él, así que envolver **acierta** y devuelve `false`. Leerlo como
            // fallo abortaba el taladro dejando la diferencia vacía en el árbol.
            envolver(objetivoId, TipoPieza.DIFERENCIA.name)
            ultimoError?.let { return rechazarTaladro(abierta, it) }
            diferencia = seleccionado
                ?: return rechazarTaladro(abierta, "no se pudo crear la diferencia")
            renombrar(diferencia, "${pieza.nombre} taladrada")
        }

        anadir(TipoPieza.CILINDRO.name, diferencia)
        ultimoError?.let { return rechazarTaladro(abierta, it) }
        val broca = seleccionado ?: return rechazarTaladro(abierta, "el taladro no se pudo identificar")

        renombrar(broca, designacion?.let { "Taladro $it" } ?: "Taladro ⌀${redondeado(calibre)}")
        fijarParametro(broca, "radio", calibre * 0.5f)
        fijarParametro(broca, "altura", largo)
        if (giro != Vec3.ZERO) girarPieza(broca, giro.x, giro.y, giro.z, absoluto = true)
        mover(broca, posicion.x, posicion.y, posicion.z, absoluto = true)

        // Queda seleccionada la pieza taladrada, no la broca. Dejar seleccionada la
        // broca hacía que un segundo taladro sobre «seleccion» perforase el agujero
        // anterior en vez de la pieza, y el resultado era una diferencia anidada que
        // no quitaba nada. El alias, en cambio, sí nombra la broca: es lo que permite
        // retocar el agujero después.
        ultimoTaladro = broca
        seleccionar(diferencia)

        if (!abierta) cerrarTransaccion()
        ultimoError = null
        return true
    }

    /** La broca del último [taladrar], para que quien lo pidió pueda darle un alias. */
    var ultimoTaladro: String? = null
        private set

    /**
     * Añade un nervio triangular en el encuentro de dos piezas.
     *
     * Es la operación que evita que una escuadra impresa se parta por la esquina, y la
     * que un modelo de lenguaje no sabe construir: exige un triángulo rectángulo en el
     * plano que forman las dos piezas, con los catetos siguiendo a cada una y la
     * hipotenusa hacia fuera. Son tres decisiones —el plano, el giro y el sitio— y
     * falla en las tres. Puesto a intentarlo contra el banco, el modelo local devolvió
     * una escuadra en L pelada llamándola «nervio integrado».
     *
     * Aquí las tres las toma el núcleo mirando dónde están las dos piezas:
     *
     * - **El plano** es el que forman los dos ejes en los que las piezas se separan; el
     *   espesor va por el eje que comparten, que es el que ambas ocupan a la vez.
     * - **La esquina** es el centro de la zona donde sus envolventes se solapan.
     * - **La dirección de cada cateto** apunta hacia el cuerpo de cada pieza, medido
     *   desde esa esquina.
     */
    fun ponerNervio(
        objetivoId: String,
        contraId: String,
        tamano: Float = 0f,
        grosor: Float = 0f,
        nombrePerfil: String? = null,
    ): Boolean {
        if (objetivoId == contraId) return rechazar("Un nervio necesita dos piezas distintas")
        val a = documento.cotasEnMundoDe(objetivoId)
            ?: return rechazar("No se pueden medir las cotas de $objetivoId")
        val b = documento.cotasEnMundoDe(contraId)
            ?: return rechazar("No se pueden medir las cotas de $contraId")

        val comun = a.intersect(b)
        // La separación se mide eje a eje y **no** preguntándole a `intersect` si el
        // resultado es válido: cuando dos cajas no se cruzan, `intersect` devuelve un
        // punto degenerado en vez de una caja vacía —está escrito así a propósito, para
        // que nada se invierta—, y con eso la comprobación de solape daba siempre que
        // sí. El nervio se creaba entre dos piezas a 200 mm una de otra.
        val hueco = maxOf(
            maxOf(a.min.x - b.max.x, b.min.x - a.max.x),
            maxOf(a.min.y - b.max.y, b.min.y - a.max.y),
            maxOf(a.min.z - b.max.z, b.min.z - a.max.z),
        )
        if (hueco > 0f) {
            return rechazar(
                "«${nombreDe(objetivoId)}» y «${nombreDe(contraId)}» no se tocan: " +
                    "júntalas antes de reforzar la esquina",
            )
        }

        // El espesor va por el eje que las dos piezas comparten más, que en una escuadra
        // es la profundidad de la chapa. Los otros dos forman el plano del triángulo.
        val solape = comun.size
        val ejeDelEspesor = when {
            solape.x >= solape.y && solape.x >= solape.z -> 0
            solape.y >= solape.z -> 1
            else -> 2
        }
        val plano = (0..2).filter { it != ejeDelEspesor }

        val perfil = PerfilFabricacion.porNombre(nombrePerfil ?: "") ?: PerfilFabricacion.PREDETERMINADO
        // Sin grosor pedido, el doble del mínimo: el mínimo *rellena* la pared pero un
        // nervio existe justamente para aguantar esfuerzo, y a una capa no aguanta nada.
        val espesor = if (grosor > 0f) grosor else perfil.grosorMinimoPared * 2f

        val esquina = comun.center
        val centroA = a.center
        val centroB = b.center

        // Cuánto se aparta cada pieza de la esquina en cada eje del plano. El cateto
        // sigue a la que más se aparta: es la que el nervio tiene que acompañar.
        fun brazo(eje: Int): Pair<Float, Float> {
            val da = componente(centroA, eje) - componente(esquina, eje)
            val db = componente(centroB, eje) - componente(esquina, eje)
            val mayor = if (abs(da) >= abs(db)) da else db
            val alcance = maxOf(abs(da) * 2f, abs(db) * 2f)
            return (if (mayor >= 0f) 1f else -1f) to alcance
        }

        val (dirU, alcanceU) = brazo(plano[0])
        val (dirV, alcanceV) = brazo(plano[1])
        // Sin tamaño pedido, un tercio del brazo más corto: un nervio que llega hasta el
        // final deja de ser refuerzo y se convierte en una pared, y encima tapa lo que
        // la escuadra tenía que dejar libre.
        val lado = if (tamano > 0f) tamano else maxOf(minOf(alcanceU, alcanceV) / 3f, espesor * 2f)
        if (lado < perfil.detalleMinimo) {
            return rechazar("Un nervio de ${redondeado(lado)} mm no llega a existir con esa boquilla")
        }

        val abierta = enTransaccion
        if (!abierta) abrirTransaccion()

        // El nervio cuelga del padre común, no de una de las dos piezas: es material
        // nuevo que se une al conjunto, y meterlo dentro de una de ellas lo dejaría
        // fuera si esa pieza acaba dentro de una DIFERENCIA.
        val padre = documento.padreDe(objetivoId) ?: documento.raiz.id
        anadir(TipoPieza.EXTRUSION.name, padre)
        ultimoError?.let { return rechazarNervio(abierta, it) }
        val nervio = seleccionado ?: return rechazarNervio(abierta, "el nervio no se pudo identificar")

        renombrar(nervio, "Nervio")
        fijarParametro(nervio, "altura", espesor)

        // El contorno vive en el plano del perfil; el mapeo a mundo lo fija el giro que
        // se aplica debajo. `signoDelPerfil` dice, para cada eje del plano, con qué signo
        // llega la coordenada del contorno al mundo.
        val (ejeU, signoU) = mapaDelPerfil(ejeDelEspesor, plano[0])
        val (ejeV, signoV) = mapaDelPerfil(ejeDelEspesor, plano[1])
        val pu = dirU * lado * signoU
        val pv = dirV * lado * signoV
        fijarPuntosDelPerfil(nervio, listOf(0f, 0f, pu, 0f, 0f, pv))
        ultimoError?.let { return rechazarNervio(abierta, it) }

        val giro = when (ejeDelEspesor) {
            0 -> Vec3(0f, 0f, -90f)
            1 -> Vec3.ZERO
            else -> Vec3(90f, 0f, 0f)
        }
        if (giro != Vec3.ZERO) girarPieza(nervio, giro.x, giro.y, giro.z, absoluto = true)

        // El vértice del ángulo recto es el punto (0, 0) del contorno, y el contorno se
        // usa con sus coordenadas **crudas**: el origen de la pieza cae justo ahí. Así
        // que la pieza va a la esquina y ya.
        //
        // Se comprobó midiendo y no leyendo: la primera versión compensaba medio
        // triángulo suponiendo que el contorno se centraba en su caja —que es lo que
        // hace `taladro` con su `desplazamiento`, y por eso parecía razonable— y el
        // nervio salía desplazado 10 mm en dos ejes a la vez, sin tocar ninguna de las
        // dos piezas. Compilaba, se creaba la pieza y el revisor la veía como un sólido
        // suelto.
        mover(nervio, esquina.x, esquina.y, esquina.z, absoluto = true)

        ultimoNervio = nervio
        seleccionar(nervio)
        if (!abierta) cerrarTransaccion()
        ultimoError = null
        return true
    }

    /** El nervio del último [ponerNervio], para poder darle un alias. */
    var ultimoNervio: String? = null
        private set

    /**
     * A qué eje del mundo y con qué signo llega cada coordenada del contorno.
     *
     * Un `EXTRUSION` nace con el contorno en XZ y se levanta por Y. Al tumbarlo para
     * que el espesor vaya por otro eje, las dos coordenadas del contorno acaban en
     * otros ejes del mundo y una de ellas invertida. Escribirlo aquí, una vez, evita
     * el error de signo que en una pieza simétrica no se nota y en un triángulo pone
     * el nervio en la esquina de enfrente.
     */
    private fun mapaDelPerfil(ejeDelEspesor: Int, ejeDelPlano: Int): Pair<Int, Float> =
        when (ejeDelEspesor) {
            // Sin giro: el contorno ya está en XZ.
            1 -> ejeDelPlano to 1f
            // Giro de −90° en Z: Y→X, X→−Y, Z→Z.
            0 -> if (ejeDelPlano == 1) 1 to -1f else 2 to 1f
            // Giro de +90° en X: Y→Z, Z→−Y, X→X.
            else -> if (ejeDelPlano == 0) 0 to 1f else 1 to -1f
        }

    private fun componente(v: Vec3, eje: Int) = when (eje) {
        0 -> v.x
        1 -> v.y
        else -> v.z
    }

    private fun rechazarNervio(estabaAbierta: Boolean, motivo: String): Boolean {
        if (!estabaAbierta) revertirTransaccion(documento)
        return rechazar(motivo)
    }

    /**
     * Ahueca una pieza dejando una pared imprimible, **sin cambiar sus cotas**.
     *
     * El `VACIADO` del kernel es un cascarón centrado en la superficie: reparte el
     * grosor a los dos lados, así que la pieza engorda medio grosor por cada cara.
     * Para el kernel eso está bien —es la definición limpia— pero convierte «ahueca
     * esta caja de 40» en una caja de 41,6 que ya no encaja donde tenía que encajar,
     * y ni el modelo ni el usuario se enteran hasta que la miden.
     *
     * Aquí se compensa encogiendo el primitivo un grosor entero antes de ahuecarlo,
     * que devuelve exactamente las cotas de partida. Solo se puede hacer con
     * primitivas, porque hay que saber qué parámetro es cada dimensión; sobre un
     * grupo se ahueca sin compensar y se avisa, que es preferible a negarse.
     */
    fun ahuecar(
        objetivoId: String,
        grosor: Float = 0f,
        nombrePerfil: String? = null,
    ): Boolean {
        if (objetivoId == documento.raiz.id) return rechazar("La raíz no se puede ahuecar")
        val pieza = documento.buscar(objetivoId) ?: return rechazar("No existe la pieza $objetivoId")
        val perfil = PerfilFabricacion.porNombre(nombrePerfil ?: "") ?: PerfilFabricacion.PREDETERMINADO

        // El mínimo del perfil es lo que la boquilla llega a rellenar; el doble es lo
        // que aguanta que le atornillen algo. Por eso el valor por omisión no es el
        // mínimo: una pared en el límite pasa el analizador y se rompe en la mano.
        val calibre = if (grosor > 0f) grosor else perfil.grosorMinimoPared * 2f
        if (!calibre.isFinite() || calibre <= 0f) return rechazar("El grosor de pared no es válido")
        if (calibre < perfil.grosorMinimoPared) {
            return rechazar(
                "Una pared de ${redondeado(calibre)} mm no llega a rellenarse con esta boquilla; " +
                    "el mínimo del perfil «${perfil.nombre}» es ${redondeado(perfil.grosorMinimoPared)} mm",
            )
        }

        val abierta = enTransaccion
        if (!abierta) abrirTransaccion()

        // Encoger va antes de envolver: después, el objetivo cuelga del vaciado y
        // tocarlo obligaría a resolver de nuevo dónde acabó.
        val compensado = encogerParaPared(pieza, calibre)

        envolver(objetivoId, TipoPieza.VACIADO.name)
        ultimoError?.let { return rechazarTaladro(abierta, it) }
        val vaciado = seleccionado ?: return rechazarTaladro(abierta, "no se pudo crear el vaciado")

        renombrar(vaciado, "${pieza.nombre} hueca")
        fijarParametro(vaciado, "grosor", calibre)

        if (!abierta) cerrarTransaccion()
        ultimoError = if (compensado) {
            null
        } else {
            // No es un rechazo: la pieza está ahuecada y es correcta. Pero el aviso
            // sube por las omisiones para que el modelo sepa que las cotas exteriores
            // se movieron y pueda recolocar lo que dependiera de ellas.
            null.also {
                avisoDeAhuecado = "«${pieza.nombre}» no es una primitiva, así que ahuecarla " +
                    "la ha engordado ${redondeado(calibre * 0.5f)} mm por cada cara"
            }
        }
        return true
    }

    /** Aviso del último [ahuecar] que no pudo conservar las cotas, o `null`. */
    var avisoDeAhuecado: String? = null

    /** Aviso del último [taladrar] que tuvo que deducir el origen del punto, o `null`. */
    var avisoDeTaladro: String? = null
        private set

    /**
     * Encoge las cotas de una primitiva un grosor entero. Devuelve si pudo.
     *
     * Las claves están escritas a mano porque no hay forma de deducir del catálogo
     * cuáles de los parámetros son una dimensión completa —`anchura`— y cuáles un
     * radio, que cuenta la mitad. Equivocarse ahí da una pared del doble o de la
     * mitad de lo pedido.
     */
    private fun encogerParaPared(pieza: Pieza, grosor: Float): Boolean {
        val enteras = when (pieza.tipo) {
            TipoPieza.CAJA -> listOf("anchura", "altura", "profundidad")
            TipoPieza.CILINDRO -> listOf("altura")
            TipoPieza.CONO -> listOf("altura")
            TipoPieza.CAPSULA -> listOf("altura")
            else -> emptyList()
        }
        val radios = when (pieza.tipo) {
            TipoPieza.ESFERA -> listOf("radio")
            TipoPieza.CILINDRO -> listOf("radio")
            TipoPieza.CONO -> listOf("radioInferior", "radioSuperior")
            TipoPieza.CAPSULA -> listOf("radio")
            else -> emptyList()
        }
        if (enteras.isEmpty() && radios.isEmpty()) return false

        val claves = pieza.tipo.parametros.map { it.clave }.toSet()
        for (clave in enteras) {
            if (clave !in claves) continue
            val destino = pieza.parametro(clave) - grosor
            if (destino <= 0f) return false
            fijarParametro(pieza.id, clave, destino)
        }
        for (clave in radios) {
            if (clave !in claves) continue
            val actual = pieza.parametro(clave)
            // Un radio de 0 es legítimo en un cono, y encogerlo no tiene sentido.
            if (actual <= 0f) continue
            val destino = actual - grosor * 0.5f
            if (destino <= 0f) return false
            fijarParametro(pieza.id, clave, destino)
        }
        return true
    }

    private fun rechazarTaladro(estabaAbierta: Boolean, motivo: String): Boolean {
        if (!estabaAbierta) cerrarTransaccion()
        return rechazar(motivo)
    }

    private fun redondeado(v: Float): String {
        val r = kotlin.math.round(v * 100f) / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }

    fun colocar(
        id: String,
        referenciaId: String,
        caraNombre: String,
        holgura: Float = 0f,
        centrar: Boolean = true,
    ): Boolean {
        if (id == referenciaId) return rechazar("Una pieza no puede colocarse respecto a sí misma")
        if (!holgura.isFinite()) return rechazar("La holgura no es un número válido")
        val cara = Cara.entries.firstOrNull { it.name == caraNombre.uppercase() }
            ?: return rechazar("Cara desconocida: $caraNombre")
        // Mover un ancestro arrastraría la referencia con él y el resultado no
        // convergería nunca; es mejor decirlo que dejar la pieza en un sitio raro.
        if (esAncestro(id, referenciaId)) {
            return rechazar("«$id» contiene a «$referenciaId»: colocarla movería también la referencia")
        }

        val objetivo = documento.cotasEnMundoDe(id)
            ?: return rechazar("No se pueden medir las cotas de $id")
        val referencia = documento.cotasEnMundoDe(referenciaId)
            ?: return rechazar("No se pueden medir las cotas de $referenciaId")

        var dx = 0f
        var dy = 0f
        var dz = 0f
        when (cara) {
            Cara.ARRIBA -> dy = referencia.max.y + holgura - objetivo.min.y
            Cara.ABAJO -> dy = referencia.min.y - holgura - objetivo.max.y
            Cara.DERECHA -> dx = referencia.max.x + holgura - objetivo.min.x
            Cara.IZQUIERDA -> dx = referencia.min.x - holgura - objetivo.max.x
            Cara.DELANTE -> dz = referencia.max.z + holgura - objetivo.min.z
            Cara.DETRAS -> dz = referencia.min.z - holgura - objetivo.max.z
        }
        if (centrar) {
            // Centrar solo en los ejes que la cara no fija: el eje de apoyo ya está
            // resuelto y volver a tocarlo desharía la colocación.
            if (dx == 0f) dx = referencia.center.x - objetivo.center.x
            if (dy == 0f) dy = referencia.center.y - objetivo.center.y
            if (dz == 0f) dz = referencia.center.z - objetivo.center.z
        }
        return desplazarEnElMundo(id, Vec3(dx, dy, dz))
    }

    /** Alinea una pieza con otra en un eje, por centro o por una de sus caras. */
    fun alinear(id: String, referenciaId: String, ejeNombre: String, modo: String): Boolean {
        if (id == referenciaId) return rechazar("Una pieza no puede alinearse consigo misma")
        val eje = Axis.entries.firstOrNull { it.name == ejeNombre.uppercase() }
            ?: return rechazar("Eje desconocido: $ejeNombre")
        if (esAncestro(id, referenciaId)) {
            return rechazar("«$id» contiene a «$referenciaId»: alinearla movería también la referencia")
        }
        val objetivo = documento.cotasEnMundoDe(id) ?: return rechazar("No se pueden medir las cotas de $id")
        val referencia = documento.cotasEnMundoDe(referenciaId)
            ?: return rechazar("No se pueden medir las cotas de $referenciaId")

        fun componente(c: Vec3) = when (eje) {
            Axis.X -> c.x
            Axis.Y -> c.y
            Axis.Z -> c.z
        }

        val delta = when (modo.uppercase()) {
            "MINIMO" -> componente(referencia.min) - componente(objetivo.min)
            "MAXIMO" -> componente(referencia.max) - componente(objetivo.max)
            else -> componente(referencia.center) - componente(objetivo.center)
        }
        val vector = when (eje) {
            Axis.X -> Vec3(delta, 0f, 0f)
            Axis.Y -> Vec3(0f, delta, 0f)
            Axis.Z -> Vec3(0f, 0f, delta)
        }
        return desplazarEnElMundo(id, vector)
    }

    /**
     * Suma un desplazamiento expresado en el mundo a la posición local de la pieza.
     *
     * La traslación de una pieza vive en el espacio de su padre, así que un vector
     * del mundo hay que llevarlo allí. Se hace transformando dos puntos y restando
     * en lugar de invertir la matriz: así la rotación y la escala del padre entran
     * solas y no hay álgebra que equivocar.
     */
    private fun desplazarEnElMundo(id: String, delta: Vec3): Boolean {
        if (delta.length() < 1e-6f) return false
        val padre = documento.transformDelPadreDe(id)
            ?: return rechazar("No se encuentra el padre de $id")
        val enLocal = padre.worldToLocal(delta) - padre.worldToLocal(Vec3.ZERO)
        return aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.mapear(id) {
                    it.copy(transform = it.transform.copy(translation = it.transform.translation + enLocal))
                },
            )
        }
    }

    private fun esAncestro(posibleAncestro: String, id: String): Boolean {
        if (posibleAncestro == id) return true
        val pieza = documento.buscar(posibleAncestro) ?: return false
        return pieza.aplanar().any { (p, _) -> p.id == id }
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

    // ---------------------------------------------------- historial navegable

    /**
     * Los planes de la IA que se aplicaron, en orden.
     *
     * Es la mitad valiosa del historial de un CAD: no un hilo de deshacer sino las
     * operaciones de dominio que construyeron la pieza, listas para re-ejecutarse.
     * Las primitivas sueltas siguen siendo estado y no historia; mezclarlas es un
     * rediseño y no lo pide nadie.
     */
    val planesAplicados: List<PlanDeModelado> get() = documento.planesAplicados

    /** Añade un plan aplicado a la historia del documento. Lo llama el `Aplicador`. */
    fun registrarPlanAplicado(plan: PlanDeModelado) {
        documento = documento.copy(planesAplicados = documento.planesAplicados + plan)
        regenerar()
    }

    /**
     * Reproduce los planes aplicados sobre un documento vacío y devuelve el resultado.
     *
     * Ejecuta la misma maquinaria que aplicó los planes la primera vez (`Aplicador`),
     * así que si la reproducción diverge del documento actual es un fallo del núcleo
     * y no de la maquinaria. Devuelve `null` si algún plan ya no se puede re-ejecutar
     * —p. ej. porque el catálogo cambió— y explica el motivo.
     */
    fun reproducirPlanes(): Documento? {
        val repuesto = Editor(Documento.vacio())
        for (plan in documento.planesAplicados) {
            val resultado = Aplicador(repuesto, perfilActivo()).aplicar(plan)
            if (!resultado.exito) {
                ultimoError = "El historial no se puede reproducir: ${resultado.error ?: "plan fallido"}"
                return null
            }
        }
        return repuesto.documentoActual
    }

    /**
     * Reemplaza el plan [indice] del historial por [nuevo] y rehace todo lo demás.
     *
     * Es «los agujeros eran M3, ponlos M4» sin deshacer treinta pasos: se reconstruye
     * el documento desde cero ejecutando la historia con el plan nuevo en su sitio, y
     * si el resultado es válido se aplica como una sola edición —un solo ⌘Z—. Si el
     * plan nuevo ya no reproduce (el catálogo cambió, la operación ya no existe), el
     * documento queda intacto y se explica por qué.
     */
    fun reemplazarPlanEnHistoria(indice: Int, nuevo: PlanDeModelado): Boolean {
        val planes = documento.planesAplicados
        if (indice !in planes.indices) return rechazar("No existe el paso $indice del historial")

        val repuesto = Editor(Documento.vacio())
        for ((i, plan) in planes.withIndex()) {
            val cual = if (i == indice) nuevo else plan
            val resultado = Aplicador(repuesto, perfilActivo()).aplicar(cual)
            if (!resultado.exito) {
                return rechazar("El paso ${i + 1} no se puede reproducir: ${resultado.error ?: "plan fallido"}")
            }
        }

        val reconstruido = repuesto.documentoActual.copy(
            planesAplicados = planes.mapIndexed { i, plan -> if (i == indice) nuevo else plan },
        )
        aplicar { reconstruido }
        // El booleano de `aplicar` significa «hay que recompilar», no «salió bien»:
        // cambiar M3 por M4 no cambia la topología del shader. La señal buena es la
        // de siempre, `ultimoError`.
        return ultimoError == null
    }

    /**
     * El perfil de fabricación con el que se está trabajando.
     *
     * Dejó de ser una constante cuando los encajes empezaron a derivar sus cotas de él:
     * si el perfil no fuera estado, cambiar de boquilla no podría mover una pieza, y esa
     * es justamente la demostración de que la relación está viva.
     */
    private fun perfilActivo(): PerfilFabricacion = perfilDeFabricacion

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
    fun exportarStl(ruta: String, resolucion: Float, alAvanzar: ((Float) -> Unit)? = null): Certificado? =
        exportar(ruta, resolucion, alAvanzar) { exportador, paso, suelo ->
            exportador.exportarStl(ruta, paso, suelo)
        }

    /**
     * Exporta a 3MF, con el mismo examen y el mismo certificado.
     *
     * Es el formato que hay que ofrecer primero: un STL no declara unidades, y esa
     * omisión es la causa del clásico modelo que entra en el laminador a 1/25 de su
     * tamaño. El 3MF además llega ya apoyado en el plato y con la Z arriba, así que
     * la orientación que se eligió en Yunkil es la que se imprime.
     */
    fun exportarTresMf(ruta: String, resolucion: Float, alAvanzar: ((Float) -> Unit)? = null): Certificado? =
        exportar(ruta, resolucion, alAvanzar) { exportador, paso, suelo ->
            exportador.exportarTresMf(ruta, paso, suelo, documento.raiz.nombre)
        }

    /**
     * Exporta por la extensión del archivo que eligió el usuario.
     *
     * El formato lo decide el nombre que se escribe en el diálogo de guardar, que es
     * donde ya lo está decidiendo de hecho: obligar además a elegirlo en un menú
     * aparte es una pregunta que se puede contestar sola.
     */
    fun exportarPieza(ruta: String, resolucion: Float, alAvanzar: ((Float) -> Unit)? = null): Certificado? =
        if (ruta.lowercase().endsWith(".3mf")) exportarTresMf(ruta, resolucion, alAvanzar)
        else exportarStl(ruta, resolucion, alAvanzar)

    /**
     * Exporta una **placa** con varios cuerpos como 3MF multiobjeto: una pieza y su
     * cupón, las variantes de un encaje, los cuerpos de un ensamblaje.
     *
     * Cada cuerpo se malla y pasa **su propio** certificado; si uno no es apto no se
     * escribe nada y se nombra la pieza. Es la misma ley del archivo único —no se
     * escribe lo que no pasa el examen— aplicada a la placa entera: entregar un
     * paquete donde una de las piezas está rota sería exactamente el fallo callado
     * que el certificado existe para evitar.
     */
    fun exportarPlacaTresMf(
        ruta: String,
        ids: List<String>,
        resolucion: Float,
        alAvanzar: ((Float) -> Unit)? = null,
    ): List<Certificado>? {
        if (ruta.isBlank()) return rechazarPlaca("No se dijo dónde guardar la placa")
        if (!resolucion.isFinite() || resolucion <= 0f) return rechazarPlaca("La resolución de exportación no es válida")
        if (ids.size < 2) return rechazarPlaca("Una placa necesita al menos dos cuerpos")
        val piezas = ids.map { id ->
            documento.buscar(id) ?: return rechazarPlaca("No existe la pieza $id")
        }
        if (piezas.any { it.id == documento.raiz.id }) {
            return rechazarPlaca("La raíz no es un cuerpo de la placa: es el documento entero")
        }
        ultimoError = null
        val objetos = ArrayList<TresMf.ObjetoDePlaca>(piezas.size)
        val certificados = ArrayList<Certificado>(piezas.size)
        for ((indice, pieza) in piezas.withIndex()) {
            val nodo = pieza.compilar()
                ?: return rechazarPlaca("${pieza.nombre} no aporta material: está oculta o vacía")
            val exportador = Exportador(nodo)
            exportador.alAvanzar = alAvanzar?.let { progreso ->
                { avance: Float -> progreso((avance + indice) / piezas.size) }
            }
            val resultado = exportador.malladoExaminado(resolucion, resolucionUtil(nodo, 0f))
                ?: return rechazarPlaca(
                    "${pieza.nombre} no pasa el certificado a $resolucion mm; " +
                        "la placa no se escribe con una pieza rota",
                )
            objetos.add(TresMf.ObjetoDePlaca(resultado.first, pieza.nombre))
            certificados.add(resultado.second)
        }
        val paquete = TresMf.paqueteDePlaca(objetos, titulo = "Yunkil · placa")
        if (!escribirArchivo(ruta, paquete)) {
            return rechazarPlaca("No se pudo escribir $ruta")
        }
        return certificados
    }

    /**
     * Escribe el informe de fabricación en Markdown, listo para el taller.
     *
     * Es el mismo informe que muestra la aplicación —y del que depende el
     * certificado—, más lo que solo el documento sabe: la estimación de material, las
     * interferencias de los ensamblajes declarados y las medidas de procedencia «a
     * ojo», que se avisan a propósito porque son las menos fiables del proyecto.
     */
    fun exportarInformeMarkdown(ruta: String, perfilNombre: String? = null): Boolean {
        val informe = analizarFabricacion(perfilNombre)
            ?: return rechazar(ultimoError ?: "no se pudo analizar la pieza")
        val notas = documento.raiz.aplanar().flatMap { (pieza, _) ->
            pieza.encajes.mapNotNull { encaje ->
                val medida = documento.medidaDe(encaje.medida) ?: return@mapNotNull null
                if (medida.procedencia != ProcedenciaDeMedida.A_OJO) return@mapNotNull null
                "«${pieza.nombre}» verifica su encaje contra «${medida.nombre}», que está dada " +
                    "a ojo. Mídelo con calibre y sube la procedencia de la medida."
            }
        }
        val texto = informe.aMarkdown(
            estimacion = EstimacionDeImpresion.desde(informe.metricas.volumen, informe.perfil),
            interferencias = verificarEnsamblajes(),
            notas = notas,
        )
        if (!escribirArchivo(ruta, texto.encodeToByteArray())) {
            return rechazar("No se pudo escribir $ruta")
        }
        return true
    }

    private fun exportar(
        ruta: String,
        resolucion: Float,
        alAvanzar: ((Float) -> Unit)?,
        escribir: (Exportador, Float, Float) -> Certificado,
    ): Certificado? {        if (ruta.isBlank()) return rechazarNulo("No se dijo dónde guardar la pieza")
        if (!resolucion.isFinite() || resolucion <= 0f) return rechazarNulo("La resolución de exportación no es válida")
        val nodo = documento.compilar() ?: return rechazarNulo("El documento no tiene material que exportar")
        val celdas = yunkil.malla.estimarCeldas(nodo, resolucion)
        if (celdas <= 0L || celdas > 250_000_000L) {
            return rechazarNulo("La exportación recorrería ${celdas.coerceAtLeast(0)} celdas. Aumenta el valor de detalle para evitar agotar la memoria.")
        }
        val exportador = Exportador(nodo)
        exportador.alAvanzar = alAvanzar
        ultimoError = null
        val suelo = resolucionUtil(nodo, 0f)
        return escribir(exportador, maxOf(resolucion, suelo), suelo)
    }

    /**
     * La resolución más fina que tiene sentido para este árbol.
     *
     * Una malla importada no es una fórmula: es una rejilla de valores con un paso
     * concreto. Mallarla por debajo de ese paso no añade ni un detalle —la información
     * no está— y en cambio saca artefactos, porque el campo interpolado tiene esquinas
     * en las caras de sus propias celdas y el contorneado dual las persigue.
     *
     * Se midió: la misma pieza sale estanca a 0,6 y a 0,4 mm, y agujereada a 0,125.
     * Así que el exportador no baja del paso del campo más grueso del árbol. Es la
     * misma disciplina que publicar la desviación en vez de esconderla: el límite
     * existe, y más vale que lo imponga la herramienta a que lo descubra el usuario
     * con un STL roto.
     */
    private fun resolucionUtil(nodo: SdfNode, pedida: Float): Float {
        var tope = 0f
        fun recorrer(n: SdfNode) {
            if (n is CampoDeMalla) tope = maxOf(tope, n.resolucion)
            n.hijos.forEach(::recorrer)
        }
        recorrer(nodo)
        return if (tope > 0f) maxOf(pedida, tope) else pedida
    }

    private fun rechazarNulo(motivo: String): Certificado? {
        ultimoError = motivo
        return null
    }

    private fun rechazarPlaca(motivo: String): List<Certificado>? {
        ultimoError = motivo
        return null
    }

    // ------------------------------------------------------------------ fabricación

    /** Nombres de los perfiles de fabricación disponibles. */
    fun perfilesDeFabricacion(): List<String> = CatalogoDePerfiles.todos.map { it.nombre }

    /** Los que ha guardado el usuario, que son los únicos que se pueden borrar. */
    fun perfilesPropios(): List<String> = CatalogoDePerfiles.propios.map { it.nombre }

    /**
     * Lee del disco los perfiles propios. Devuelve cuántos había, o −1 si el archivo está
     * ilegible; en ese caso no se pierde nada, simplemente se sigue con los de fábrica.
     */
    fun cargarPerfiles(ruta: String): Int = CatalogoDePerfiles.cargarDesde(ruta)

    // ------------------------------------------------------- calibrar con un cupón

    /**
     * Pone el cupón de calibración de un perfil en el documento, listo para exportar.
     *
     * Reemplaza lo que hubiera: el cupón es una probeta, no una pieza que se mezcle con el
     * trabajo. Entra en el historial, así que un ⌘Z devuelve el documento de antes.
     */
    fun cargarCuponDeCalibracion(nombrePerfil: String): Boolean {
        val perfil = PerfilFabricacion.porNombre(nombrePerfil)
            ?: return rechazar("No existe el perfil «$nombrePerfil»")
        reemplazarDocumento(CuponDeCalibracion.documento(perfil))
        return true
    }

    /**
     * Las holguras que ofrece cada estación del cupón, en el mismo orden en que van
     * impresas. La estación que se traga el pasador sin bailar es la holgura de la máquina.
     */
    fun holgurasDelCupon(nombrePerfil: String): List<Float> {
        val perfil = PerfilFabricacion.porNombre(nombrePerfil) ?: return emptyList()
        return CuponDeCalibracion.estaciones(perfil).map { it.holgura }
    }

    /** El diámetro impreso de cada estación, para poder comprobarlo con el calibre. */
    fun diametrosDelCupon(nombrePerfil: String): List<Float> {
        val perfil = PerfilFabricacion.porNombre(nombrePerfil) ?: return emptyList()
        return CuponDeCalibracion.estaciones(perfil).map { it.diametro(CuponDeCalibracion.NOMINAL) }
    }

    /**
     * Guarda un perfil calibrado con la holgura que dio el cupón impreso, y lo activa.
     *
     * El nombre nuevo es obligatorio y no puede ser el de un perfil de fábrica: lo que se
     * está guardando ya no son los números del fabricante, son los de una máquina concreta
     * con un material concreto, y confundirlos es perder justo lo que se acaba de medir.
     *
     * @return el motivo si no se pudo, o `null` si quedó guardado y activo.
     */
    fun guardarPerfilCalibrado(
        nombreBase: String,
        nombreNuevo: String,
        holgura: Float,
        ruta: String,
    ): String? {
        val base = PerfilFabricacion.porNombre(nombreBase) ?: return "No existe el perfil «$nombreBase»"
        val limpio = nombreNuevo.trim()
        if (limpio.isEmpty()) return "El perfil calibrado necesita un nombre"
        if (!holgura.isFinite() || holgura <= 0f) return "La holgura medida tiene que ser positiva"

        val calibrado = base.calibradoCon(holgura).copy(nombre = limpio, derivadoDe = base.nombre)
        CatalogoDePerfiles.guardar(calibrado, ruta)?.let { return it }
        usarPerfilCalibrado(calibrado)
        return null
    }

    /**
     * Guarda un perfil con los umbrales cambiados a mano, y lo activa.
     *
     * Es lo que faltaba para que un perfil sea de quien lo usa y no una constante: una
     * boquilla de 0,25, un material que cuelga peor de lo que dice la tabla, una cama que
     * agarra mejor. Los valores se validan en el constructor de [PerfilFabricacion] —una
     * altura de capa mayor que la boquilla no se puede imprimir—, así que un número
     * imposible se rechaza con su motivo en vez de guardarse.
     *
     * Queda marcado como `editado`, **también si se partía de uno calibrado**. Es
     * deliberado: «calibrado en tu máquina» dice que esos números salieron de una pieza
     * impresa y medida, y en cuanto alguien toca uno a mano deja de ser cierto. La etiqueta
     * solo vale mientras se pueda creer. De dónde salió se conserva en `derivadoDe`.
     */
    fun guardarPerfilEditado(
        nombreBase: String,
        nombreNuevo: String,
        boquilla: Float,
        alturaCapa: Float,
        perimetros: Int,
        anguloVoladizoMaximo: Float,
        areaBaseMinima: Float,
        esbeltezMaxima: Float,
        holguraEncaje: Float,
        ruta: String,
    ): String? {
        val base = PerfilFabricacion.porNombre(nombreBase) ?: return "No existe el perfil «$nombreBase»"
        val limpio = nombreNuevo.trim()
        if (limpio.isEmpty()) return "El perfil necesita un nombre"
        if (!holguraEncaje.isFinite() || holguraEncaje < 0f) return "La holgura no puede ser negativa"
        if (areaBaseMinima < 0f || esbeltezMaxima <= 0f) return "El área de base y la esbeltez deben ser positivas"

        val editado = try {
            base.copy(
                nombre = limpio,
                boquilla = boquilla,
                alturaCapa = alturaCapa,
                perimetros = perimetros,
                anguloVoladizoMaximo = anguloVoladizoMaximo,
                areaBaseMinima = areaBaseMinima,
                esbeltezMaxima = esbeltezMaxima,
                holguraEncaje = holguraEncaje,
                origen = OrigenDelPerfil.EDITADO,
                derivadoDe = base.derivadoDe ?: base.nombre,
            )
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Esos umbrales no describen una impresora que pueda imprimir"
        }

        CatalogoDePerfiles.guardar(editado, ruta)?.let { return it }
        usarPerfilCalibrado(editado)
        return null
    }

    /**
     * Los umbrales del perfil activo, en el mismo orden en que los pide
     * [guardarPerfilEditado]: boquilla, altura de capa, perímetros, voladizo máximo, área
     * mínima de base, esbeltez máxima y holgura.
     */
    fun umbralesDelPerfil(): List<Float> = with(perfilDeFabricacion) {
        listOf(
            boquilla, alturaCapa, perimetros.toFloat(),
            anguloVoladizoMaximo, areaBaseMinima, esbeltezMaxima, holguraEncaje,
        )
    }

    /**
     * Borra un perfil propio. Los de fábrica no se tocan.
     *
     * Si el borrado era el activo se vuelve **al perfil del que salió**, no al primero de la
     * lista: quien calibró una A1 con PETG y borra su calibración sigue teniendo una A1
     * delante, y devolverle la P1S sería cambiarle la impresora sin avisar.
     */
    fun olvidarPerfil(nombre: String, ruta: String): String? {
        val borrado = CatalogoDePerfiles.porNombre(nombre)
        val motivo = CatalogoDePerfiles.olvidar(nombre, ruta)
        if (motivo == null && perfilDeFabricacion.nombre == nombre) {
            val vuelta = borrado?.derivadoDe?.let { CatalogoDePerfiles.porNombre(it) }
                ?: PerfilFabricacion.PREDETERMINADO
            usarPerfilCalibrado(vuelta)
        }
        return motivo
    }

    /**
     * Grosor mínimo de pared que exige un perfil, en milímetros.
     *
     * Es el umbral del tinte de la cara de corte de la sección en vivo: por debajo
     * la pared no se llena, así que se tiñe de rojo. El valor sale de aquí —del
     * perfil, con su boquilla y sus perímetros— y no de una constante de la vista.
     */
    fun grosorMinimoDePared(nombrePerfil: String): Float =
        (PerfilFabricacion.porNombre(nombrePerfil) ?: PerfilFabricacion.PREDETERMINADO)
            .grosorMinimoPared

    /**
     * Examina la pieza contra un perfil de impresora y devuelve el informe.
     *
     * Se le pasa el documento además del campo compilado para que cada aviso pueda
     * nombrar la pieza que lo causa: es la diferencia entre «hay una pared fina» y
     * «el cilindro *Agujero del eje* deja 0,9 mm».
     */
    fun analizarFabricacion(
        nombrePerfil: String? = null,
        alAvanzar: ((Float) -> Unit)? = null,
    ): InformeDeFabricacion? {
        val nodo = documento.compilar()
            ?: return rechazarInforme("El documento no tiene material que analizar")
        val perfil = PerfilFabricacion.porNombre(nombrePerfil ?: "") ?: PerfilFabricacion.PREDETERMINADO
        val analizador = AnalizadorFdm(nodo, perfil, documento)
        analizador.alAvanzar = alAvanzar
        ultimoError = null
        return analizador.analizar()
    }

    /** Orientaciones de impresión candidatas, de mejor a peor. */
    fun orientacionesDeImpresion(nombrePerfil: String? = null): List<OrientacionEvaluada> {
        val nodo = documento.compilar() ?: return emptyList()
        val perfil = PerfilFabricacion.porNombre(nombrePerfil ?: "") ?: PerfilFabricacion.PREDETERMINADO
        return BuscadorDeOrientacion(nodo, perfil).buscar()
    }

    /**
     * Aplica una corrección propuesta por el analizador.
     *
     * Pasa por el mismo camino que cualquier edición manual: entra en el historial y
     * se puede deshacer. Un arreglo que no se puede deshacer no es un arreglo, es un
     * riesgo.
     */
    fun aplicarCorreccion(correccion: Correccion): Boolean = when (correccion.accion) {
        AccionCorrectora.FIJAR_PARAMETRO -> {
            val id = correccion.piezaId
            val clave = correccion.clave
            if (id == null || clave == null) {
                rechazar("La corrección no dice qué parámetro cambiar")
            } else {
                val cambio = fijarParametro(id, clave, correccion.valor)
                if (ultimoError == null) confirmarEdicionContinua()
                cambio || ultimoError == null
            }
        }

        AccionCorrectora.ROTAR_MODELO -> girarModelo(correccion.valor, correccion.valorSecundario)

        AccionCorrectora.ESCALAR_MODELO -> {
            if (!correccion.valor.isFinite() || correccion.valor <= 0f) {
                rechazar("El factor de escala debe ser positivo")
            } else {
                val actual = documento.raiz.transform
                aplicar { doc ->
                    doc.copy(raiz = doc.raiz.copy(transform = actual.copy(scale = actual.scale * correccion.valor)))
                }
                ultimoError == null
            }
        }

        AccionCorrectora.ASENTAR_EN_PLATO -> asentarEnPlato()

        AccionCorrectora.MOVER_PIEZA -> {
            val id = correccion.piezaId
            val vector = correccion.vector
            if (id == null || vector.size < 3) {
                rechazar("La corrección no dice qué pieza mover ni hacia dónde")
            } else {
                // `vector` es la dirección unitaria y `valor` la distancia en mm.
                val cambio = mover(
                    id,
                    vector[0] * correccion.valor,
                    vector[1] * correccion.valor,
                    vector[2] * correccion.valor,
                )
                if (ultimoError == null) confirmarEdicionContinua()
                cambio || ultimoError == null
            }
        }
    }

    /** Gira el modelo entero sobre el plato, acumulando sobre el giro que ya tuviera. */
    fun girarModelo(gradosX: Float, gradosZ: Float): Boolean {
        if (!gradosX.isFinite() || !gradosZ.isFinite()) return rechazar("El giro no es un número válido")
        if (gradosX == 0f && gradosZ == 0f) return false

        val aRadianes = kotlin.math.PI.toFloat() / 180f
        val giro = Quat.fromAxisAngle(Vec3(0f, 0f, 1f), gradosZ * aRadianes) *
            Quat.fromAxisAngle(Vec3(1f, 0f, 0f), gradosX * aRadianes)

        val actual = documento.raiz.transform
        aplicar { doc ->
            doc.copy(
                raiz = doc.raiz.copy(
                    transform = actual.copy(rotation = (giro * actual.rotation).normalized()),
                ),
            )
        }
        // Girar suele dejar la pieza atravesando el plato o flotando; asentarla
        // después es lo que el usuario habría hecho a mano en el paso siguiente.
        asentarEnPlato(registrarEnHistorial = false)
        return ultimoError == null
    }

    /** Baja o sube el modelo hasta que su cota inferior descanse en el plato. */
    fun asentarEnPlato(registrarEnHistorial: Boolean = true): Boolean {
        val nodo = documento.compilar() ?: return rechazar("No hay nada que asentar")
        val desfase = nodo.cotas().min.y
        if (abs(desfase) < 1e-4f) return false
        val actual = documento.raiz.transform
        aplicar(registrarEnHistorial) { doc ->
            doc.copy(
                raiz = doc.raiz.copy(
                    transform = actual.copy(
                        translation = actual.translation - Vec3(0f, desfase, 0f),
                    ),
                ),
            )
        }
        return ultimoError == null
    }

    private fun rechazarInforme(motivo: String): InformeDeFabricacion? {
        ultimoError = motivo
        return null
    }

    // ------------------------------------------------------------------ IA

    /**
     * El documento descrito para un modelo de lenguaje: identificadores, medidas en
     * milímetros y cotas reales en el mundo. No es el JSON del documento, que gasta
     * muchos más tokens y no dice dónde acaba cada pieza.
     */
    fun contextoParaModelo(): String = documento.contextoParaModelo()

    // MARK: El hilo de la conversación

    val conversacion: Conversacion get() = documento.conversacion

    /**
     * Cuántas ediciones ha hecho la persona a mano. Sirve para saber si el hilo miente.
     *
     * Se cuenta aquí y no con una bandera porque una bandera hay que acordarse de
     * ponerla en cada camino que edita el documento —y son muchos: arrastrar una cara,
     * mover con el inspector, borrar, pegar— y el día que se olvide uno el modelo
     * corregirá de memoria una pieza que ya cambió, sin que nada lo delate.
     */
    private var edicionesAMano: Int = 0

    /** El valor del contador cuando se habló con el modelo por última vez. */
    private var edicionesAlUltimoTurno: Int = 0

    /** ¿Ha tocado la persona el modelo desde el último turno? */
    val tocadoAManoDesdeElUltimoTurno: Boolean
        get() = edicionesAMano > edicionesAlUltimoTurno

    /** Añade lo que pidió la persona al hilo. */
    fun anotarPeticion(texto: String) {
        documento = documento.copy(
            conversacion = documento.conversacion.con(Turno(Rol.PERSONA, texto)),
        )
        edicionesAlUltimoTurno = edicionesAMano
        regenerar()
    }

    /** Añade al hilo qué se hizo con la última propuesta. */
    fun anotarRespuesta(
        desenlaceNombre: String,
        aplicadas: List<String>,
        rechazadas: List<String>,
    ) {
        val desenlace = Desenlace.entries.firstOrNull { it.name == desenlaceNombre.uppercase() }
        documento = documento.copy(
            conversacion = documento.conversacion.con(
                Turno(
                    rol = Rol.YUNKIL,
                    texto = "",
                    desenlace = desenlace,
                    aplicadas = aplicadas,
                    rechazadas = rechazadas,
                ),
            ),
        )
        edicionesAlUltimoTurno = edicionesAMano
        regenerar()
    }

    /** Añade una pregunta de aclaración del modelador sin fingir que aplicó geometría. */
    fun anotarAclaracion(texto: String) {
        documento = documento.copy(
            conversacion = documento.conversacion.con(Turno(Rol.YUNKIL, texto)),
        )
        edicionesAlUltimoTurno = edicionesAMano
        regenerar()
    }

    /**
     * Borra el hilo sin tocar la geometría.
     *
     * No entra en el historial de deshacer a propósito: ⌘Z habla de la pieza, y
     * mezclar en esa pila un cambio que no se ve en pantalla convierte el deshacer en
     * una caja negra —se pulsa, no cambia nada visible, se vuelve a pulsar y se pierde
     * trabajo real—.
     */
    fun olvidarConversacion() {
        documento = documento.copy(conversacion = Conversacion())
        edicionesAlUltimoTurno = edicionesAMano
        regenerar()
    }

    /**
     * El documento y el hilo, que es lo que se le manda al modelo como mensaje de
     * usuario.
     *
     * Van juntos y en este orden a propósito: primero los **hechos** —las cotas
     * medidas del documento, que se recalculan cada vez— y después la **intención**
     * —de qué se venía hablando—. Si se cruzaran, un hilo que dijera «la caja mide 60»
     * competiría con un contexto que mide 58 después de un `acotar`, y no hay forma de
     * que el modelo sepa cuál creer.
     */
    fun contextoConHilo(presupuestoDelHilo: Int = 1200): String {
        val hilo = documento.conversacion.paraModelo(
            presupuesto = presupuestoDelHilo,
            tocadoAMano = tocadoAManoDesdeElUltimoTurno,
        )
        val contexto = documento.contextoParaModelo()
        return if (hilo.isEmpty()) contexto else "$contexto\n\n$hilo"
    }

    /** Las instrucciones del sistema, generadas desde el propio catálogo del kernel. */
    /**
     * El mensaje de sistema, con los ejemplares que vienen a cuento de [peticion].
     *
     * Sin la petición no se pueden elegir ejemplos, y sin ejemplos el catálogo
     * verificado no sirve para nada: estuvo escrito y sin usar, y el efecto era que el
     * modelo ignoraba las operaciones de dominio —`patron`, `taladro`, `acotar`— y
     * volvía a apilar cajas con coordenadas a ojo, que es justo lo que existen para
     * evitar.
     */
    fun instruccionesParaModelo(nombrePerfil: String? = null, peticion: String? = null): String {
        val perfil = PerfilFabricacion.porNombre(nombrePerfil ?: "") ?: PerfilFabricacion.PREDETERMINADO
        val base = Vocabulario.instrucciones(perfil)
        val ejemplos = peticion?.takeIf { it.isNotBlank() }?.let { Vocabulario.ejemplares(it) }.orEmpty()
        return base + ejemplos
    }

    /**
     * El mismo mensaje de sistema, más las reglas que solo aplican con una imagen.
     *
     * Se suma en vez de sustituir porque la geometría no cambia por mirar una foto:
     * lo que cambia es de dónde sale la información y qué no se puede sacar de ahí.
     * [medidaConocida] es la cota que ha dado la persona, ya redactada; sin ella el
     * modelo tiene orden de pedirla en el resumen en vez de inventarse los
     * milímetros, que es lo que hace cualquier modelo cuando se le deja.
     */
    fun instruccionesParaModeloConImagen(
        nombrePerfil: String? = null,
        medidaConocida: String? = null,
        peticion: String? = null,
    ): String = instruccionesParaModelo(nombrePerfil, peticion) +
        Vocabulario.instruccionesDeImagen(medidaConocida?.takeIf { it.isNotBlank() })

    fun correccionParaModelo(motivo: String, respuestaAnterior: String): String =
        Vocabulario.correccion(motivo, respuestaAnterior)

    /** El reintento cuando el plan se entendió pero la pieza que sale no se sostiene. */
    fun revisionParaModelo(motivos: List<String>, respuestaAnterior: String): String =
        Vocabulario.revision(motivos, respuestaAnterior)

    /**
     * Anota en la bitácora qué hizo el usuario con una propuesta.
     *
     * Se registra el desenlace, no la propuesta: lo que hace falta para afinar un
     * modelo algún día no es el plan —eso se puede generar a millares— sino si la
     * persona se quedó con la pieza. Ese dato solo existe en el instante en que
     * ocurre, así que se apunta ahora aunque todavía no haya nada que lo lea.
     *
     * Devuelve `false` si no se pudo escribir, y no pasa nada más: quedarse sin
     * registro es perder una medida, y hacer fallar una edición por eso sería
     * cambiar un problema pequeño por uno grande.
     */
    fun registrarDesenlace(
        ruta: String,
        id: String,
        momento: Long,
        peticion: String,
        plan: String,
        nombrePerfil: String,
        rondas: Int,
        reparos: List<String>,
        desenlaceNombre: String,
        rechazadas: List<String> = emptyList(),
    ): Boolean {
        val desenlace = Desenlace.entries.firstOrNull { it.name == desenlaceNombre.uppercase() }
            ?: return false
        return anadirLinea(
            ruta,
            Bitacora.aLinea(
                Asiento(
                    id = id,
                    momento = momento,
                    peticion = peticion,
                    plan = plan,
                    perfil = nombrePerfil,
                    rondas = rondas,
                    reparos = reparos,
                    desenlace = desenlace,
                    rechazadas = rechazadas,
                ),
            ),
        )
    }

    /**
     * Interpreta la respuesta cruda de un modelo. No toca el documento.
     *
     * Separar interpretar de aplicar es lo que permite enseñar la propuesta antes de
     * ejecutarla: el usuario ve qué va a pasar y decide.
     */
    fun interpretarPlan(respuesta: String, edicion: Boolean = false): PlanInterpretado {
        return when (val r = Interprete.interpretar(respuesta, edicion = edicion)) {
            is ResultadoDeInterpretacion.Rechazado -> PlanInterpretado(null, r.motivo, emptyList())
            is ResultadoDeInterpretacion.Aceptado -> PlanInterpretado(r.plan, null, r.avisos)
        }
    }

    /**
     * Aplica un plan ya interpretado, como una sola transacción deshacible.
     *
     * El perfil viaja hasta aquí porque las operaciones de dominio lo necesitan: el
     * diámetro de un taladro de paso depende de cuánto cierra los agujeros *esta*
     * impresora, y resolverlo con el perfil por omisión daría una pieza que encaja
     * en el papel y no en la mesa.
     */
    fun aplicarPlan(plan: PlanDeModelado, nombrePerfil: String? = null): ResultadoDeAplicacion =
        Aplicador(
            this,
            PerfilFabricacion.porNombre(nombrePerfil ?: "") ?: PerfilFabricacion.PREDETERMINADO,
        ).aplicar(plan)

    /**
     * Aplica solo las operaciones que el usuario aceptó.
     *
     * Antes un plan era todo o nada: cinco operaciones buenas y una mala se
     * descartaban juntas, y recuperarlas exigía volver a pedírselo al modelo con la
     * esperanza de que esta vez saliera igual menos una. Aquí se marca lo que sirve.
     *
     * La selección pasa por [Explicacion.podar] **siempre**, aunque la interfaz ya la
     * haya cerrado: una operación que nombra un alias que no se va a crear no falla,
     * se omite en silencio, y ese es exactamente el fallo callado que el resto del
     * bucle existe para no tener. El plan que se registra en el historial es el
     * podado, porque el historial tiene que contar lo que se hizo y no lo que se
     * propuso.
     */
    fun aplicarParteDelPlan(
        plan: PlanDeModelado,
        aceptadas: Set<Int>,
        nombrePerfil: String? = null,
    ): ResultadoDeAplicacion {
        val vivas = Explicacion.podar(plan, aceptadas)
        if (vivas.isEmpty()) {
            return ResultadoDeAplicacion(0, emptyList(), "no se aceptó ninguna operación")
        }
        val parcial = plan.copy(
            operaciones = plan.operaciones.filterIndexed { i, _ -> i in vivas },
            // `reemplazar` vacía el documento antes de empezar. Aceptar media propuesta
            // no puede querer decir «y de paso tira lo que ya tenías»: si el usuario
            // descartó parte de lo que iba a sustituir el trabajo previo, sustituirlo
            // igual destruiría más de lo que se acepta.
            reemplazar = plan.reemplazar && vivas.size == plan.operaciones.size,
        )
        return aplicarPlan(parcial, nombrePerfil)
    }

    /**
     * El plan contado en español, listo para enseñarlo con una casilla por línea.
     *
     * Va por aquí y no llamando a `Explicacion` desde la aplicación porque toda la
     * superficie que consume Swift entra por `Editor`: un `object` de Kotlin cruza el
     * puente con otro nombre y otra forma según la versión del compilador, y eso ya
     * ha costado un rato antes.
     */
    fun explicarPlan(plan: PlanDeModelado): List<LineaExplicada> = Explicacion.de(plan)

    /** Cierra hacia abajo la selección de operaciones: quita lo que quedaría huérfano. */
    fun podarSeleccion(plan: PlanDeModelado, marcadas: List<Int>): List<Int> =
        Explicacion.podar(plan, marcadas.toSet()).sorted()

    /** Cierra hacia arriba: añade lo que necesita lo que se acaba de marcar. */
    fun completarSeleccion(plan: PlanDeModelado, marcadas: List<Int>): List<Int> =
        Explicacion.completar(plan, marcadas.toSet()).sorted()

    /** Aplica solo las operaciones marcadas. Ver [aplicarParteDelPlan]. */
    fun aplicarParte(
        plan: PlanDeModelado,
        aceptadas: List<Int>,
        nombrePerfil: String? = null,
    ): ResultadoDeAplicacion = aplicarParteDelPlan(plan, aceptadas.toSet(), nombrePerfil)

    /** Aplica una propuesta solo sobre el mismo estado sobre el que fue generada. */
    fun aplicarParteEnVersion(
        plan: PlanDeModelado,
        aceptadas: List<Int>,
        versionEsperada: Long,
        nombrePerfil: String? = null,
    ): ResultadoDeAplicacion {
        if (versionDocumento != versionEsperada) {
            return ResultadoDeAplicacion(
                aplicadas = 0,
                omitidas = emptyList(),
                error = DOCUMENTO_CAMBIADO,
            )
        }
        return aplicarParte(plan, aceptadas, nombrePerfil)
    }

    /**
     * Ejecuta el plan en un banco de pruebas y mide lo que sale, sin tocar nada.
     *
     * El documento es inmutable, así que un `Editor` nuevo sobre él es un duplicado
     * aislado y no hay nada que restaurar después: el peor caso de esta función es
     * que se tire el banco entero.
     *
     * Esto es lo que cierra el bucle con el modelo. Hasta aquí el modelo escribía y
     * nadie miraba el resultado; ahora la misma regla que le avisa al usuario de que
     * una pieza arranca en el aire le llega a él antes de que el usuario vea nada.
     */
    fun revisarPlan(plan: PlanDeModelado, nombrePerfil: String? = null): RevisionDePlan {
        val banco = Editor(documento)
        val aplicacion = banco.aplicarPlan(plan, nombrePerfil)
        if (!aplicacion.exito) {
            return RevisionDePlan.noAplicable(aplicacion.error ?: aplicacion.resumen)
        }
        val nodo = banco.documento.compilar()
            ?: return RevisionDePlan.noAplicable("el plan no deja ninguna geometría que analizar")

        // Primero el modelado y después la fabricación: si las piezas ni siquiera se
        // tocan, los avisos de voladizo y pared son ruido sobre una pieza que todavía
        // no existe, y mandárselos al modelo lo distrae del fallo que importa.
        //
        // Las omisiones abren la lista porque son el fallo más concreto que existe:
        // el modelo pidió algo textual que no ocurrió, y hasta ahora se enteraba el
        // usuario en un aviso y el modelo no se enteraba nunca.
        val perfilDeRevision = PerfilFabricacion.porNombre(nombrePerfil ?: "")
            ?: PerfilFabricacion.PREDETERMINADO
        val deModelado =
            aplicacion.omitidas + RevisorDeGeometria.revisar(banco.documento, nodo, perfilDeRevision)
        if (deModelado.isNotEmpty()) return RevisionDePlan(deModelado, null)

        val informe = banco.analizarFabricacion(nombrePerfil)
            ?: return RevisionDePlan.noAplicable(
                banco.ultimoError ?: "el plan no deja ninguna geometría que analizar",
            )
        return RevisionDePlan.de(informe)
    }

    /**
     * Cose un plan: le añade las operaciones que unen lo que quedó suelto.
     *
     * El banco dejó el diagnóstico cerrado —el único modo de fallo que queda es el
     * contacto entre piezas— y también el límite del remedio anterior: enseñarle al
     * modelo la operación literal subió la geometría limpia de 6/8 a 7/8, pero
     * seguía dependiendo de que el modelo la transcribiera bien y de gastar una
     * ronda de inferencia en algo que Yunkil ya sabía.
     *
     * Si el revisor sabe *qué* operación arregla la pieza, aquí se aplica. Y no a
     * ciegas: se vuelve a medir el campo sobre el resultado, y solo se devuelve el
     * plan cosido si los sólidos quedaron de verdad unidos. Si no, se devuelve
     * `null` y el fallo sigue su camino hacia la ronda de corrección con el modelo,
     * que es donde estaba antes. Coser nunca puede dejar la pieza peor.
     *
     * Devuelve `null` también cuando no había nada que coser.
     */
    fun coserPlan(plan: PlanDeModelado, nombrePerfil: String? = null): PlanDeModelado? {
        val perfil = PerfilFabricacion.porNombre(nombrePerfil ?: "") ?: PerfilFabricacion.PREDETERMINADO
        val antes = reparosDe(plan, nombrePerfil, perfil) ?: return null
        val arreglos = antes.flatMap { it.arreglos }
        if (arreglos.isEmpty()) return null

        // Dos intentos, en orden de menor a mayor intervención. El primero solo pega
        // la pieza por la cara que toca; el segundo la centra además en los otros dos
        // ejes, que arregla el caso en que el modelo tampoco acertó esa parte y
        // estropea el caso en que el desplazamiento era intencionado. Cuál sirve no
        // se supone: se prueba contra el campo y se mide.
        val candidatos = listOf(
            arreglos,
            arreglos.map { if (it is Colocar) it.copy(centrar = true) else it },
        )
        for (candidato in candidatos) {
            val propuesta = plan.copy(operaciones = plan.operaciones + candidato)
            val despues = reparosDe(propuesta, nombrePerfil, perfil) ?: continue
            if (mejora(antes, despues)) return propuesta
        }
        return null
    }

    /**
     * ¿El plan cosido deja la pieza mejor que el original?
     *
     * La versión anterior preguntaba solo si quedaban sólidos sueltos, y con eso un
     * cosido que no arreglaba nada pasaba por bueno en cuanto el fallo era de otra
     * clase —una resta que no corta, por ejemplo—: `none { SOLIDOS_SUELTOS }` es
     * trivialmente cierto cuando nunca hubo sólidos sueltos.
     *
     * Ahora se exige que haya **menos fallos en total** y que **ninguna clase empeore**.
     * Lo segundo importa por sí solo: mover un sustraendo para que corte puede separar
     * la pieza en dos, y cambiar un fallo por otro no es coser, es barajar.
     */
    private fun mejora(antes: List<Reparo>, despues: List<Reparo>): Boolean {
        if (despues.size >= antes.size) return false
        return ClaseDeFallo.entries.all { clase ->
            despues.count { it.clase == clase } <= antes.count { it.clase == clase }
        }
    }

    /**
     * Añade un `acotar` si la petición dijo cuánto tiene que medir la pieza y la pieza
     * no lo cumple.
     *
     * Es la post-condición de cotas, y ataca el fallo que cometen todos los modelos por
     * igual: la forma sale bien y los milímetros no. La petición dice «60 × 40 × 25 mm»,
     * eso es comprobable, y la operación que lo arregla ya existía en el vocabulario
     * —`acotar`— sin que nadie la usara para esto.
     *
     * `acotar` escala **uniforme**, así que arregla el tamaño y no las proporciones. Por
     * eso el arreglo no se da por bueno: se aplica en un banco, se vuelve a medir, y solo
     * se devuelve el plan si la pieza quedó **más cerca** de lo pedido. Igual que coser,
     * acotar nunca puede dejarla peor.
     */
    fun acotarPlan(
        plan: PlanDeModelado,
        peticion: String,
        nombrePerfil: String? = null,
    ): PlanDeModelado? {
        val pedidas = CotasPedidas.leer(peticion) ?: return null
        val antes = medirPlan(plan, nombrePerfil) ?: return null
        val errorAntes = desvio(antes, pedidas)
        if (errorAntes.isEmpty() || errorAntes.sum() <= TOLERANCIA_DE_COTA) return null

        val arreglo = acotarPara(antes, pedidas) ?: return null
        val propuesta = plan.copy(operaciones = plan.operaciones + arreglo)
        val despues = medirPlan(propuesta, nombrePerfil) ?: return null
        return if (acerca(errorAntes, desvio(despues, pedidas))) propuesta else null
    }

    /**
     * Cuánto se aparta la pieza de lo pedido, como error relativo sumado.
     *
     * Las cotas sin eje se emparejan **por tamaño** —la mayor con la mayor— y no por
     * orden de escritura: nadie sabe si el «60 × 40 × 25» de la petición iba ancho ×
     * fondo × alto o ancho × alto × fondo, y equivocarse en eso convertiría el arnés
     * en un generador de escalados absurdos.
     */
    private fun desvio(cotas: Aabb, pedidas: CotasPedidas): List<Float> {
        val medidas = listOf(cotas.size.x, cotas.size.y, cotas.size.z)
        pedidas.eje?.let { eje ->
            val medida = when (eje) {
                EjeNombrado.X -> medidas[0]
                EjeNombrado.Y -> medidas[1]
                EjeNombrado.Z -> medidas[2]
            }
            val pedida = pedidas.medidaDelEje ?: return emptyList()
            return if (pedida <= 0f) emptyList() else listOf(abs(medida / pedida - 1f))
        }
        val pedidasOrdenadas = pedidas.libres.sortedDescending()
        val medidasOrdenadas = medidas.sortedDescending().take(pedidasOrdenadas.size)
        return pedidasOrdenadas.zip(medidasOrdenadas)
            .map { (pedida, medida) -> if (pedida <= 0f) 0f else abs(medida / pedida - 1f) }
    }

    /**
     * ¿El plan acotado deja la pieza más cerca de lo pedido?
     *
     * Dos condiciones, y la segunda es la que costó un caso del banco: el total tiene
     * que bajar **y ninguna cota puede empeorar**. Una pieza medía 59,7 donde se pedían
     * 60 y el arnés la escaló a 62,7 porque así bajaba el error medio de las tres
     * cotas: mejoró la media y estropeó la única que estaba bien. Es exactamente la
     * regla que `coserPlan` ya tenía escrita —menos fallos en total y ninguna clase
     * peor— aplicada aquí a las cotas.
     */
    private fun acerca(antes: List<Float>, despues: List<Float>): Boolean {
        if (antes.isEmpty() || despues.size != antes.size) return false
        if (despues.sum() >= antes.sum()) return false
        return despues.zip(antes).all { (d, a) -> d <= a + 1e-4f }
    }

    /** El `acotar` que lleva la pieza al tamaño pedido, o `null` si no hay por dónde. */
    private fun acotarPara(cotas: Aabb, pedidas: CotasPedidas): Acotar? {
        val medidas = listOf(cotas.size.x, cotas.size.y, cotas.size.z)
        pedidas.eje?.let { eje ->
            val medida = pedidas.medidaDelEje ?: return null
            return Acotar(objetivo = "modelo", eje = eje, medida = medida)
        }

        // Sin ejes nombrados, el factor sale de emparejar por tamaño, y se acota por la
        // cota **mayor**: es la que mejor se mide y la que menos ruido relativo tiene.
        val pedidasOrdenadas = pedidas.libres.sortedDescending()
        val medidasOrdenadas = medidas.sortedDescending()
        val factores = pedidasOrdenadas.zip(medidasOrdenadas)
            .filter { (_, medida) -> medida > 1e-4f }
            .map { (pedida, medida) -> pedida / medida }
        if (factores.isEmpty()) return null
        val factor = factores.sorted()[factores.size / 2]

        val mayor = medidasOrdenadas.first()
        if (mayor <= 1e-4f) return null
        val eje = when (mayor) {
            medidas[0] -> EjeNombrado.X
            medidas[1] -> EjeNombrado.Y
            else -> EjeNombrado.Z
        }
        return Acotar(objetivo = "modelo", eje = eje, medida = mayor * factor)
    }

    /**
     * Las cuatro vistas ortográficas de lo que deja un plan, en PNG.
     *
     * Se traza el mismo `evaluar` que es la verdad de referencia del sistema —sin Metal
     * y sin mallar—, así que lo que ve el crítico es lo que se exportaría, no una
     * aproximación suya. Y se dibuja sobre un banco aislado: mirar una propuesta no
     * puede tocar el documento del usuario.
     */
    fun vistasDelPlan(
        plan: PlanDeModelado,
        lado: Int = 320,
        nombrePerfil: String? = null,
    ): ByteArray? {
        val banco = Editor(documento)
        if (!banco.aplicarPlan(plan, nombrePerfil).exito) return null
        val nodo = banco.documento.compilar() ?: return null
        return Vistas.cuatroVistas(nodo, lado)
    }

    /** Las cotas que deja un plan, medidas en un banco aislado. `null` si no aplica. */
    private fun medirPlan(plan: PlanDeModelado, nombrePerfil: String?): Aabb? {
        val banco = Editor(documento)
        val aplicacion = banco.aplicarPlan(plan, nombrePerfil)
        if (!aplicacion.exito) return null
        return banco.cotasDelModelo()
    }

    /** Los fallos de modelado que deja un plan, medidos en un banco. `null` si no aplica. */
    private fun reparosDe(
        plan: PlanDeModelado,
        nombrePerfil: String?,
        perfil: PerfilFabricacion,
    ): List<Reparo>? {
        val banco = Editor(documento)
        val aplicacion = banco.aplicarPlan(plan, nombrePerfil)
        if (!aplicacion.exito || aplicacion.omitidas.isNotEmpty()) return null
        val nodo = banco.documento.compilar() ?: return null
        return RevisorDeGeometria.reparos(banco.documento, nodo, perfil)
    }

    // ------------------------------------------------------------------ archivo

    fun aJson(): String = FormatoYunkil.codificar(documento)

    /** Devuelve `true` si el documento se cargó; deja el actual intacto si falla. */
    fun desdeJson(texto: String): Boolean {
        val cargado = try {
            FormatoYunkil.decodificar(texto)
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
    /**
     * Aplica un cambio al documento, y solo si el resultado se puede construir.
     *
     * La comprobación de que el documento nuevo compila **antes** de sustituir al viejo
     * no es una precaución teórica. Sin ella bastaba con llevar el deslizador del ángulo
     * de una repetición circular a cero: el rango de la interfaz llega hasta ahí, el
     * nodo lo prohíbe, y la excepción saltaba con el documento roto ya guardado. A
     * partir de ese momento no fallaba una edición, fallaba **todo**: cada regeneración
     * posterior volvía a intentar compilar el mismo árbol imposible y el viewport se
     * quedaba muerto hasta reiniciar. Un estado del que no se sale no es un error de
     * usuario, es una trampa.
     *
     * Así el editor mantiene la propiedad que promete: toda mutación valida antes de
     * aplicar, y un cambio que no se puede construir deja el documento exactamente como
     * estaba. Vale para el ángulo cero y para cualquier combinación futura que un nodo
     * decida rechazar en su constructor, sin tener que acordarse de blindar cada camino
     * de edición uno por uno.
     */
    private fun aplicar(registrarEnHistorial: Boolean = true, cambio: (Documento) -> Documento): Boolean {
        val anterior = documento
        val nuevo = try {
            // Los encajes se vuelven a derivar **después** de cada cambio y no solo
            // donde se declaran. Cualquier edición puede mover la cota que gobierna un
            // encaje —un plan de la IA, un asa arrastrada, un escalado— y una relación
            // que solo se cumple en el momento de declararla no es una relación.
            cambio(documento).resolverEncajes(perfilActivo())
        } catch (e: IllegalArgumentException) {
            return rechazar(e.message ?: "El cambio no se puede aplicar")
        }
        if (nuevo == anterior) {
            ultimoError = null
            return false
        }
        val compilado = try {
            compilarSeguro(nuevo)
        } catch (e: IllegalArgumentException) {
            return rechazar(e.message ?: "El cambio deja un modelo que no se puede construir")
        }
        if (registrarEnHistorial && !enTransaccion) {
            historial.addLast(anterior)
            recortarHistorial()
            rehechos.clear()
            // Fuera de transacción edita la persona; dentro, el `Aplicador` ejecutando
            // un plan. Contarlo aquí y no con una bandera en cada camino de edición es
            // la diferencia entre que funcione siempre y que funcione hasta que alguien
            // añada una forma nueva de mover una pieza y se le olvide marcarla.
            edicionesAMano++
        }
        documento = nuevo
        ultimoError = null
        // Se reaprovecha el árbol que ya se compiló para validar: compilarlo dos veces
        // por edición no cambia nada y se paga en cada arrastre de deslizador.
        return regenerar(compilado)
    }

    private fun regenerar(compilado: SdfNode? = null): Boolean {
        val generado = generador.generar(compilado ?: compilarSeguro(documento), nodoFantasma)
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

        /**
         * El rechazo de toda aplicación ligada a la revisión que vio la IA.
         *
         * Va en una sola constante porque la aplicación lo busca por texto para
         * decidir si el fallo se le cuenta al usuario como aviso o como resultado:
         * duplicar la frase partiría esa detección el día que una se retocara.
         */
        const val DOCUMENTO_CAMBIADO = "El documento cambió; vuelve a generar la propuesta"

        /**
         * Cuánto puede desviarse la pieza de la cota pedida antes de tocarla.
         *
         * Un 2 % sobre 60 mm son 1,2: por debajo de eso la diferencia sale del
         * redondeo de la propia caja envolvente y no de que el modelo se equivocara,
         * y escalar por ella sería ruido con forma de arreglo.
         */
        const val TOLERANCIA_DE_COTA = 0.02f

        /** Las operaciones que producen cantos, y por tanto lo único fileteable. */
        val BOOLEANAS = setOf(TipoPieza.UNION, TipoPieza.DIFERENCIA, TipoPieza.INTERSECCION)

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
