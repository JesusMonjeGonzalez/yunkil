package yunkil.fabricacion

import kotlinx.serialization.Serializable
import yunkil.doc.Documento
import yunkil.doc.TipoPieza
import yunkil.kernel.Aabb
import yunkil.kernel.SdfNode
import yunkil.kernel.Vec3
import yunkil.malla.ContorneadoDual
import yunkil.malla.Exportador
import yunkil.malla.Malla
import yunkil.malla.TopologiaDeMalla
import yunkil.malla.estimarCeldas
import yunkil.malla.resolucionSugerida
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Qué le pasa a la pieza si se manda a imprimir tal cual.
 *
 * Tres niveles y ni uno más. `FALLARA` significa que se ha medido algo que la
 * máquina no puede materializar; no es una estimación. `PROBABLE` es un riesgo
 * real que depende de la calibración. `MEJORABLE` es una sugerencia y nunca debe
 * pintarse en rojo. Inflar la severidad es la forma más rápida de que el usuario
 * aprenda a ignorar los avisos.
 */
@Serializable
enum class Severidad(val etiqueta: String, val peso: Int) {
    FALLARA("fallará", 3),
    PROBABLE("probable", 2),
    MEJORABLE("mejorable", 1),
}

@Serializable
enum class Regla(val etiqueta: String) {
    GROSOR_PARED("Grosor de pared"),
    DETALLE_MINIMO("Detalle mínimo"),
    VOLADIZO("Voladizo"),
    SIN_APOYO("Material sin apoyo"),
    BASE_APOYO("Base de apoyo"),
    ESBELTEZ("Esbeltez"),
    VOLUMEN_DE_IMPRESION("Volumen de impresión"),
    INTERFERENCIA("Interferencia"),
    HOLGURA_DE_ENCAJE("Holgura de encaje"),

    /**
     * La holgura que el documento **declara**, comprobada sobre la geometría final.
     *
     * Es distinta de [HOLGURA_DE_ENCAJE], que mira si dos piezas del modelo se rozan.
     * Esta compara contra una medida del mundo real que alguien dio, así que su fallo
     * no es «estas dos se tocan» sino «esto no va a entrar donde dijiste».
     */
    ENCAJE_DECLARADO("Encaje declarado"),
    MALLA_EXPORTABLE("Malla exportable"),
}

/** Lo que sabe hacer una corrección. La interfaz las traduce a llamadas del editor. */
@Serializable
enum class AccionCorrectora {
    FIJAR_PARAMETRO,
    ROTAR_MODELO,
    ESCALAR_MODELO,
    ASENTAR_EN_PLATO,
    MOVER_PIEZA,
}

/**
 * Un arreglo que se puede pulsar.
 *
 * Es la diferencia entre un informe y una herramienta: el analizador ve el árbol
 * paramétrico, así que no dice «hay una pared fina», dice «el cilindro *Agujero
 * del eje* deja 0,9 mm de pared» y sabe qué parámetro tocar para arreglarlo.
 */
@Serializable
data class Correccion(
    val etiqueta: String,
    val accion: AccionCorrectora,
    val piezaId: String? = null,
    val clave: String? = null,
    val valor: Float = 0f,
    val valorSecundario: Float = 0f,
    /** Desplazamiento en milímetros del mundo para `MOVER_PIEZA`. */
    val vector: List<Float> = emptyList(),
)

@Serializable
data class Hallazgo(
    val regla: Regla,
    val severidad: Severidad,
    val titulo: String,
    val detalle: String,
    /** Lo que se ha medido, en las unidades de la regla. */
    val medido: Float,
    /** El umbral del perfil con el que se ha comparado. */
    val umbral: Float,
    val unidad: String,
    /** Superficie afectada en mm², para poder ordenar por importancia real. */
    val areaAfectada: Float,
    val punto: Vec3? = null,
    val piezaId: String? = null,
    val piezaNombre: String? = null,
    val correcciones: List<Correccion> = emptyList(),
)

/** Números medidos sobre el campo. Van al informe aunque no disparen ningún aviso. */
@Serializable
data class MetricasDeFabricacion(
    val volumen: Float,
    val areaSuperficie: Float,
    val alturaTotal: Float,
    val huella: Vec3,
    val areaDeContacto: Float,
    val areaEnVoladizo: Float,
    val fraccionEnVoladizo: Float,
    val espesorMinimo: Float,
    val resolucionDeAnalisis: Float,
    val muestras: Int,
)

@Serializable
data class InformeDeFabricacion(
    val perfil: PerfilFabricacion,
    val metricas: MetricasDeFabricacion,
    val hallazgos: List<Hallazgo>,
    /**
     * Topología de la malla con la que se ha medido todo lo demás.
     *
     * Viaja en el informe porque sale gratis —el análisis ya tiene que mallar— y
     * porque responde a una pregunta que ninguna regla contesta: si esta pieza va a
     * llegar a un STL. Se puede tener un sólido impecable en el campo y una malla
     * abierta, y hasta que esto no estuvo aquí el usuario lo descubría al exportar.
     */
    val topologia: TopologiaDeMalla? = null,
    /**
     * Lo medido de cada encaje declarado, cumpla o no.
     *
     * Van todos y no solo los que fallan, por la misma razón por la que van las
     * métricas: sin la lista, «no hay avisos de encaje» no distingue entre haberlos
     * comprobado y no haber podido mirarlos.
     */
    val encajes: List<EncajeMedido> = emptyList(),
) {
    /** No se entrega una pieza con un `fallará` sin decirlo. */
    val aptoParaImprimir: Boolean get() = hallazgos.none { it.severidad == Severidad.FALLARA }

    /**
     * Puntuación de imprimibilidad de 0 a 100. Es un resumen para la interfaz, no
     * un criterio: quien decide es la lista de hallazgos.
     */
    val puntuacion: Int
        get() {
            var castigo = 0f
            for (h in hallazgos) {
                castigo += when (h.severidad) {
                    Severidad.FALLARA -> 30f
                    Severidad.PROBABLE -> 12f
                    Severidad.MEJORABLE -> 4f
                }
            }
            castigo += metricas.fraccionEnVoladizo * 25f
            return (100f - castigo).coerceIn(0f, 100f).toInt()
        }

    val resumen: String
        get() = when {
            hallazgos.isEmpty() -> "Sin avisos para ${perfil.nombre}"
            else -> {
                val fallara = hallazgos.count { it.severidad == Severidad.FALLARA }
                val probable = hallazgos.count { it.severidad == Severidad.PROBABLE }
                val mejorable = hallazgos.size - fallara - probable
                listOfNotNull(
                    if (fallara > 0) "$fallara fallará" else null,
                    if (probable > 0) "$probable probable" else null,
                    if (mejorable > 0) "$mejorable mejorable" else null,
                ).joinToString(" · ")
            }
        }
}

/** Una porción de superficie con su normal exacta y su área. */
internal class MuestraDeSuperficie(
    val punto: Vec3,
    val normal: Vec3,
    val area: Float,
)

/**
 * Analizador de fabricación FDM.
 *
 * Todas las reglas se calculan sobre el campo de distancias, no sobre triángulos.
 * La malla solo se usa para repartir muestras por la superficie con su área, de
 * modo que un aviso pueda decir *cuánta* superficie afecta en vez de cuántos
 * triángulos, que no le importa a nadie.
 *
 * La dirección de impresión es +Y y el plato está en la cota mínima del modelo,
 * que es exactamente lo que enseña el viewport.
 */
class AnalizadorFdm(
    private val nodo: SdfNode,
    private val perfil: PerfilFabricacion,
    private val documento: Documento? = null,
    resolucion: Float? = null,
) {

    private val cotas: Aabb = nodo.cotas()
    private val alturaPlato: Float = cotas.min.y
    private val resolucionDeAnalisis: Float = resolucion ?: resolucionAdecuada(nodo, perfil)

    /** Aviso de progreso entre 0 y 1. */
    var alAvanzar: ((Float) -> Unit)? = null

    fun analizar(): InformeDeFabricacion {
        val contorneado = ContorneadoDual(nodo, resolucionDeAnalisis)
        contorneado.alAvanzar = { alAvanzar?.invoke(it * 0.5f) }
        val malla = contorneado.generar()

        val muestras = ArrayList<MuestraDeSuperficie>(malla.numeroDeTriangulos)
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
            val area = cruz.length() * 0.5f
            if (area <= 0f || !area.isFinite()) continue
            // El baricentro de un triángulo de contorneado dual no cae exactamente
            // sobre la superficie, así que se proyecta con un paso de Newton contra
            // el campo. Sin esto, medir el espesor arrancaría ya dentro del material
            // y toda la pieza parecería más gruesa de lo que es.
            val centro = (a + b + c) / 3f
            val n0 = gradiente(centro)
            val sobreLaSuperficie = centro - n0 * nodo.evaluar(centro)
            // La normal sale del gradiente del campo, no del triángulo: el campo es
            // la verdad y el triángulo solo su aproximación a esta resolución.
            muestras.add(
                MuestraDeSuperficie(sobreLaSuperficie, gradiente(sobreLaSuperficie), area),
            )
        }
        alAvanzar?.invoke(0.6f)

        val hojas = documento?.hojasEnMundo().orEmpty()
        val hallazgos = ArrayList<Hallazgo>()

        val areaTotal = muestras.sumOf { it.area.toDouble() }.toFloat()
        val voladizo = revisarVoladizos(muestras, hojas, hallazgos)
        alAvanzar?.invoke(0.75f)

        val espesorMinimo = revisarEspesores(muestras, hojas, hallazgos)
        alAvanzar?.invoke(0.9f)

        revisarEncuentros(hojas, hallazgos)

        val areaDeContacto = areaDePrimeraCapa()
        revisarBase(areaDeContacto, hallazgos)
        revisarVolumen(hallazgos)
        revisarMallaExportable(malla, hallazgos)

        // Las holguras declaradas se comprueban contra la pieza construida, no contra
        // el plan que las declaró. Es lo único de este informe que compara el modelo
        // con una medida del mundo real.
        val encajesMedidos = documento
            ?.let { VerificadorDeEncajes(it, nodo, perfil, resolucionDeAnalisis).medir() }
            ?: emptyList()
        hallazgos.addAll(avisosDeEncaje(encajesMedidos))
        alAvanzar?.invoke(1f)

        val metricas = MetricasDeFabricacion(
            volumen = abs(malla.volumen()),
            areaSuperficie = areaTotal,
            alturaTotal = cotas.size.y,
            huella = Vec3(cotas.size.x, cotas.size.y, cotas.size.z),
            areaDeContacto = areaDeContacto,
            areaEnVoladizo = voladizo,
            fraccionEnVoladizo = if (areaTotal > 0f) voladizo / areaTotal else 0f,
            espesorMinimo = espesorMinimo,
            resolucionDeAnalisis = resolucionDeAnalisis,
            muestras = muestras.size,
        )

        return InformeDeFabricacion(
            perfil = perfil,
            metricas = metricas,
            topologia = malla.revisarTopologia(),
            encajes = encajesMedidos,
            // Lo que va a fallar primero, y a igual severidad lo que más superficie afecta.
            hallazgos = hallazgos.sortedWith(
                compareByDescending<Hallazgo> { it.severidad.peso }.thenByDescending { it.areaAfectada },
            ),
        )
    }

    // ------------------------------------------------------------------ voladizos

    /**
     * Ángulo entre la superficie y la vertical, medido desde la pared: 0° es un muro
     * y 90° un techo plano. Devuelve el área total por encima del umbral.
     *
     * La primera capa se excluye a propósito: la cara de abajo de cualquier pieza es
     * un voladizo de 90° que descansa sobre el plato, y avisar de eso sería ruido.
     */
    private fun revisarVoladizos(
        muestras: List<MuestraDeSuperficie>,
        hojas: List<HojaEnMundo>,
        salida: MutableList<Hallazgo>,
    ): Float {
        val corteDePrimeraCapa = alturaPlato + perfil.alturaCapa * 1.5f
        var areaEnVoladizo = 0f

        var peorAngulo = 0f
        var peorPunto: Vec3? = null
        var areaConAviso = 0f

        val sinApoyo = ArrayList<MuestraDeSuperficie>()
        var areaSinApoyo = 0f

        for (m in muestras) {
            if (m.punto.y <= corteDePrimeraCapa) continue
            val bajada = -m.normal.y
            if (bajada <= 0f) continue
            val angulo = grados(asin(bajada.coerceIn(0f, 1f)))
            if (angulo <= perfil.anguloVoladizoMaximo) continue

            areaEnVoladizo += m.area
            areaConAviso += m.area
            if (angulo > peorAngulo) {
                peorAngulo = angulo
                peorPunto = m.punto
            }
            if (angulo >= UMBRAL_ISLA && sinApoyo.size < TOPE_ISLAS) sinApoyo.add(m)
        }

        if (areaConAviso > perfil.boquilla * perfil.boquilla) {
            val hoja = peorPunto?.let { hojas.atribuir(it, resolucionDeAnalisis * 2f) }
            val severidad =
                if (peorAngulo >= 75f || areaConAviso > 200f) Severidad.PROBABLE else Severidad.MEJORABLE
            salida.add(
                Hallazgo(
                    regla = Regla.VOLADIZO,
                    severidad = severidad,
                    titulo = "Voladizo de ${redondear(peorAngulo, 0)}°",
                    detalle = "${redondear(areaConAviso, 0)} mm² de superficie superan los " +
                        "${redondear(perfil.anguloVoladizoMaximo, 0)}° del perfil «${perfil.nombre}». " +
                        "Necesitará soporte o saldrá con la cara inferior colgada.",
                    medido = peorAngulo,
                    umbral = perfil.anguloVoladizoMaximo,
                    unidad = "°",
                    areaAfectada = areaConAviso,
                    punto = peorPunto,
                    piezaId = hoja?.piezaId,
                    piezaNombre = hoja?.nombre,
                    correcciones = correccionesDeVoladizo(),
                ),
            )
        }

        // Islas: material que empieza en el aire. Es lo que de verdad obliga a soporte.
        for (m in sinApoyo) {
            if (!hayMaterialDebajo(m.punto)) areaSinApoyo += m.area
        }
        if (areaSinApoyo > perfil.boquilla * perfil.boquilla * 4f) {
            val ejemplo = sinApoyo.firstOrNull { !hayMaterialDebajo(it.punto) }
            val hoja = ejemplo?.punto?.let { hojas.atribuir(it, resolucionDeAnalisis * 2f) }
            salida.add(
                Hallazgo(
                    regla = Regla.SIN_APOYO,
                    severidad = Severidad.FALLARA,
                    titulo = "Material que arranca en el aire",
                    detalle = "${redondear(areaSinApoyo, 0)} mm² empiezan sin nada debajo hasta el plato. " +
                        "Sin soporte, esas capas se imprimen sobre el vacío.",
                    medido = areaSinApoyo,
                    umbral = 0f,
                    unidad = "mm²",
                    areaAfectada = areaSinApoyo,
                    punto = ejemplo?.punto,
                    piezaId = hoja?.piezaId,
                    piezaNombre = hoja?.nombre,
                    correcciones = listOf(
                        Correccion("Girar 90° en X", AccionCorrectora.ROTAR_MODELO, valor = 90f),
                        Correccion("Girar 180° en X", AccionCorrectora.ROTAR_MODELO, valor = 180f),
                    ),
                ),
            )
        }

        return areaEnVoladizo
    }

    private fun correccionesDeVoladizo(): List<Correccion> = listOf(
        Correccion("Girar 90° en X", AccionCorrectora.ROTAR_MODELO, valor = 90f),
        Correccion("Girar 180° en X", AccionCorrectora.ROTAR_MODELO, valor = 180f),
    )

    /**
     * ¿Hay material en la columna vertical bajo este punto?
     *
     * Se recorre hacia abajo con esferas trazadas: el campo *es* una distancia, así
     * que se puede avanzar esa distancia sin riesgo de saltarse nada. En el aire los
     * pasos son enormes y la comprobación sale casi gratis.
     */
    private fun hayMaterialDebajo(p: Vec3): Boolean {
        val salto = max(resolucionDeAnalisis * 2f, perfil.boquilla)
        var y = p.y - salto
        var guardas = 0
        while (y > alturaPlato && guardas++ < 4000) {
            val d = nodo.evaluar(Vec3(p.x, y, p.z))
            if (d <= 0f) return true
            y -= max(d, salto)
        }
        return false
    }

    // ------------------------------------------------------------------ espesores

    /**
     * Mide el espesor local en cada muestra y avisa de lo que la máquina no puede
     * llenar. Devuelve el mínimo encontrado.
     */
    private fun revisarEspesores(
        muestras: List<MuestraDeSuperficie>,
        hojas: List<HojaEnMundo>,
        salida: MutableList<Hallazgo>,
    ): Float {
        if (muestras.isEmpty()) return 0f

        // Medir el espesor cuesta una decena de evaluaciones por muestra. Se recorre
        // con paso constante para que el resultado no dependa del orden de la malla.
        val paso = max(1, muestras.size / TOPE_MUESTRAS_CARAS)

        var minimo = Float.MAX_VALUE
        var puntoMinimo: Vec3? = null
        var areaDesaparece = 0f
        var areaFina = 0f
        var puntoFino: Vec3? = null

        var i = 0
        while (i < muestras.size) {
            val m = muestras[i]
            val espesor = espesorEn(m.punto, m.normal)
            val areaRepresentada = m.area * paso
            if (espesor < minimo) {
                minimo = espesor
                puntoMinimo = m.punto
            }
            when {
                espesor < perfil.detalleMinimo -> areaDesaparece += areaRepresentada
                espesor < perfil.grosorMinimoPared -> {
                    areaFina += areaRepresentada
                    if (puntoFino == null) puntoFino = m.punto
                }
            }
            i += paso
        }

        if (minimo == Float.MAX_VALUE) return 0f

        if (areaDesaparece > perfil.boquilla * perfil.boquilla) {
            val hoja = puntoMinimo?.let { hojas.atribuir(it, resolucionDeAnalisis * 2f) }
            salida.add(
                Hallazgo(
                    regla = Regla.DETALLE_MINIMO,
                    severidad = Severidad.FALLARA,
                    titulo = "Detalle de ${redondear(minimo, 2)} mm",
                    detalle = "Por debajo de la boquilla de ${redondear(perfil.detalleMinimo, 2)} mm el " +
                        "laminador no genera recorrido: ese detalle desaparece sin avisar.",
                    medido = minimo,
                    umbral = perfil.detalleMinimo,
                    unidad = "mm",
                    areaAfectada = areaDesaparece,
                    punto = puntoMinimo,
                    piezaId = hoja?.piezaId,
                    piezaNombre = hoja?.nombre,
                    correcciones = correccionesDeEspesor(hoja, minimo, perfil.detalleMinimo),
                ),
            )
        } else if (areaFina > perfil.boquilla * perfil.boquilla) {
            val hoja = puntoFino?.let { hojas.atribuir(it, resolucionDeAnalisis * 2f) }
            salida.add(
                Hallazgo(
                    regla = Regla.GROSOR_PARED,
                    severidad = Severidad.PROBABLE,
                    titulo = "Pared de ${redondear(minimo, 2)} mm",
                    detalle = "El perfil «${perfil.nombre}» necesita ${redondear(perfil.grosorMinimoPared, 2)} mm " +
                        "para ${perfil.perimetros} perímetros de ${redondear(perfil.boquilla, 2)} mm. " +
                        "Más fina, la pared sale hueca o traslúcida.",
                    medido = minimo,
                    umbral = perfil.grosorMinimoPared,
                    unidad = "mm",
                    areaAfectada = areaFina,
                    punto = puntoFino,
                    piezaId = hoja?.piezaId,
                    piezaNombre = hoja?.nombre,
                    correcciones = correccionesDeEspesor(hoja, minimo, perfil.grosorMinimoPared),
                ),
            )
        }

        return minimo
    }

    private fun correccionesDeEspesor(hoja: HojaEnMundo?, medido: Float, objetivo: Float): List<Correccion> {
        val correcciones = ArrayList<Correccion>()
        if (hoja != null && medido > 0f) {
            val definicion = hoja.tipo.parametros.firstOrNull { it.clave == "grosor" }
                ?: hoja.tipo.parametros.firstOrNull { it.clave == "radioMenor" }
            if (definicion != null) {
                correcciones.add(
                    Correccion(
                        etiqueta = "Engordar «${hoja.nombre}» a ${redondear(objetivo, 2)} mm",
                        accion = AccionCorrectora.FIJAR_PARAMETRO,
                        piezaId = hoja.piezaId,
                        clave = definicion.clave,
                        valor = objetivo,
                    ),
                )
            }
        }
        if (medido > 0f) {
            // Escalar es el arreglo universal cuando la pieza no tiene un parámetro
            // que ataque la pared fina: un margen del 5 % evita quedarse en el filo.
            val factor = (objetivo / medido) * 1.05f
            correcciones.add(
                Correccion(
                    etiqueta = "Escalar la pieza ×${redondear(factor, 2)}",
                    accion = AccionCorrectora.ESCALAR_MODELO,
                    valor = factor,
                ),
            )
        }
        return correcciones
    }

    /**
     * Espesor de material bajo un punto de la superficie: cuánto se recorre hacia
     * dentro, perpendicular a la superficie, antes de volver a salir.
     *
     * La tentación es leer el campo y doblarlo, porque el campo *es* la distancia al
     * borde más cercano. No sirve: cerca de una arista el borde más cercano es la
     * cara de al lado, no la de enfrente, y un cubo macizo de 30 mm acaba declarando
     * paredes de 0,6 mm por sus doce aristas. Ese aviso falso es exactamente el que
     * arruina la confianza en la herramienta.
     *
     * Recorrer el rayo normal no tiene ese problema: la normal es perpendicular a la
     * superficie por definición, así que la salida ocurre por la cara de enfrente,
     * que es la que define la pared.
     */
    internal fun espesorEn(p: Vec3, n: Vec3): Float {
        val paso = max(perfil.boquilla * 0.2f, resolucionDeAnalisis * 0.35f)
        // No hace falta medir más allá de lo que puede disparar un aviso; parar
        // pronto es lo que mantiene el análisis en tiempo interactivo.
        val limite = perfil.grosorMinimoPared * 4f + perfil.boquilla
        var t = paso
        while (t <= limite) {
            // El cruce exacto se afina dentro del último tramo: así una pared más
            // fina que el propio paso sigue midiéndose bien.
            if (nodo.evaluar(p - n * t) >= 0f) return afinarSalida(p, n, t - paso, t)
            t += paso
        }
        return limite
    }

    private fun afinarSalida(p: Vec3, n: Vec3, dentro: Float, fuera: Float): Float {
        var a = dentro
        var b = fuera
        repeat(14) {
            val m = (a + b) * 0.5f
            if (nodo.evaluar(p - n * m) < 0f) a = m else b = m
        }
        return (a + b) * 0.5f
    }

    // ------------------------------------------------------------------ encuentros

    /**
     * Interferencias y holguras entre piezas que cuelgan de la raíz sin fusión.
     *
     * Es la regla que vende la tesis de los campos: lo que en un B-rep cuesta una
     * intersección booleana con su subsistema de robustez, aquí son dos lecturas
     * del campo sobre la caja común.
     *
     * Dos piezas que comparten un ancestro de unión o intersección están fundidas a
     * propósito: su solape es el diseño, y denunciarlo sería ruido. Solo importan
     * las piezas que cuelgan de la raíz (con la raíz sin fusión), que es el caso del
     * montaje: piezas hermanas que deben encajar o que no deben tocarse.
     */
    private fun revisarEncuentros(
        hojas: List<HojaEnMundo>,
        salida: MutableList<Hallazgo>,
    ) {
        if (hojas.size < 2) return
        val doc = documento ?: return
        // Con la raíz fundida, todo solape entre sus hijas es una unión buscada.
        if (doc.raiz.tipo != TipoPieza.UNION || doc.raiz.parametro("fusion") > 0f) return

        val paso = max(resolucionDeAnalisis * 2f, 0.5f)
        for (i in hojas.indices) for (j in i + 1 until hojas.size) {
            val a = hojas[i]
            val b = hojas[j]
            if (a.esSustraendo || b.esSustraendo) continue
            if (ancestroComun(doc, a.piezaId, b.piezaId) != doc.raiz.id) continue
            revisarPar(a, b, paso, salida)
        }
    }

    private fun revisarPar(
        a: HojaEnMundo,
        b: HojaEnMundo,
        paso: Float,
        salida: MutableList<Hallazgo>,
    ) {
        val ca = a.nodo.cotas()
        val cb = b.nodo.cotas()

        // Solape: puntos que están dentro del material de las dos a la vez. El
        // volumen solapado en mm³ es el número que permite decidir si es un error o
        // una unión buscada, así que se mide y se enseña.
        val comun = ca.intersect(cb)
        if (comun.size.x > 0f && comun.size.y > 0f && comun.size.z > 0f) {
            var dentro = 0
            muestrear(comun, paso) { p ->
                if (a.nodo.evaluar(p) < 0f && b.nodo.evaluar(p) < 0f) dentro++
            }
            val volumen = dentro * paso * paso * paso
            val celda = paso * paso * paso
            if (volumen > celda * 2f) {
                val severidad = if (volumen >= 100f) Severidad.FALLARA else Severidad.PROBABLE
                val direccion = separacionEntre(a, b)
                salida.add(
                    Hallazgo(
                        regla = Regla.INTERFERENCIA,
                        severidad = severidad,
                        titulo = "«${a.nombre}» y «${b.nombre}» se solapan",
                        detalle = "${redondear(volumen, 1)} mm³ de material común. Si es una " +
                            "unión buscada, ignóralo; si no, sepáralas antes de imprimir.",
                        medido = volumen,
                        umbral = 0f,
                        unidad = "mm³",
                        areaAfectada = volumen,
                        piezaId = b.piezaId,
                        piezaNombre = b.nombre,
                        correcciones = listOf(
                            Correccion(
                                etiqueta = "Separar «${b.nombre}» de «${a.nombre}»",
                                accion = AccionCorrectora.MOVER_PIEZA,
                                piezaId = b.piezaId,
                                valor = kotlin.math.cbrt(volumen.toDouble()).toFloat() + perfil.holguraEncaje,
                                vector = direccion,
                            ),
                        ),
                    ),
                )
            }
            return
        }

        // Holgura: la distancia mínima de la superficie de B al material de A, en la
        // zona donde se rozan. Si sale menos de lo que pide el perfil, la pieza no
        // entrará una vez impresa: la FDM ensancha lo que el modelo dice que mide.
        val zona = ca.intersect(cb.expanded(perfil.holguraEncaje * 3f))
        if (zona.size.x <= 0f || zona.size.y <= 0f || zona.size.z <= 0f) return
        var minimo = Float.MAX_VALUE
        muestrear(zona, paso) { p ->
            if (abs(b.nodo.evaluar(p)) < paso) {
                minimo = min(minimo, a.nodo.evaluar(p))
            }
        }
        if (minimo >= perfil.holguraEncaje || !minimo.isFinite()) return

        val severidad = if (minimo < perfil.holguraEncaje) Severidad.PROBABLE else Severidad.MEJORABLE
        val falta = perfil.holguraEncaje - minimo
        salida.add(
            Hallazgo(
                regla = Regla.HOLGURA_DE_ENCAJE,
                severidad = severidad,
                titulo = "«${a.nombre}» y «${b.nombre}» se rozan",
                detalle = "Holgura de ${redondear(minimo, 2)} mm; el perfil «${perfil.nombre}» " +
                    "pide ${redondear(perfil.holguraEncaje, 2)} para que encaje sin forzar.",
                medido = minimo,
                umbral = perfil.holguraEncaje,
                unidad = "mm",
                areaAfectada = 0f,
                piezaId = b.piezaId,
                piezaNombre = b.nombre,
                correcciones = listOf(
                    Correccion(
                        etiqueta = "Holgar «${b.nombre}» ${redondear(falta + 0.05f, 2)} mm",
                        accion = AccionCorrectora.MOVER_PIEZA,
                        piezaId = b.piezaId,
                        valor = falta + 0.05f,
                        vector = separacionEntre(a, b),
                    ),
                ),
            ),
        )
    }

    /** Dirección normalizada de B respecto a A, para separarlas u holgarlas. */
    private fun separacionEntre(a: HojaEnMundo, b: HojaEnMundo): List<Float> {
        val v = b.nodo.cotas().center - a.nodo.cotas().center
        val largo = v.length()
        if (largo < 1e-6f || !largo.isFinite()) return listOf(1f, 0f, 0f)
        return listOf(v.x / largo, v.y / largo, v.z / largo)
    }

    /** Recorre las celdas de [caja] a [paso], con tope para que el coste no explote. */
    private fun muestrear(caja: Aabb, paso: Float, accion: (Vec3) -> Unit) {
        var pasoEfectivo = paso
        repeat(4) {
            val nx = ceil(caja.size.x / pasoEfectivo).toInt() + 1
            val ny = ceil(caja.size.y / pasoEfectivo).toInt() + 1
            val nz = ceil(caja.size.z / pasoEfectivo).toInt() + 1
            if (nx.toLong() * ny.toLong() * nz.toLong() <= TOPE_CELDAS_DE_ENCUENTRO) {
                for (iz in 0 until nz) for (iy in 0 until ny) for (ix in 0 until nx) {
                    accion(
                        Vec3(
                            caja.min.x + ix * pasoEfectivo,
                            caja.min.y + iy * pasoEfectivo,
                            caja.min.z + iz * pasoEfectivo,
                        ),
                    )
                }
                return
            }
            pasoEfectivo *= 2f
        }
    }

    /** El ancestro común más cercano de dos piezas, o `null` si no se encuentran. */
    private fun ancestroComun(doc: Documento, a: String, b: String): String? {
        val ancestros = HashSet<String>()
        var actual: String? = a
        while (actual != null) {
            ancestros.add(actual)
            actual = doc.padreDe(actual)
        }
        actual = b
        while (actual != null) {
            if (ancestros.contains(actual)) return actual
            actual = doc.padreDe(actual)
        }
        return null
    }

    // ------------------------------------------------------------------ base

    /**
     * Área real de la primera capa: se muestrea el campo en el plano medio de esa
     * capa. Es una medida directa, no una proyección de la sombra de la pieza, así
     * que un modelo apoyado en cuatro patas da el área de las cuatro patas.
     */
    private fun areaDePrimeraCapa(): Float {
        val y = alturaPlato + perfil.alturaCapa * 0.5f
        val paso = max(perfil.boquilla * 0.5f, resolucionDeAnalisis * 0.5f)
        val nx = min(ceil(cotas.size.x / paso).toInt() + 1, TOPE_REJILLA)
        val nz = min(ceil(cotas.size.z / paso).toInt() + 1, TOPE_REJILLA)
        if (nx <= 0 || nz <= 0) return 0f

        val pasoX = if (nx > 1) cotas.size.x / (nx - 1) else paso
        val pasoZ = if (nz > 1) cotas.size.z / (nz - 1) else paso
        var dentro = 0
        for (ix in 0 until nx) {
            val x = cotas.min.x + ix * pasoX
            for (iz in 0 until nz) {
                val z = cotas.min.z + iz * pasoZ
                if (nodo.evaluar(Vec3(x, y, z)) < 0f) dentro++
            }
        }
        return dentro * pasoX * pasoZ
    }

    private fun revisarBase(areaDeContacto: Float, salida: MutableList<Hallazgo>) {
        val altura = cotas.size.y
        if (altura <= 0f) return

        if (areaDeContacto < perfil.areaBaseMinima) {
            salida.add(
                Hallazgo(
                    regla = Regla.BASE_APOYO,
                    severidad = if (areaDeContacto < perfil.areaBaseMinima * 0.35f) {
                        Severidad.PROBABLE
                    } else {
                        Severidad.MEJORABLE
                    },
                    titulo = "Base de ${redondear(areaDeContacto, 0)} mm²",
                    detalle = "El perfil pide ${redondear(perfil.areaBaseMinima, 0)} mm² de primera capa. " +
                        "Con menos, la pieza se despega o se lleva por delante el cabezal.",
                    medido = areaDeContacto,
                    umbral = perfil.areaBaseMinima,
                    unidad = "mm²",
                    areaAfectada = areaDeContacto,
                    correcciones = listOf(
                        Correccion("Girar 90° en X", AccionCorrectora.ROTAR_MODELO, valor = 90f),
                        Correccion("Asentar en el plato", AccionCorrectora.ASENTAR_EN_PLATO),
                    ),
                ),
            )
        }

        val radioBase = sqrt(max(areaDeContacto, 1e-6f) / PI.toFloat())
        val esbeltez = altura / radioBase
        if (esbeltez > perfil.esbeltezMaxima) {
            salida.add(
                Hallazgo(
                    regla = Regla.ESBELTEZ,
                    severidad = Severidad.MEJORABLE,
                    titulo = "Esbeltez ${redondear(esbeltez, 1)}",
                    detalle = "${redondear(altura, 1)} mm de alto sobre una base equivalente a " +
                        "${redondear(radioBase * 2f, 1)} mm de diámetro. Por encima de " +
                        "${redondear(perfil.esbeltezMaxima, 1)} la pieza tiende a vibrar y a volcar.",
                    medido = esbeltez,
                    umbral = perfil.esbeltezMaxima,
                    unidad = "",
                    areaAfectada = areaDeContacto,
                    correcciones = listOf(
                        Correccion("Tumbar 90° en X", AccionCorrectora.ROTAR_MODELO, valor = 90f),
                    ),
                ),
            )
        }
    }

    /**
     * ¿Saldría de aquí un archivo, o el certificado lo va a rechazar?
     *
     * Las demás reglas miden el **campo**: si la pieza es demasiado fina, si vuela, si
     * dos piezas se pisan. Ninguna mira si la malla que sale del contorneado es un
     * sólido entregable, y eso abría un hueco de los que hacen perder la confianza en
     * una herramienta: el informe decía «apta para imprimir», el usuario le daba a
     * exportar y el certificado se negaba a escribir el archivo por una razón que el
     * informe nunca había mencionado. Dos verdades distintas sobre la misma pieza.
     *
     * La regla no reimplementa el examen: llama al **mismo** `examinar` que usa la
     * exportación, sobre la misma malla que el análisis ya ha construido. Por eso no
     * pueden divergir por un umbral que alguien cambie en un sitio y no en el otro.
     *
     * Lo que garantiza es una implicación, no una equivalencia: si el informe dice que
     * la pieza es apta, el certificado a **esta** resolución la aprueba. Al revés no,
     * y a propósito: exportar a una resolución más gruesa que la del análisis puede
     * abrir agujeros que aquí no estaban, y por eso el aviso dice a qué resolución se
     * ha medido en lugar de prometer que cualquier export saldrá bien.
     */
    private fun revisarMallaExportable(malla: Malla, salida: MutableList<Hallazgo>) {
        val certificado = Exportador(nodo).examinar(malla, resolucionDeAnalisis, 0)
        if (certificado.apto) return

        val motivos = buildList {
            if (certificado.salioVacia) {
                add("no sale ni un triángulo a ${redondear(resolucionDeAnalisis, 3)} mm")
            }
            if (!certificado.cerrada) add("la superficie tiene agujeros")
            if (!certificado.bienOrientada) add("hay caras del revés")
            if (certificado.degenerados > 0) {
                add("${certificado.degenerados} triángulos degenerados")
            }
            if (certificado.autoIntersecciones > 0) add("la superficie se cruza consigo misma")
            val tope = maxOf(certificado.resolucion * 1.5f, 0.05f)
            if (certificado.desviacionMaxima > tope) {
                add(
                    "la malla se separa ${redondear(certificado.desviacionMaxima, 3)} mm de la " +
                        "forma exacta, y el tope es ${redondear(tope, 3)} mm",
                )
            }
            if (certificado.volumenMalla <= 0f || certificado.volumenAnalitico <= 0f) {
                add("el volumen medido es cero")
            } else if (certificado.errorDeVolumen > 0.10f) {
                add("el volumen se desvía un ${redondear(certificado.errorDeVolumen * 100f, 1)} %")
            }
        }

        salida.add(
            Hallazgo(
                regla = Regla.MALLA_EXPORTABLE,
                severidad = Severidad.FALLARA,
                titulo = "No se puede exportar tal cual",
                detalle = "Mallada a ${redondear(resolucionDeAnalisis, 3)} mm, la pieza no pasa el " +
                    "certificado: ${motivos.joinToString("; ")}. El archivo no se escribiría. " +
                    "Suele arreglarse exportando con más detalle o engordando la zona más fina.",
                medido = certificado.desviacionMaxima,
                umbral = maxOf(certificado.resolucion * 1.5f, 0.05f),
                unidad = "mm",
                areaAfectada = 0f,
            ),
        )
    }

    private fun revisarVolumen(salida: MutableList<Hallazgo>) {
        val t = cotas.size
        val v = perfil.volumenDeImpresion
        // La pieza puede girarse 90° sobre el eje vertical, así que se compara la
        // huella ordenada contra la bandeja ordenada en lugar de eje a eje.
        val huella = listOf(t.x, t.z).sorted()
        val bandeja = listOf(v.x, v.z).sorted()
        val cabe = huella[0] <= bandeja[0] && huella[1] <= bandeja[1] && t.y <= v.y
        if (cabe) return

        salida.add(
            Hallazgo(
                regla = Regla.VOLUMEN_DE_IMPRESION,
                severidad = Severidad.FALLARA,
                titulo = "No cabe en ${perfil.impresora}",
                detalle = "La pieza mide ${redondear(t.x, 1)} × ${redondear(t.y, 1)} × ${redondear(t.z, 1)} mm " +
                    "y la bandeja ${redondear(v.x, 0)} × ${redondear(v.y, 0)} × ${redondear(v.z, 0)} mm.",
                medido = max(max(t.x, t.y), t.z),
                umbral = max(max(v.x, v.y), v.z),
                unidad = "mm",
                areaAfectada = 0f,
                correcciones = listOf(
                    Correccion(
                        etiqueta = "Escalar para que quepa",
                        accion = AccionCorrectora.ESCALAR_MODELO,
                        valor = min(min(v.x / max(t.x, 1e-6f), v.y / max(t.y, 1e-6f)), v.z / max(t.z, 1e-6f)) * 0.98f,
                    ),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ auxiliares

    private fun gradiente(p: Vec3): Vec3 {
        val e = resolucionDeAnalisis * 0.25f
        val g = Vec3(
            nodo.evaluar(Vec3(p.x + e, p.y, p.z)) - nodo.evaluar(Vec3(p.x - e, p.y, p.z)),
            nodo.evaluar(Vec3(p.x, p.y + e, p.z)) - nodo.evaluar(Vec3(p.x, p.y - e, p.z)),
            nodo.evaluar(Vec3(p.x, p.y, p.z + e)) - nodo.evaluar(Vec3(p.x, p.y, p.z - e)),
        )
        val len = g.length()
        return if (len < 1e-12f) Vec3(0f, 1f, 0f) else g / len
    }

    internal companion object {
        /** Por encima de este ángulo se comprueba si además hay algo debajo. */
        const val UMBRAL_ISLA = 70f

        const val TOPE_ISLAS = 3000
        const val TOPE_MUESTRAS_CARAS = 20_000
        const val TOPE_REJILLA = 1200
        const val TOPE_CELDAS = 30_000_000L

        /** Celdas máximas por par de piezas en la regla de encuentros. */
        const val TOPE_CELDAS_DE_ENCUENTRO = 4_000_000L

        /**
         * Resolución de análisis: fina como la boquilla, porque una regla no puede
         * medir lo que la malla no resuelve, pero acotada para que analizar no se
         * convierta en una espera.
         */
        fun resolucionAdecuada(nodo: SdfNode, perfil: PerfilFabricacion): Float {
            val lados = nodo.cotas().size
            // Si la pieza entera mide décimas en algún eje, la rejilla tiene que caber
            // varias veces dentro de esa décima o el contorneado no encuentra ni una
            // muestra y el analizador se queda mudo justo en el caso que debe cazar.
            val menorLado = listOf(lados.x, lados.y, lados.z).filter { it > 1e-4f }.minOrNull()
            var res = min(resolucionSugerida(nodo), perfil.boquilla * 0.75f)
            if (menorLado != null) res = min(res, menorLado / 4f)
            var guardas = 0
            while (estimarCeldas(nodo, res) > TOPE_CELDAS && guardas++ < 64) res *= 1.3f
            return res
        }

        fun grados(radianes: Float) = radianes * 180f / PI.toFloat()

        /** Redondeo para texto. Evita `1.7999998 mm` en un aviso que el usuario lee. */
        fun redondear(v: Float, decimales: Int): String {
            if (!v.isFinite()) return "—"
            var factor = 1f
            repeat(decimales) { factor *= 10f }
            val r = kotlin.math.round(v * factor) / factor
            if (decimales == 0) return r.toInt().toString()
            val texto = r.toString()
            return if (texto.contains('.')) {
                val partes = texto.split('.')
                partes[0] + "," + partes[1].padEnd(decimales, '0').take(decimales)
            } else {
                "$texto,${"0".repeat(decimales)}"
            }
        }
    }
}
