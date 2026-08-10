package yunkil.kernel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/**
 * Las brochas de escultura que no se pueden escribir sumando o restando bolas.
 *
 * Añadir y quitar volumen son booleanas: la esfera de la brocha entra en el árbol como
 * una parte más y no hace falta nada nuevo. Alisar, pellizcar y mover **no lo son**, y
 * fingir que sí —etiquetar de otra forma la misma unión— daría tres botones que hacen lo
 * mismo. Cada una de las tres necesita tocar el campo, no el conjunto de sólidos:
 *
 * - [AlisadoLocal] promedia el campo en un entorno, que es lo que baja la curvatura.
 * - [PellizcoLocal] aprieta el espacio contra un eje, que es lo que afila un relieve.
 * - [MoverLocal] arrastra el espacio, que es lo que transporta material sin romperlo.
 *
 * Las tres pagan el mismo precio y lo declaran: el campo deja de ser 1-Lipschitz. Cada
 * nodo publica su [constanteDeLipschitz] y de ahí sale el paso seguro del trazado, en
 * CPU y en Metal. Un campo que se pasa de 1 y no lo dice es un campo que abre agujeros
 * en la pantalla justo donde se acaba de esculpir.
 *
 * ### Por qué los sitios van en lista y no un nodo por trazada
 *
 * Una trazada continua deja decenas de muestras. Si cada una fuera un nodo, el alisado
 * —que evalúa a su hijo cinco veces— costaría 5^n, y cualquiera de los otros dos
 * recompilaría el shader en cada fotograma del arrastre. Con los sitios dentro del nodo
 * el coste no crece con la trazada y, cuando una muestra se funde con la anterior, la
 * topología ni se mueve: solo cambian uniforms.
 */

/** Tope de sitios por nodo. Se desenrollan en el shader, así que no puede crecer sin fin. */
const val MAXIMO_DE_SITIOS = 64

/** Lo común a toda brocha: una bola donde actúa. */
sealed interface SitioDeBrocha {
    val centro: Vec3
    val radio: Float
}

/**
 * Un sitio de brocha con peso: dónde, hasta dónde y cuánto.
 *
 * [intensidad] va en (0, 1]: cero es no alisar y uno es sustituir el campo por su media.
 */
@Serializable
data class SitioDeMezcla(
    override val centro: Vec3,
    override val radio: Float,
    val intensidad: Float,
) : SitioDeBrocha

/** Un sitio de arrastre: el material dentro de la bola se lleva [desplazamiento]. */
@Serializable
data class SitioDeArrastre(
    override val centro: Vec3,
    override val radio: Float,
    val desplazamiento: Vec3,
) : SitioDeBrocha

/**
 * Un sitio de pellizco: además del dónde y el cuánto, **por dónde**.
 *
 * [eje] es la normal de la superficie donde se pinchó, y sin ella el pellizco no existe.
 * Apretar hacia un punto en las tres direcciones a la vez no afila nada: contraer el
 * espacio de forma isótropa alrededor de un centro engorda la zona igual por todos lados,
 * que es lo que ya hace añadir volumen. Lo que afila es apretar **solo en el plano
 * perpendicular al eje**: el material converge hacia la recta que pasa por el centro y
 * la cresta se estrecha sin bajar de altura.
 */
@Serializable
data class SitioDePellizco(
    override val centro: Vec3,
    override val radio: Float,
    val intensidad: Float,
    val eje: Vec3,
) : SitioDeBrocha

/**
 * Las cuatro direcciones de un tetraedro regular, en unidades de 1/√3.
 *
 * El promedio sobre ellas es isótropo hasta el segundo orden —`Σvᵢ = 0` y
 * `Σvᵢ⊗vᵢ = (4/3)I`—, así que la media vale `d + h²/6·∇²d` igual que el estencil de
 * seis puntos de los ejes, con dos evaluaciones menos. En un nodo que ya multiplica por
 * cinco el coste del hijo, esas dos evaluaciones son el 30 % del gasto.
 *
 * El valor es un literal y no una raíz calculada a propósito: el generador de MSL emite
 * este mismo número, y así los dos lados parten del mismo float exacto.
 */
const val TERCIO_DE_RAIZ = 0.57735027f

internal val ESTRELLA_TETRAEDRO = listOf(
    Vec3(TERCIO_DE_RAIZ, TERCIO_DE_RAIZ, TERCIO_DE_RAIZ),
    Vec3(TERCIO_DE_RAIZ, -TERCIO_DE_RAIZ, -TERCIO_DE_RAIZ),
    Vec3(-TERCIO_DE_RAIZ, TERCIO_DE_RAIZ, -TERCIO_DE_RAIZ),
    Vec3(-TERCIO_DE_RAIZ, -TERCIO_DE_RAIZ, TERCIO_DE_RAIZ),
)

/**
 * Peso de la máscara en un punto: 1 donde el material está protegido del todo.
 *
 * La máscara no es una capa aparte del árbol sino un factor que llevan las tres brochas,
 * y eso no es un atajo: una capa que restaurase el campo anterior tendría que guardar una
 * copia del árbol de antes de enmascarar, y el árbol se duplicaría en cada pincelada. Como
 * factor, proteger una zona es multiplicar por cero el peso de las brochas ahí, y lo que
 * había debajo se queda **porque nadie lo toca**, que es lo que significa proteger.
 *
 * Las tres listas son la misma; se copia en cada nodo porque cada uno emite sus propios
 * uniforms y compartir una lista entre nodos rompería el preorden del que depende el
 * empaquetado.
 */
internal fun pesoDeMascara(mascara: List<SitioDeMezcla>, p: Vec3): Float {
    var m = 0f
    for (s in mascara) {
        val candidato = s.intensidad * caidaDeAcuerdo((p - s.centro).length(), s.radio)
        if (candidato > m) m = candidato
    }
    return m
}

/**
 * Cuánto gradiente añade la máscara a una brocha, contando **solo donde se tocan**.
 *
 * El término que aparece al modular una brocha por la máscara es `fuerza · |∇m|`, y los
 * dos factores solo son distintos de cero a la vez donde una bola de brocha y una zona
 * protegida se cortan. Acotarlo por el peor sitio contra la peor zona sin mirar si están
 * cerca haría impagable pintar una máscara en la cabeza para poder esculpir los pies: el
 * paso de trazado caería en toda la figura por dos bolas que nunca se ven.
 *
 * [fuerzaCerca] recibe una zona de máscara y devuelve cuánta brocha puede acumularse
 * dentro de ella. Suma o máximo según cómo componga el nodo sus sitios, que es la razón de
 * que lo decida quien llama y no esta función.
 */
internal fun <T : SitioDeBrocha> gradienteDeMascara(
    mascara: List<SitioDeMezcla>,
    sitios: List<T>,
    fuerzaCerca: (List<T>) -> Float,
): Float {
    var peor = 0f
    for (m in mascara) {
        if (m.radio <= 0f) continue
        val vecinos = sitios.filter { (it.centro - m.centro).length() <= it.radio + m.radio }
        if (vecinos.isEmpty()) continue
        val candidato = fuerzaCerca(vecinos) *
            abs(m.intensidad) * AcuerdoLocal.PENDIENTE_DE_LA_CAIDA / m.radio
        if (candidato > peor) peor = candidato
    }
    return peor
}

/**
 * Alisado local: baja la curvatura dentro de unas bolas y deja el resto intacto.
 *
 * El campo se sustituye por su media en un entorno de radio [paso], mezclada con el
 * original según el peso del sitio. Promediar un campo de distancia es exactamente un
 * flujo por curvatura media discretizado: donde la superficie es convexa la media sube
 * —y subir el campo es quitar material, o sea limar el bulto— y donde es cóncava baja,
 * que es rellenar el surco. No hay que detectar nada; sale de la propia aritmética.
 *
 * Dos propiedades hacen que esto sea utilizable y no un experimento:
 *
 * 1. **Es local de verdad.** Fuera de todas las bolas el peso es cero y el nodo
 *    devuelve el campo del hijo sin tocarlo, bit a bit. Un alisado que se notara a diez
 *    milímetros no sería una brocha, sería un parámetro global —que ya existe y se
 *    llama `fusionMm`—.
 * 2. **La media de funciones 1-Lipschitz sigue siendo 1-Lipschitz.** El único gradiente
 *    de más lo mete la caída del peso, y ese término está acotado y se publica.
 */
@Serializable
@SerialName("alisado_local")
data class AlisadoLocal(
    val hijo: SdfNode,
    val sitios: List<SitioDeMezcla>,
    /** Radio del estencil. Cuanto mayor, más detalle se lima; también más gradiente. */
    val paso: Float,
    /** Zonas protegidas: donde la máscara vale 1, el alisado no llega. */
    val mascara: List<SitioDeMezcla> = emptyList(),
) : SdfNode {

    init {
        require(sitios.isNotEmpty() && sitios.size <= MAXIMO_DE_SITIOS) {
            "El alisado necesita entre 1 y $MAXIMO_DE_SITIOS sitios, tenía ${sitios.size}"
        }
        require(mascara.size <= MAXIMO_DE_SITIOS) { "La máscara no puede pasar de $MAXIMO_DE_SITIOS zonas" }
        require(paso.isFinite() && paso > 0f) { "El paso del alisado debe ser positivo" }
    }

    /** Peso del alisado en un punto: manda el sitio que más pesa, nunca la suma. */
    fun peso(p: Vec3): Float {
        var w = 0f
        for (s in sitios) {
            val candidato = s.intensidad * caidaDeAcuerdo((p - s.centro).length(), s.radio)
            if (candidato > w) w = candidato
        }
        return w * (1f - pesoDeMascara(mascara, p))
    }

    override fun evaluar(p: Vec3): Float {
        val d0 = hijo.evaluar(p)
        val w = peso(p)
        // Sin peso no se toca el campo **ni se evalúa el hijo cuatro veces más**. El
        // shader emite el mismo corte, así que la paridad no depende de que el compilador
        // de Metal decida lo mismo que la JVM sobre multiplicar por cero.
        if (w <= 0f) return d0
        var suma = 0f
        for (v in ESTRELLA_TETRAEDRO) suma += hijo.evaluar(p + v * paso)
        return d0 + w * (suma * 0.25f - d0)
    }

    /**
     * El material solo puede haberse movido [paso] milímetros.
     *
     * `|d − d₀| ≤ w·|media − d₀| ≤ paso`, porque la media de traslados de una función
     * 1-Lipschitz no puede apartarse del original más que el propio desplazamiento. Así
     * que todo lo que sea material aquí estaba a menos de [paso] del material del hijo.
     */
    override fun cotas() = hijo.cotas().expanded(paso)

    override val escalares: List<Float>
        get() = buildList {
            add(paso)
            for (s in sitios) {
                add(s.centro.x); add(s.centro.y); add(s.centro.z)
                add(s.radio); add(s.intensidad)
            }
            for (s in mascara) {
                add(s.centro.x); add(s.centro.y); add(s.centro.z)
                add(s.radio); add(s.intensidad)
            }
        }

    override val hijos get() = listOf(hijo)

    /**
     * Cota del gradiente, **derivada y comprobada numéricamente**.
     *
     * `d = (1−w)·d₀ + w·media`. Los dos primeros términos son una combinación convexa de
     * gradientes de norma ≤ 1, así que aportan ≤ 1. El tercero es `(media − d₀)·∇w`, con
     * `|media − d₀| ≤ paso` y `|∇w| ≤ intensidad · PENDIENTE_DE_LA_CAIDA / radio`.
     *
     * De ahí sale directamente el paso de trazado: un alisado con el estencil a la mitad
     * del radio de brocha cuesta un 25 % más de pasos y no se nota; uno con el estencil
     * tan ancho como la brocha costaría el doble, y por eso el motor no lo permite.
     */
    val constanteDeLipschitz: Float
        get() {
            var peor = 0f
            for (s in sitios) {
                if (s.radio <= 0f) continue
                peor = max(peor, abs(s.intensidad) / s.radio)
            }
            // La máscara también varía con la posición, así que su pendiente se suma a la
            // de la brocha: proteger una zona abarata la geometría pero no el trazado.
            // El peso del alisado es un máximo entre sitios, así que lo que puede
            // acumularse dentro de una zona protegida es el mayor de ellos.
            val porLaMascara = gradienteDeMascara(mascara, sitios) { vecinos ->
                vecinos.maxOf { abs(it.intensidad) }
            }
            return 1f + paso * (AcuerdoLocal.PENDIENTE_DE_LA_CAIDA * peor + porLaMascara)
        }
}

/**
 * Pellizco: afila un relieve apretando el material contra un eje.
 *
 * Es una deformación del dominio y no una booleana. Se evalúa el hijo en
 * `p + Σ kᵢ·wᵢ·T(p − cᵢ)`, donde `T` borra la componente a lo largo del eje del sitio.
 * Con `k > 0` el dominio se **expande** en el plano perpendicular, y expandir el dominio
 * encoge la figura: el relieve se estrecha hacia el eje y la cresta sale más afilada, que
 * es exactamente lo que se le pide a un pellizco. Con `k < 0` pasa lo contrario y la zona
 * se ensancha.
 *
 * Que el efecto sea tangencial es lo que lo separa de añadir una bola. Una unión pega
 * material nuevo y borra lo que hubiera debajo; esto **transporta** el material que ya
 * está, así que los detalles de la zona se estrechan con ella en vez de desaparecer.
 *
 * No puede crear discontinuidades: el mapa es continuo y —mientras la suma de gradientes
 * de desplazamiento se quede por debajo de 1, que es lo que impone el constructor— también
 * inyectivo, así que no hay dos puntos del espacio que caigan encima del mismo y la
 * superficie no se pliega ni se rompe.
 */
@Serializable
@SerialName("pellizco_local")
data class PellizcoLocal(
    val hijo: SdfNode,
    val sitios: List<SitioDePellizco>,
    /** Zonas protegidas: donde la máscara vale 1, el material no se aprieta. */
    val mascara: List<SitioDeMezcla> = emptyList(),
) : SdfNode {

    init {
        require(sitios.isNotEmpty() && sitios.size <= MAXIMO_DE_SITIOS) {
            "El pellizco necesita entre 1 y $MAXIMO_DE_SITIOS sitios, tenía ${sitios.size}"
        }
        require(mascara.size <= MAXIMO_DE_SITIOS) { "La máscara no puede pasar de $MAXIMO_DE_SITIOS zonas" }
        require(sitios.all { abs(it.eje.length() - 1f) < 1e-3f }) {
            "El eje de un pellizco tiene que venir normalizado"
        }
        require(constanteDeLipschitz <= LIMITE_DE_DEFORMACION) {
            "El pellizco acumulado plegaría el material sobre sí mismo"
        }
    }

    /** Dónde hay que preguntarle al hijo. */
    fun mapear(p: Vec3): Vec3 {
        var desplazamiento = Vec3.ZERO
        for (s in sitios) {
            val radial = p - s.centro
            val altura = radial.x * s.eje.x + radial.y * s.eje.y + radial.z * s.eje.z
            val tangencial = radial - s.eje * altura
            desplazamiento += tangencial * (s.intensidad * caidaDeAcuerdo(radial.length(), s.radio))
        }
        return p + desplazamiento * (1f - pesoDeMascara(mascara, p))
    }

    override fun evaluar(p: Vec3) = hijo.evaluar(mapear(p))

    /**
     * El desplazamiento no puede pasar de `|k|·radio`, porque fuera de la bola el peso
     * es cero, dentro `|p − c| ≤ radio` y la proyección solo puede acortar ese vector.
     */
    override fun cotas(): Aabb {
        var alcance = 0f
        for (s in sitios) alcance = max(alcance, abs(s.intensidad) * s.radio)
        return hijo.cotas().expanded(alcance)
    }

    override val escalares: List<Float>
        get() = buildList {
            for (s in sitios) {
                add(s.centro.x); add(s.centro.y); add(s.centro.z)
                add(s.radio); add(s.intensidad)
                add(s.eje.x); add(s.eje.y); add(s.eje.z)
            }
            for (s in mascara) {
                add(s.centro.x); add(s.centro.y); add(s.centro.z)
                add(s.radio); add(s.intensidad)
            }
        }

    override val hijos get() = listOf(hijo)

    /**
     * Cota del gradiente contando **solo los sitios que se solapan**.
     *
     * `∇(p + despᵢ)` vale como mucho `1 + Σ|∇despᵢ|`, y `|∇despᵢ| ≤ |kᵢ|·(1 + PENDIENTE)`
     * —la proyección tangencial no aumenta ninguna norma, así que la cota del caso
     * isótropo sigue valiendo—. Sumar los 64 sitios daría una cifra inservible: en un
     * punto cualquiera solo aportan los sitios que lo contienen, y todos ellos cortan a la
     * bola de cualquiera de ellos.
     */
    val constanteDeLipschitz: Float
        get() {
            // El desplazamiento sí se suma entre sitios, así que dentro de una zona
            // protegida puede acumularse el de todos los que la tocan.
            val porLaMascara = gradienteDeMascara(mascara, sitios) { vecinos ->
                vecinos.sumOf { (abs(it.intensidad) * it.radio).toDouble() }.toFloat()
            }
            return 1f + sumaPeorDeVecinos(sitios) { s ->
                abs(s.intensidad) * (1f + AcuerdoLocal.PENDIENTE_DE_LA_CAIDA)
            } + porLaMascara
        }
}

/**
 * Mover volumen: arrastra el material de una bola hacia otro punto sin cortarlo.
 *
 * Se evalúa el hijo en `p − Σ wᵢ·vᵢ`. En el centro el peso es 1 y la forma se traslada
 * entera; en el borde el peso es 0 y nada se mueve, de modo que el material intermedio
 * se estira. Es lo que hace falta para alargar una oreja o doblar una cola sin volver a
 * describir la anatomía: la conectividad se conserva porque el mapa es un homeomorfismo,
 * y lo es mientras el gradiente del desplazamiento no llegue a 1, que es justo lo que
 * comprueba el constructor.
 */
@Serializable
@SerialName("mover_local")
data class MoverLocal(
    val hijo: SdfNode,
    val sitios: List<SitioDeArrastre>,
    /** Zonas protegidas: donde la máscara vale 1, el material no se arrastra. */
    val mascara: List<SitioDeMezcla> = emptyList(),
) : SdfNode {

    init {
        require(sitios.isNotEmpty() && sitios.size <= MAXIMO_DE_SITIOS) {
            "El movimiento necesita entre 1 y $MAXIMO_DE_SITIOS sitios, tenía ${sitios.size}"
        }
        require(mascara.size <= MAXIMO_DE_SITIOS) { "La máscara no puede pasar de $MAXIMO_DE_SITIOS zonas" }
        require(constanteDeLipschitz <= LIMITE_DE_DEFORMACION) {
            "El arrastre acumulado rompería la conectividad del material"
        }
    }

    /** Dónde hay que preguntarle al hijo. */
    fun mapear(p: Vec3): Vec3 {
        var desplazamiento = Vec3.ZERO
        for (s in sitios) {
            val w = caidaDeAcuerdo((p - s.centro).length(), s.radio)
            desplazamiento += s.desplazamiento * w
        }
        return p - desplazamiento * (1f - pesoDeMascara(mascara, p))
    }

    override fun evaluar(p: Vec3) = hijo.evaluar(mapear(p))

    override fun cotas(): Aabb {
        var alcance = 0f
        for (s in sitios) alcance = max(alcance, s.desplazamiento.length())
        return hijo.cotas().expanded(alcance)
    }

    override val escalares: List<Float>
        get() = buildList {
            for (s in sitios) {
                add(s.centro.x); add(s.centro.y); add(s.centro.z)
                add(s.radio)
                add(s.desplazamiento.x); add(s.desplazamiento.y); add(s.desplazamiento.z)
            }
            for (s in mascara) {
                add(s.centro.x); add(s.centro.y); add(s.centro.z)
                add(s.radio); add(s.intensidad)
            }
        }

    override val hijos get() = listOf(hijo)

    /** `|∇desp| ≤ |v|·PENDIENTE/radio`, sumado igual que en [PellizcoLocal]. */
    val constanteDeLipschitz: Float
        get() {
            val porLaMascara = gradienteDeMascara(mascara, sitios) { vecinos ->
                vecinos.sumOf { it.desplazamiento.length().toDouble() }.toFloat()
            }
            return 1f + sumaPeorDeVecinos(sitios) { s ->
                if (s.radio <= 0f) 0f
                else s.desplazamiento.length() * AcuerdoLocal.PENDIENTE_DE_LA_CAIDA / s.radio
            } + porLaMascara
        }
}

/**
 * Tope del gradiente de una deformación del dominio.
 *
 * Por debajo de 2 el mapa `p ↦ p + desp(p)` es inyectivo —su parte de desplazamiento
 * tiene gradiente menor que 1— y por tanto la superficie no se pliega. Es la línea entre
 * «una brocha fuerte» y «una brocha que hace geometría imposible de imprimir», y por eso
 * está en el constructor y no en la interfaz.
 */
const val LIMITE_DE_DEFORMACION = 2f

/**
 * El peor punto no ve todos los sitios: solo los que lo contienen.
 *
 * Y todos los que contienen un punto cortan entre sí. Así que el máximo, sobre cada
 * sitio, de la suma de sus vecinos que lo tocan es una cota superior válida y mucho más
 * ajustada que sumar la lista entera.
 */
private fun <T : SitioDeBrocha> sumaPeorDeVecinos(sitios: List<T>, peso: (T) -> Float): Float {
    var peor = 0f
    for (a in sitios) {
        var suma = 0f
        for (b in sitios) {
            if ((a.centro - b.centro).length() <= a.radio + b.radio) suma += peso(b)
        }
        if (suma > peor) peor = suma
    }
    return peor
}

/**
 * Cota del gradiente del árbol entero, de la que sale el paso de trazado.
 *
 * Se compone **multiplicando por el camino**: un alisado sobre un arrastre puede
 * acumular el gradiente de los dos, y quedarse con el mayor de ellos sería declarar un
 * paso que no es seguro. Entre hermanos manda el mayor, porque en un punto dado solo
 * gobierna la rama que devuelve la distancia.
 *
 * [AcuerdoLocal] queda fuera de la multiplicación y sigue contándose por el máximo, que
 * es como estaba antes de que existieran las deformaciones: sus acuerdos se apilan uno
 * por filete y sus zonas de influencia son bolas pequeñas y disjuntas, de modo que
 * multiplicarlas dividiría por cinco el paso de cualquier pieza con cinco filetes sin
 * que ningún punto del espacio vea más de uno.
 */
fun SdfNode.constanteDeLipschitz(): Float {
    var peorAcuerdo = 1f
    fun deformacionesDe(n: SdfNode): Float {
        if (n is AcuerdoLocal) peorAcuerdo = max(peorAcuerdo, n.lipschitz)
        val bajo = n.hijos.maxOfOrNull { deformacionesDe(it) } ?: 1f
        val propio = when (n) {
            is AlisadoLocal -> n.constanteDeLipschitz
            is PellizcoLocal -> n.constanteDeLipschitz
            is MoverLocal -> n.constanteDeLipschitz
            else -> 1f
        }
        return propio * bajo
    }
    return deformacionesDe(this) * peorAcuerdo
}

/** Fracción de la distancia que se puede avanzar sin saltarse la superficie. */
fun SdfNode.pasoSeguro(): Float = 1f / constanteDeLipschitz()
