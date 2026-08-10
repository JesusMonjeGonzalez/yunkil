package yunkil.organico

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import yunkil.kernel.AlisadoLocal
import yunkil.kernel.Capsula
import yunkil.kernel.Cono
import yunkil.kernel.Cordon
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.MAXIMO_DE_SITIOS
import yunkil.kernel.MoverLocal
import yunkil.kernel.PellizcoLocal
import yunkil.kernel.Quat
import yunkil.kernel.SdfNode
import yunkil.kernel.SitioDeArrastre
import yunkil.kernel.SitioDeMezcla
import yunkil.kernel.SitioDePellizco
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vec3
import yunkil.kernel.constanteDeLipschitz
import yunkil.kernel.pesoDeMascara
import yunkil.malla.Exportador
import kotlin.math.abs

@Serializable
enum class FormaOrganica { ESFERA, CAPSULA, TRONCO, CURVA }

@Serializable
enum class RolOrganico { CUERPO, CABEZA, EXTREMIDAD, OREJA, COLA, OJO, DETALLE }

@Serializable
enum class ModoOrganico { AGREGAR, QUITAR }

@Serializable
data class ParteOrganica(
    val id: String,
    val rol: RolOrganico,
    val forma: FormaOrganica,
    val centro: List<Float> = emptyList(),
    val a: List<Float> = emptyList(),
    val b: List<Float> = emptyList(),
    val radio: Float = 0f,
    val radioA: Float = 0f,
    val radioB: Float = 0f,
    /**
     * Puntos de control de una CURVA, en tripletes (x, y, z) seguidos.
     *
     * Van en una lista plana y no en una lista de tripletas porque el contrato lo
     * escribe un modelo de lenguaje: una lista de números seguidos es la forma que
     * menos veces sale mal escrita, y el validador cuenta que sean múltiplo de tres.
     */
    val puntos: List<Float> = emptyList(),
    /** El grosor de la CURVA en cada punto de control. Un cero afila la punta. */
    val radios: List<Float> = emptyList(),
    val unidoA: String? = null,
    val modo: ModoOrganico = ModoOrganico.AGREGAR,
)

/**
 * Una zona esculpida: dónde actúa una brocha que no es una booleana.
 *
 * Las tres listas de zonas del contrato son lo que hace que alisar, pellizcar y mover
 * sean **persistentes y reeditables** en vez de un retoque destructivo. Al guardar el
 * `.yunkil` viajan como cualquier otra parte de la anatomía; al abrirlo vuelven a
 * compilar el mismo campo. Una brocha que hubiera horneado la malla habría convertido
 * la escultura en un STL y se habría acabado la edición paramétrica.
 */
@Serializable
data class ZonaOrganica(
    val centro: List<Float>,
    val radio: Float,
    /** Fuerza de la brocha. Alisado en (0, 1]; pellizco con signo en [-0.3, 0.3]. */
    val intensidad: Float = 0f,
    /** Solo para el arrastre: cuánto se lleva el material de la bola. */
    val desplazamiento: List<Float> = emptyList(),
    /** Solo para el pellizco: la normal contra la que se aprieta, ya normalizada. */
    val eje: List<Float> = emptyList(),
)

/**
 * El plano contra el que se refleja lo que se esculpe.
 *
 * Antes era el plano YZ y solo el plano YZ, escrito en el código como `x → −x`. Eso vale
 * para una figura de pie y centrada, que es la mitad de los casos: en cuanto la anatomía
 * llega girada, o lo simétrico es la pareja de alas y no los lados del cuerpo, la brocha
 * espejo caía en el aire. Con normal y desplazamiento el reflejo es el mismo cálculo para
 * cualquier plano, y el valor por omisión reproduce exactamente el comportamiento
 * anterior: los contratos ya guardados no cambian de forma.
 */
@Serializable
data class PlanoDeSimetria(
    val normal: List<Float> = listOf(1f, 0f, 0f),
    /** Distancia del plano al origen a lo largo de la normal, en milímetros. */
    val desplazamiento: Float = 0f,
)

@Serializable
data class ContratoOrganico(
    val esquema: String,
    val nombre: String,
    val unidades: String = "mm",
    val fusionMm: Float = 2f,
    val partes: List<ParteOrganica>,
    /**
     * Las tres capas de escultura, en el orden en que se aplican sobre las partes.
     *
     * Están por omisión vacías, así que un contrato escrito por la IA —que solo conoce
     * la anatomía— sigue siendo válido palabra por palabra y compila exactamente al
     * mismo campo que antes de que estas listas existieran.
     */
    val pellizcos: List<ZonaOrganica> = emptyList(),
    val arrastres: List<ZonaOrganica> = emptyList(),
    val alisados: List<ZonaOrganica> = emptyList(),
    /**
     * Las zonas protegidas. No son una capa más: son un factor que llevan las otras tres,
     * de modo que donde la máscara vale 1 ninguna brocha posterior alcanza el material.
     */
    val mascaras: List<ZonaOrganica> = emptyList(),
    val simetria: PlanoDeSimetria = PlanoDeSimetria(),
)

data class ResultadoContratoOrganico(
    val aceptado: Boolean,
    val contratoCanonico: String? = null,
    val nombre: String = "",
    val motivo: String? = null,
)

data class ResultadoMotorOrganico(
    val exito: Boolean,
    val resumen: String,
    val triangulos: Int = 0,
)

object MotorOrganico {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
        encodeDefaults = true
    }

    // ------------------------------------------------------------- topes de escultura

    /**
     * Radio del estencil de alisado en fracción del radio de brocha.
     *
     * Es el mando que decide a la vez cuánto lima la brocha y cuánto cuesta trazar el
     * campo: el detalle que se borra es el más pequeño que el estencil, y el gradiente
     * de más es `PENDIENTE · factor`. A 0,45 se come los bultos de menos de dos
     * milímetros con una brocha de 4 mm y el trazado pierde un 40 % de paso. Subirlo a 1
     * alisaría el doble y costaría el doble de pasos, y ahí ya se ven agujeros en las
     * siluetas rasantes.
     */
    const val FACTOR_DE_ESTENCIL = 0.45f

    /** El pellizco más fuerte que no pliega el material sobre sí mismo. */
    const val PELLIZCO_MAXIMO = 0.3f

    /** El arrastre más largo por brochazo, en fracción del radio. */
    const val ARRASTRE_MAXIMO = 0.5f

    /**
     * Tope del gradiente de la escultura entera.
     *
     * Las tres capas se componen, y componer multiplica cotas. Sin un tope, alisar sobre
     * una zona ya pellizcada y arrastrada dejaría el paso de trazado en una décima y el
     * viewport se llenaría de agujeros. 2,6 es del mismo orden que lo que ya cuesta un
     * chaflán local ancho, que es geometría que la aplicación dibuja bien todos los días.
     */
    const val LIMITE_DE_CADENA = 2.6f

    fun instrucciones(): String = """
        Eres un escultor 3D técnico. Convierte la petición en UNA figura orgánica
        estilizada, cerrada, apoyable e imprimible. Devuelve solo JSON, sin markdown.

        Esquema exacto:
        {"esquema":"yunkil.organico.v1","nombre":"...","unidades":"mm","fusionMm":2,
         "partes":[
          {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,8,0],"b":[0,38,0],"radio":14},
          {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,52,0],"radio":13,"unidoA":"cuerpo"}
         ]}

        Formas permitidas:
        - ESFERA: centro [x,y,z], radio.
        - CAPSULA: extremos a y b, radio.
        - TRONCO: extremos a y b, radioA y radioB.
        - CURVA: puntos [x1,y1,z1, x2,y2,z2, ...] y radios [r1, r2, ...], un radio por
          punto. Traza un tubo suave que pasa por todos los puntos y cambia de grosor
          entre ellos. De 2 a 12 puntos. El último radio puede ser 0 para afilar.
          Ejemplo de cola: {"id":"cola","rol":"COLA","forma":"CURVA",
          "puntos":[0,30,12, 6,38,20, 4,48,24, -4,52,18],"radios":[5,3.5,2,0.4],
          "unidoA":"cuerpo"}
        Roles: CUERPO, CABEZA, EXTREMIDAD, OREJA, COLA, OJO, DETALLE.

        Reglas:
        - milímetros, Y vertical, frente hacia Z negativo, suelo Y=0;
        - exactamente un CUERPO y una CABEZA;
        - entre 3 y 64 partes, ids únicos; unidoA solo apunta a una parte anterior;
        - cada parte debe penetrar ligeramente en la parte a la que se une;
        - usa CURVA para colas, trompas, cuernos curvados, mechones, tentáculos y cables:
          una sola CURVA sustituye a la cadena de cápsulas y afila mejor;
        - el primer punto de una CURVA debe caer dentro de la parte a la que se une;
        - usa cápsulas para brazos, piernas y dedos gruesos rectos;
        - usa TRONCO para orejas, hocicos y puntas rectas;
        - ojos y detalles deben solaparse con la cabeza o cuerpo, no flotar;
        - fusionMm entre 0.2 y 8; evita detalles menores de 1.2 mm;
        - altura habitual 60-140 mm y base de los pies en Y=0;
        - no incluyas scripts, rutas, materiales, texturas ni campos no listados.
    """.trimIndent()

    fun interpretar(respuesta: String): ResultadoContratoOrganico {
        val inicio = respuesta.indexOf('{')
        val fin = respuesta.lastIndexOf('}')
        if (inicio < 0 || fin <= inicio) return rechazo("no se encontró un objeto JSON")
        val contrato = try {
            json.decodeFromString<ContratoOrganico>(respuesta.substring(inicio, fin + 1))
        } catch (e: Exception) {
            return rechazo("JSON orgánico inválido: ${e.message}")
        }
        validar(contrato)?.let { return rechazo(it) }
        return ResultadoContratoOrganico(
            aceptado = true,
            contratoCanonico = json.encodeToString(contrato),
            nombre = contrato.nombre.trim(),
        )
    }

    fun generarStl(
        contratoCanonico: String,
        ruta: String,
        resolucion: Float = 0.8f,
    ): ResultadoMotorOrganico {
        val contrato = try {
            json.decodeFromString<ContratoOrganico>(contratoCanonico)
        } catch (e: Exception) {
            return ResultadoMotorOrganico(false, "Contrato orgánico inválido: ${e.message}")
        }
        validar(contrato)?.let { return ResultadoMotorOrganico(false, it) }
        val nodo = try {
            compilar(contrato)
        } catch (e: Exception) {
            return ResultadoMotorOrganico(false, "No se pudo construir la figura: ${e.message}")
        }
        val certificado = Exportador(nodo).exportarStl(ruta, resolucion.coerceIn(0.25f, 2f))
        return ResultadoMotorOrganico(
            exito = certificado.apto && certificado.bytes > 0,
            resumen = certificado.resumen(),
            triangulos = certificado.triangulos,
        )
    }

    /** Compila un contrato ya canónico para documento, render, análisis y exportación. */
    fun nodoDeContrato(contratoCanonico: String): SdfNode? {
        val contrato = leer(contratoCanonico) ?: return null
        if (validar(contrato) != null) return null
        return try { compilar(contrato) } catch (_: Exception) { null }
    }

    /**
     * Añade una esfera de escultura y devuelve un contrato validado y canónico.
     *
     * El punto llega en el espacio que se ve, que **no** es el de las partes en cuanto
     * hay una zona arrastrada o pellizcada: ahí el espacio está deformado. Se mapea antes
     * de guardar la esfera, de modo que la bola cae donde el usuario ha pinchado y no
     * donde estaría la superficie si nadie hubiera esculpido.
     */
    fun aplicarBrocha(
        contratoCanonico: String,
        modo: String,
        x: Float,
        y: Float,
        z: Float,
        radio: Float,
        simetriaX: Boolean = false,
    ): ResultadoContratoOrganico {
        val contrato = leer(contratoCanonico)
            ?: return rechazo("Contrato orgánico inválido")
        val operacion = try { ModoOrganico.valueOf(modo.uppercase()) }
        catch (_: Exception) { return rechazo("Modo de brocha desconocido: $modo") }
        if (!radio.isFinite() || radio !in 0.2f..100f) return rechazo("El radio de brocha debe estar entre 0.2 y 100 mm")

        val nodos = contrato.partes.associate { it.id to nodoDe(it) }
        val existentes = contrato.partes.mapTo(HashSet()) { it.id }
        fun idLibre(base: String): String {
            var i = contrato.partes.size + 1
            while ("${base}_$i" in existentes) i++
            return "${base}_$i".also(existentes::add)
        }

        fun bolaEn(punto: Vec3): ParteOrganica? {
            val enPartes = aEspacioDePartes(contrato, punto)
            val padre = nodos.minByOrNull { abs(it.value.evaluar(enPartes)) }?.key ?: return null
            return ParteOrganica(
                id = idLibre(if (operacion == ModoOrganico.AGREGAR) "volumen" else "corte"),
                rol = RolOrganico.DETALLE,
                forma = FormaOrganica.ESFERA,
                centro = listOf(enPartes.x, enPartes.y, enPartes.z),
                radio = radio,
                unidoA = padre,
                modo = operacion,
            )
        }

        val nuevas = ArrayList<ParteOrganica>()
        val centro = Vec3(x, y, z)
        nuevas += bolaEn(centro) ?: return rechazo("La escultura no tiene ninguna parte")
        if (simetriaX && abs(distanciaAlPlano(contrato, centro)) > 1e-4f) {
            bolaEn(reflejarPunto(contrato, centro))?.let { nuevas += it }
        }
        val actualizado = contrato.copy(partes = contrato.partes + nuevas)
        return canonizar(actualizado, "la brocha no produjo una escultura válida")
    }

    /**
     * Alisar, pellizcar o mover: las brochas que tocan el campo y no el conjunto.
     *
     * [continuandoTrazo] distingue una muestra más del mismo arrastre de un brochazo
     * nuevo. Importa para el arrastre, donde la trazada entera es **un** sitio cuyo
     * desplazamiento crece mientras el ratón se mueve: sumar un sitio por fotograma
     * recompilaría el shader sesenta veces por segundo y apilaría deformaciones que ni
     * el usuario ni la cota de gradiente esperan.
     *
     * `dx/dy/dz` es el vector del gesto y significa dos cosas según la brocha: en MOVER
     * es cuánto se arrastra el material, y en PELLIZCAR es la normal de la superficie
     * contra la que se aprieta. Es un solo vector porque es un solo dato —la dirección
     * que el ratón acaba de señalar—, y desdoblarlo en dos parámetros dejaría uno vacío
     * en todas las llamadas.
     *
     * La fuerza pedida puede recortarse para no pasarse del [LIMITE_DE_CADENA]. Se
     * recorta y se dice; lo que no se hace es aceptar el brochazo entero y dejar el campo
     * con un gradiente que el trazado no sabe recorrer.
     */
    fun aplicarDeformacion(
        contratoCanonico: String,
        modo: String,
        x: Float,
        y: Float,
        z: Float,
        radio: Float,
        intensidad: Float,
        dx: Float,
        dy: Float,
        dz: Float,
        simetriaX: Boolean = false,
        continuandoTrazo: Boolean = false,
    ): ResultadoContratoOrganico {
        val contrato = leer(contratoCanonico) ?: return rechazo("Contrato orgánico inválido")
        val brocha = try { BrochaDeCampo.valueOf(modo.uppercase()) }
        catch (_: Exception) { return rechazo("Modo de brocha desconocido: $modo") }
        if (!radio.isFinite() || radio !in 0.5f..100f) {
            return rechazo("El radio de brocha debe estar entre 0.5 y 100 mm")
        }
        val desplazamiento = Vec3(dx, dy, dz)
        if (!intensidad.isFinite() || !desplazamiento.length().isFinite()) {
            return rechazo("La fuerza de la brocha no es un número")
        }
        if (brocha == BrochaDeCampo.MOVER && desplazamiento.length() < 1e-3f) {
            // Un arrastre de cero no es un error, es el primer fotograma del gesto.
            return ResultadoContratoOrganico(true, contratoCanonico, contrato.nombre)
        }
        if (brocha != BrochaDeCampo.MOVER && abs(intensidad) < 1e-3f) {
            return ResultadoContratoOrganico(true, contratoCanonico, contrato.nombre)
        }
        // Dentro de una zona protegida la brocha no llegaría al material —el factor de
        // máscara la anula— y guardaría una zona que no hace nada. Se dice en lugar de
        // aceptar un brochazo mudo: quien pinta una máscara y luego pincha ahí quiere
        // saber que está chocando con ella.
        if (brocha != BrochaDeCampo.PROTEGER) {
            val proteccion = pesoDeMascara(mascaraDe(contrato), Vec3(x, y, z))
            if (proteccion > 0.98f) return rechazo("Esta zona está protegida por la máscara")
        }

        // La fuerza se busca a la baja: se prueba entera y, si la cadena se pasa de
        // gradiente, se recorta por bisección hasta la mayor que cabe. Doce vueltas dan
        // tres cifras, que es más precisión de la que distingue una brocha.
        fun con(factor: Float): ContratoOrganico? {
            val candidato = conDeformacion(
                contrato, brocha, Vec3(x, y, z), radio, intensidad * factor,
                desplazamiento * factor, simetriaX, continuandoTrazo,
            ) ?: return null
            return if (cadenaDentroDelTope(candidato)) candidato else null
        }

        var elegido = con(1f)
        var conseguido = 1f
        if (elegido == null) {
            var lo = 0f
            var hi = 1f
            repeat(12) {
                val medio = (lo + hi) * 0.5f
                val candidato = con(medio)
                if (candidato != null) {
                    elegido = candidato
                    lo = medio
                } else {
                    hi = medio
                }
            }
            conseguido = lo
        }

        val contratoFinal = elegido ?: return rechazo(
            "Esta zona ya está deformada al límite: usa un radio mayor o quita volumen antes",
        )
        if (conseguido < 0.15f) {
            return rechazo("Esta zona ya está deformada al límite: la brocha apenas haría efecto")
        }
        return canonizar(contratoFinal, "la brocha no produjo una escultura válida")
    }

    fun fusionDe(contratoCanonico: String): Float = leer(contratoCanonico)?.fusionMm ?: 0f

    /** El plano espejo de la escultura: normal (3) y desplazamiento en milímetros. */
    fun simetriaDe(contratoCanonico: String): List<Float> {
        val c = leer(contratoCanonico) ?: return listOf(1f, 0f, 0f, 0f)
        val n = normalDeSimetria(c)
        return listOf(n.x, n.y, n.z, c.simetria.desplazamiento)
    }

    fun fijarSimetria(
        contratoCanonico: String,
        nx: Float,
        ny: Float,
        nz: Float,
        desplazamiento: Float,
    ): ResultadoContratoOrganico {
        val contrato = leer(contratoCanonico) ?: return rechazo("Contrato orgánico inválido")
        val bruto = Vec3(nx, ny, nz)
        val largo = bruto.length()
        if (!largo.isFinite() || largo < 1e-3f) return rechazo("El plano de simetría necesita una normal")
        if (!desplazamiento.isFinite() || abs(desplazamiento) > 1_000f) {
            return rechazo("El plano de simetría se ha ido de la figura")
        }
        val n = bruto / largo
        return canonizar(
            contrato.copy(
                simetria = PlanoDeSimetria(listOf(n.x, n.y, n.z), desplazamiento),
            ),
            "el plano de simetría no produce una escultura válida",
        )
    }

    /** Cuántas zonas guarda la escultura de una brocha concreta. */
    fun cuentaDeZonas(contratoCanonico: String, modo: String): Int {
        val c = leer(contratoCanonico) ?: return 0
        return when (modo.uppercase()) {
            "ALISAR" -> c.alisados.size
            "PELLIZCAR" -> c.pellizcos.size
            "MOVER" -> c.arrastres.size
            "PROTEGER" -> c.mascaras.size
            else -> 0
        }
    }

    /** Borra todas las zonas de una brocha, para volver a la anatomía sin rehacerla. */
    fun limpiarDeformaciones(contratoCanonico: String, modo: String): ResultadoContratoOrganico {
        val contrato = leer(contratoCanonico) ?: return rechazo("Contrato orgánico inválido")
        val brocha = try { BrochaDeCampo.valueOf(modo.uppercase()) }
        catch (_: Exception) { return rechazo("Modo de brocha desconocido: $modo") }
        val limpio = when (brocha) {
            BrochaDeCampo.ALISAR -> contrato.copy(alisados = emptyList())
            BrochaDeCampo.PELLIZCAR -> contrato.copy(pellizcos = emptyList())
            BrochaDeCampo.MOVER -> contrato.copy(arrastres = emptyList())
            BrochaDeCampo.PROTEGER -> contrato.copy(mascaras = emptyList())
        }
        return canonizar(limpio, "no se pudieron quitar las zonas")
    }

    fun fijarFusion(contratoCanonico: String, fusionMm: Float): ResultadoContratoOrganico {
        if (!fusionMm.isFinite() || fusionMm !in 0.2f..8f) {
            return rechazo("La suavidad debe estar entre 0.2 y 8 mm")
        }
        val contrato = leer(contratoCanonico) ?: return rechazo("Contrato orgánico inválido")
        return canonizar(contrato.copy(fusionMm = fusionMm), "la suavidad no produce una escultura válida")
    }

    // -------------------------------------------------------------------- compilación

    /**
     * El campo completo: la anatomía y, encima, las tres capas de escultura.
     *
     * El orden no es arbitrario. El alisado va fuera porque promedia lo que haya debajo,
     * incluido lo que arrastre y pellizco hayan movido; si fuera al revés, mover una zona
     * ya alisada la devolvería a su aspereza. Y el arrastre va por fuera del pellizco
     * para que las coordenadas que se guardan sean las que se ven: solo la capa más
     * interna necesita traducir el punto, y esa traducción es una evaluación, no una
     * inversión iterativa.
     */
    internal fun compilar(contrato: ContratoOrganico): SdfNode {
        var nodo = compilarPartes(contrato, comprobarContactos = true)
        nodo = conPellizco(contrato, nodo)
        nodo = conArrastre(contrato, nodo)
        nodo = conAlisado(contrato, nodo)
        return nodo
    }

    private fun conPellizco(c: ContratoOrganico, base: SdfNode): SdfNode {
        val sitios = c.pellizcos.map {
            SitioDePellizco(vector(it.centro), it.radio, it.intensidad, eje(it.eje))
        }
        if (sitios.isEmpty()) return base
        // La máscara se pinta sobre lo que se ve, y el pellizco vive una capa por debajo
        // del arrastre: sus zonas bajan con la misma traducción que sus centros. Sin esto,
        // proteger una oreja movida dejaría el escudo donde la oreja ya no está.
        val mascara = mascaraDe(c).map { it.copy(centro = aEspacioDePellizco(c, it.centro)) }
        return PellizcoLocal(base, sitios, mascara)
    }

    private fun mascaraDe(c: ContratoOrganico): List<SitioDeMezcla> =
        c.mascaras.map { SitioDeMezcla(vector(it.centro), it.radio, it.intensidad) }

    /**
     * El eje de un pellizco, siempre unitario.
     *
     * Un contrato viejo o escrito a mano puede no traerlo, y un eje de longitud cero
     * dejaría el nodo en NaN. Se cae a la vertical, que es la normal más probable en una
     * figura apoyada, en vez de rechazar el contrato entero por un campo omitido.
     */
    private fun eje(v: List<Float>): Vec3 {
        val bruto = vector(v)
        val largo = bruto.length()
        return if (largo > 1e-4f) bruto / largo else Vec3(0f, 1f, 0f)
    }

    private fun conArrastre(c: ContratoOrganico, base: SdfNode): SdfNode {
        val sitios = c.arrastres.map {
            SitioDeArrastre(vector(it.centro), it.radio, vector(it.desplazamiento))
        }
        return if (sitios.isEmpty()) base else MoverLocal(base, sitios, mascaraDe(c))
    }

    private fun conAlisado(c: ContratoOrganico, base: SdfNode): SdfNode {
        if (c.alisados.isEmpty()) return base
        val sitios = c.alisados.map { SitioDeMezcla(vector(it.centro), it.radio, it.intensidad) }
        val paso = (FACTOR_DE_ESTENCIL * sitios.minOf { it.radio }).coerceIn(0.05f, 6f)
        return AlisadoLocal(base, sitios, paso, mascaraDe(c))
    }

    /**
     * Del espacio que se ve al espacio en el que están escritas las partes.
     *
     * Es la misma cuenta que hace el campo al evaluarse, en el mismo orden y hacia
     * dentro: el arrastre primero —que es la capa de fuera— y el pellizco después. El
     * alisado no aparece porque no deforma el espacio: promedia el campo, y un promedio
     * no mueve el punto donde se pregunta.
     */
    private fun aEspacioDePartes(c: ContratoOrganico, punto: Vec3): Vec3 {
        val base = Esfera(1f)
        var p = punto
        val arrastre = conArrastre(c, base)
        if (arrastre is MoverLocal) p = arrastre.mapear(p)
        val pellizco = conPellizco(c, base)
        if (pellizco is PellizcoLocal) p = pellizco.mapear(p)
        return p
    }

    /** Solo el arrastre, que es la capa que separa el punto visto del espacio del pellizco. */
    private fun aEspacioDePellizco(c: ContratoOrganico, punto: Vec3): Vec3 {
        val arrastre = conArrastre(c, Esfera(1f))
        return if (arrastre is MoverLocal) arrastre.mapear(punto) else punto
    }

    private fun compilarPartes(contrato: ContratoOrganico, comprobarContactos: Boolean): SdfNode {
        val nodos = LinkedHashMap<String, SdfNode>()
        var conjunto: SdfNode? = null
        for (parte in contrato.partes) {
            val nodo = nodoDe(parte)
            if (comprobarContactos) {
                val padre = parte.unidoA?.let(nodos::get)
                if (padre != null) {
                    val muestras = muestrasDe(parte)
                    val tolerancia = maxOf(contrato.fusionMm, radioDeReferencia(parte) * 0.65f)
                    require(muestras.any { padre.evaluar(it) <= tolerancia }) {
                        "${parte.id} no toca ${parte.unidoA}"
                    }
                }
            }
            nodos[parte.id] = nodo
            conjunto = when {
                conjunto == null && parte.modo == ModoOrganico.AGREGAR -> nodo
                conjunto == null -> throw IllegalArgumentException("la primera parte no puede quitar material")
                parte.modo == ModoOrganico.AGREGAR -> Union(conjunto, nodo, contrato.fusionMm)
                else -> Diferencia(conjunto, nodo, contrato.fusionMm.coerceAtMost(radioDeReferencia(parte) * 0.5f))
            }
        }
        return requireNotNull(conjunto)
    }

    // ------------------------------------------------------------------ zonas nuevas

    /** Las brochas que trabajan sobre el campo y no sobre el conjunto de sólidos. */
    private enum class BrochaDeCampo { ALISAR, PELLIZCAR, MOVER, PROTEGER }

    /**
     * El contrato con la zona nueva puesta, o `null` si la zona no aporta nada.
     *
     * Aquí vive la regla que impide que una trazada continua reviente el contrato: una
     * muestra que cae encima de otra zona parecida **se funde con ella** en vez de añadir
     * un sitio. Fundir no cambia la topología del shader, así que arrastrar la brocha
     * sobre el mismo punto ni recompila ni acumula sitios hasta el tope de 64.
     */
    private fun conDeformacion(
        c: ContratoOrganico,
        brocha: BrochaDeCampo,
        punto: Vec3,
        radio: Float,
        intensidad: Float,
        desplazamiento: Vec3,
        simetriaX: Boolean,
        continuandoTrazo: Boolean,
    ): ContratoOrganico? {
        val espejo = simetriaX && abs(distanciaAlPlano(c, punto)) > 1e-4f
        val reflejo = reflejarPunto(c, punto)

        return when (brocha) {
            BrochaDeCampo.ALISAR -> {
                var zonas = c.alisados
                val fuerza = intensidad.coerceIn(0f, 1f)
                if (fuerza <= 0f) return null
                zonas = fundirMezcla(zonas, punto, radio, fuerza, 0f, 1f)
                if (espejo) {
                    zonas = fundirMezcla(zonas, reflejo, radio, fuerza, 0f, 1f)
                }
                c.copy(alisados = zonas)
            }

            BrochaDeCampo.PELLIZCAR -> {
                var zonas = c.pellizcos
                val fuerza = intensidad.coerceIn(-PELLIZCO_MAXIMO, PELLIZCO_MAXIMO)
                if (abs(fuerza) <= 0f) return null
                // El vector del gesto es aquí la normal de la superficie: es contra ella
                // contra lo que se aprieta, y sin ella el pellizco engordaría en vez de
                // afilar. Si no llega —una llamada sin normal—, se usa la vertical.
                val normal = eje(listOf(desplazamiento.x, desplazamiento.y, desplazamiento.z))
                // El pellizco vive por debajo del arrastre, así que su centro se guarda
                // ya traducido: si no, mover una oreja dejaría su pellizco donde estaba.
                val centro = aEspacioDePellizco(c, punto)
                zonas = fundirPellizco(zonas, centro, radio, fuerza, normal)
                if (espejo) {
                    val reflejado = aEspacioDePellizco(c, reflejo)
                    zonas = fundirPellizco(zonas, reflejado, radio, fuerza, reflejarVector(c, normal))
                }
                c.copy(pellizcos = zonas)
            }

            BrochaDeCampo.PROTEGER -> {
                var zonas = c.mascaras
                val fuerza = intensidad.coerceIn(0f, 1f)
                if (fuerza <= 0f) return null
                zonas = fundirMezcla(zonas, punto, radio, fuerza, 0f, 1f)
                if (espejo) {
                    zonas = fundirMezcla(zonas, reflejo, radio, fuerza, 0f, 1f)
                }
                c.copy(mascaras = zonas)
            }

            BrochaDeCampo.MOVER -> {
                val tope = ARRASTRE_MAXIMO * radio
                val largo = desplazamiento.length()
                if (largo < 1e-4f) return null
                val v = if (largo > tope) desplazamiento * (tope / largo) else desplazamiento
                var zonas = arrastreConGesto(c.arrastres, punto, radio, v, continuandoTrazo)
                if (espejo) {
                    zonas = arrastreConGesto(
                        zonas, reflejo, radio, reflejarVector(c, v), continuandoTrazo,
                    )
                }
                c.copy(arrastres = zonas)
            }
        }
    }

    /**
     * Funde una brochada de peso con la zona parecida que ya haya, o la añade.
     *
     * «Parecida» es a la vez cerca y del mismo tamaño: dos brochazos del mismo radio en
     * el mismo sitio son el mismo gesto repetido y su efecto tiene que sumarse hasta el
     * tope, mientras que un brochazo pequeño dentro de uno grande es otra intención y
     * merece su propio sitio.
     */
    private fun fundirMezcla(
        zonas: List<ZonaOrganica>,
        centro: Vec3,
        radio: Float,
        fuerza: Float,
        minimo: Float,
        maximo: Float,
    ): List<ZonaOrganica> {
        var mejor = -1
        var mejorDistancia = Float.MAX_VALUE
        for ((i, z) in zonas.withIndex()) {
            val d = (vector(z.centro) - centro).length()
            val proporcion = z.radio / radio
            if (d <= 0.5f * maxOf(z.radio, radio) && proporcion in 0.6f..1.7f && d < mejorDistancia) {
                mejor = i
                mejorDistancia = d
            }
        }
        // Con el contrato lleno se funde con la más cercana aunque no cumpla el parecido:
        // perder resolución de brocha es mejor que rechazar el trazo a mitad de gesto.
        if (mejor < 0 && zonas.size >= MAXIMO_DE_SITIOS) {
            mejor = zonas.indices.minByOrNull { (vector(zonas[it].centro) - centro).length() } ?: -1
        }
        if (mejor < 0) {
            return zonas + ZonaOrganica(listOf(centro.x, centro.y, centro.z), radio, fuerza)
        }
        val vieja = zonas[mejor]
        // Se suma un tercio de lo pedido: así insistir sube el efecto sin que la primera
        // muestra de un arrastre lento ya deje la zona al máximo.
        val sumada = (vieja.intensidad + fuerza * 0.34f).coerceIn(minimo, maximo)
        return zonas.toMutableList().also {
            it[mejor] = vieja.copy(radio = maxOf(vieja.radio, radio), intensidad = sumada)
        }
    }

    /**
     * Lo mismo para el pellizco, que además arrastra su eje.
     *
     * Al fundir dos brochazos el eje se queda con el del más fuerte en vez de promediarse:
     * promediar dos normales opuestas —los dos lados de una cresta— daría un eje casi nulo
     * y el pellizco apretaría en una dirección que no es ninguna de las dos.
     */
    private fun fundirPellizco(
        zonas: List<ZonaOrganica>,
        centro: Vec3,
        radio: Float,
        fuerza: Float,
        normal: Vec3,
    ): List<ZonaOrganica> {
        val ejeNuevo = listOf(normal.x, normal.y, normal.z)
        var mejor = -1
        var mejorDistancia = Float.MAX_VALUE
        for ((i, z) in zonas.withIndex()) {
            val d = (vector(z.centro) - centro).length()
            val proporcion = z.radio / radio
            if (d <= 0.5f * maxOf(z.radio, radio) && proporcion in 0.6f..1.7f && d < mejorDistancia) {
                mejor = i
                mejorDistancia = d
            }
        }
        if (mejor < 0 && zonas.size >= MAXIMO_DE_SITIOS) {
            mejor = zonas.indices.minByOrNull { (vector(zonas[it].centro) - centro).length() } ?: -1
        }
        if (mejor < 0) {
            return zonas + ZonaOrganica(
                listOf(centro.x, centro.y, centro.z), radio, fuerza, eje = ejeNuevo,
            )
        }
        val vieja = zonas[mejor]
        val sumada = (vieja.intensidad + fuerza * 0.34f).coerceIn(-PELLIZCO_MAXIMO, PELLIZCO_MAXIMO)
        return zonas.toMutableList().also {
            it[mejor] = vieja.copy(
                radio = maxOf(vieja.radio, radio),
                intensidad = sumada,
                eje = if (abs(sumada) >= abs(vieja.intensidad)) ejeNuevo else vieja.eje,
            )
        }
    }

    /**
     * El arrastre es un gesto, no una acumulación.
     *
     * Mientras el botón sigue pulsado la trazada es **un** sitio cuyo desplazamiento se
     * reescribe con el vector total desde donde se agarró. Al soltar y volver a agarrar
     * empieza otro sitio, salvo que caiga justo encima del anterior, en cuyo caso los dos
     * vectores se suman: coger dos veces el mismo trozo de material y tirar de él es
     * tirar más, no tirar dos veces desde el principio.
     */
    private fun arrastreConGesto(
        zonas: List<ZonaOrganica>,
        centro: Vec3,
        radio: Float,
        desplazamiento: Vec3,
        continuandoTrazo: Boolean,
    ): List<ZonaOrganica> {
        val tope = ARRASTRE_MAXIMO * radio
        fun acotado(v: Vec3): Vec3 {
            val largo = v.length()
            return if (largo > tope) v * (tope / largo) else v
        }

        val encaja = zonas.indexOfLast {
            (vector(it.centro) - centro).length() <= 0.25f * maxOf(it.radio, radio) &&
                abs(it.radio - radio) <= 0.1f * radio
        }
        if (encaja < 0 && zonas.size >= MAXIMO_DE_SITIOS) return zonas
        if (encaja < 0) {
            return zonas + ZonaOrganica(
                listOf(centro.x, centro.y, centro.z), radio, 0f,
                listOf(desplazamiento.x, desplazamiento.y, desplazamiento.z),
            )
        }
        val vieja = zonas[encaja]
        val total = if (continuandoTrazo) {
            acotado(desplazamiento)
        } else {
            acotado(vector(vieja.desplazamiento) + desplazamiento)
        }
        return zonas.toMutableList().also {
            it[encaja] = vieja.copy(
                desplazamiento = listOf(total.x, total.y, total.z),
            )
        }
    }

    /** ¿El campo resultante se puede seguir trazando con el paso que publica el shader? */
    private fun cadenaDentroDelTope(c: ContratoOrganico): Boolean = try {
        compilarConDeformaciones(c).constanteDeLipschitz() <= LIMITE_DE_CADENA
    } catch (_: Exception) {
        false
    }

    /**
     * Las tres capas sobre una esfera de mentira.
     *
     * La cota de gradiente de las deformaciones no depende de la anatomía —sale de los
     * radios y las fuerzas de las zonas—, así que medirla sobre un sólido de un
     * milímetro evita compilar doscientas partes trece veces por brochazo.
     */
    private fun compilarConDeformaciones(c: ContratoOrganico): SdfNode {
        var nodo: SdfNode = Esfera(1f)
        nodo = conPellizco(c, nodo)
        nodo = conArrastre(c, nodo)
        nodo = conAlisado(c, nodo)
        return nodo
    }

    // -------------------------------------------------------------------- validación

    private fun leer(canonico: String): ContratoOrganico? = try {
        json.decodeFromString<ContratoOrganico>(canonico)
    } catch (_: Exception) {
        null
    }

    private fun canonizar(contrato: ContratoOrganico, siFalla: String): ResultadoContratoOrganico {
        validar(contrato)?.let { return rechazo(it) }
        return try {
            compilar(contrato)
            ResultadoContratoOrganico(true, json.encodeToString(contrato), contrato.nombre)
        } catch (e: Exception) {
            rechazo(e.message ?: siFalla)
        }
    }

    private fun validar(c: ContratoOrganico): String? {
        if (c.esquema != "yunkil.organico.v1") return "esquema orgánico desconocido"
        if (c.unidades != "mm") return "las unidades deben ser mm"
        if (c.nombre.isBlank() || c.nombre.length > 80) return "el nombre no es válido"
        if (!c.fusionMm.isFinite() || c.fusionMm !in 0.2f..8f) return "fusionMm debe estar entre 0.2 y 8"
        if (c.partes.size !in 3..256) return "la figura necesita entre 3 y 256 partes"
        if (c.partes.count { it.rol == RolOrganico.CUERPO && it.modo == ModoOrganico.AGREGAR } != 1) return "debe haber exactamente un CUERPO"
        if (c.partes.count { it.rol == RolOrganico.CABEZA && it.modo == ModoOrganico.AGREGAR } != 1) return "debe haber exactamente una CABEZA"

        val ids = HashSet<String>()
        for ((i, p) in c.partes.withIndex()) {
            if (!p.id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")) || !ids.add(p.id)) {
                return "id orgánico inválido o repetido en la parte ${i + 1}"
            }
            if (p.unidoA != null && p.unidoA !in ids) return "${p.id} se une a una parte inexistente o posterior"
            if (i == 0 && (p.rol != RolOrganico.CUERPO || p.unidoA != null || p.modo != ModoOrganico.AGREGAR)) {
                return "la primera parte debe ser el CUERPO y no puede depender de otra"
            }
            if (i > 0 && p.unidoA == null) return "${p.id} está flotando: falta unidoA"
            if (p.forma == FormaOrganica.CURVA) {
                validarCurva(p)?.let { return it }
            } else {
                val listas = when (p.forma) {
                    FormaOrganica.ESFERA -> listOf(p.centro)
                    FormaOrganica.CAPSULA, FormaOrganica.TRONCO -> listOf(p.a, p.b)
                    FormaOrganica.CURVA -> emptyList()
                }
                if (listas.any { it.size != 3 || it.any { n -> !n.isFinite() || abs(n) > 1_000f } }) {
                    return "${p.id} tiene coordenadas inválidas"
                }
                val radios = when (p.forma) {
                    FormaOrganica.ESFERA, FormaOrganica.CAPSULA -> listOf(p.radio)
                    FormaOrganica.TRONCO -> listOf(p.radioA, p.radioB)
                    FormaOrganica.CURVA -> emptyList()
                }
                if (radios.any { !it.isFinite() || it !in 0.2f..300f }) return "${p.id} tiene un radio inválido"
                if (p.forma != FormaOrganica.ESFERA && (vector(p.b) - vector(p.a)).length() < 0.1f) {
                    return "${p.id} tiene extremos coincidentes"
                }
            }
        }

        validarZonas(c.alisados, "alisado", 0f, 1f)?.let { return it }
        validarZonas(c.pellizcos, "pellizco", -PELLIZCO_MAXIMO, PELLIZCO_MAXIMO)?.let { return it }
        for (z in c.pellizcos) {
            val n = vector(z.eje)
            if (z.eje.size != 3 || n.length() < 1e-4f || n.length().isNaN()) {
                return "un pellizco no declara contra qué eje aprieta"
            }
        }
        validarZonas(c.arrastres, "arrastre", 0f, 0f)?.let { return it }
        validarZonas(c.mascaras, "máscara", 0f, 1f)?.let { return it }
        val normal = vector(c.simetria.normal)
        if (c.simetria.normal.size != 3 || normal.length() < 1e-3f || !normal.length().isFinite()) {
            return "el plano de simetría no declara una normal válida"
        }
        if (!c.simetria.desplazamiento.isFinite() || abs(c.simetria.desplazamiento) > 1_000f) {
            return "el plano de simetría se ha ido de la figura"
        }
        for (z in c.arrastres) {
            val v = vector(z.desplazamiento)
            if (v.length() > ARRASTRE_MAXIMO * z.radio + 1e-3f) {
                return "un arrastre supera la mitad de su radio y rompería el material"
            }
        }

        return try {
            val cadena = compilarConDeformaciones(c).constanteDeLipschitz()
            if (cadena > LIMITE_DE_CADENA + 1e-3f) return "la escultura acumula más deformación de la trazable"
            val tamano = compilarPartes(c, comprobarContactos = false).cotas().size
            if (maxOf(tamano.x, tamano.y, tamano.z) > 600f) "la figura supera 600 mm" else null
        } catch (e: Exception) {
            e.message ?: "la figura no se puede compilar"
        }
    }

    private fun validarZonas(
        zonas: List<ZonaOrganica>,
        nombre: String,
        minimo: Float,
        maximo: Float,
    ): String? {
        if (zonas.size > MAXIMO_DE_SITIOS) return "hay más de $MAXIMO_DE_SITIOS zonas de $nombre"
        for (z in zonas) {
            if (z.centro.size != 3 || z.centro.any { !it.isFinite() || abs(it) > 1_000f }) {
                return "una zona de $nombre tiene coordenadas inválidas"
            }
            if (!z.radio.isFinite() || z.radio !in 0.5f..100f) return "una zona de $nombre tiene un radio inválido"
            if (!z.intensidad.isFinite() || z.intensidad < minimo - 1e-4f || z.intensidad > maximo + 1e-4f) {
                return "una zona de $nombre tiene una fuerza fuera de rango"
            }
            val v = z.desplazamiento
            if (v.isNotEmpty() && (v.size != 3 || v.any { !it.isFinite() || abs(it) > 1_000f })) {
                return "una zona de $nombre tiene un desplazamiento inválido"
            }
        }
        return null
    }

    // -------------------------------------------------------------------- primitivas

    private fun nodoDe(p: ParteOrganica): SdfNode = when (p.forma) {
        FormaOrganica.ESFERA -> Transformado(Esfera(p.radio), Transform(translation = vector(p.centro)))
        FormaOrganica.CAPSULA -> orientar(Capsula(p.radio, longitud(p)), vector(p.a), vector(p.b))
        FormaOrganica.TRONCO -> orientar(Cono(p.radioA, p.radioB, longitud(p)), vector(p.a), vector(p.b))
        FormaOrganica.CURVA -> cordonDe(p)
    }

    // ----------------------------------------------------------------------- curvas

    /** Puntos de control que puede llevar una curva del contrato. */
    const val MAXIMO_DE_CONTROLES = 12

    /** Muestras por tramo al suavizar. Ocho ya no se distingue de una curva continua. */
    private const val MUESTRAS_POR_TRAMO = 8

    private fun controlesDe(p: ParteOrganica): List<Vec3> =
        (0 until p.puntos.size / 3).map { i ->
            Vec3(p.puntos[i * 3], p.puntos[i * 3 + 1], p.puntos[i * 3 + 2])
        }

    /**
     * Convierte los puntos de control de una curva en el cordón que se compila.
     *
     * El suavizado pasa por Catmull-Rom, que **pasa por los puntos de control** en vez de
     * quedarse por dentro como una Bézier: cuando la IA dice que la punta de la cola está
     * en (30, 40, 0), la punta está ahí y no a ocho milímetros. A cambio la curva puede
     * abombarse un poco fuera del polígono de control en los giros cerrados, lo cual no
     * rompe nada porque las cotas del cordón se miden sobre las muestras ya calculadas.
     *
     * El grosor interpola lineal por tramo, no por spline: una spline sobre los radios
     * sobrepasaría en los giros y podría cruzar el cero, y un radio negativo no es una
     * punta afilada sino un campo del revés.
     *
     * Con dos puntos de control no se suaviza nada: una recta suavizada sigue siendo la
     * misma recta, y gastar nueve vértices en decirlo solo encarece el shader.
     */
    internal fun cordonDe(p: ParteOrganica): Cordon {
        val control = controlesDe(p)
        if (control.size == 2) return Cordon(control, p.radios)

        val tramos = control.size - 1
        val porTramo = ((Cordon.MAXIMO_DE_PUNTOS - 1) / tramos).coerceIn(1, MUESTRAS_POR_TRAMO)
        val puntos = ArrayList<Vec3>(tramos * porTramo + 1)
        val grosores = ArrayList<Float>(tramos * porTramo + 1)
        for (i in 0 until tramos) {
            // Los extremos se duplican para que el primer y el último tramo tengan
            // tangente: sin eso la curva arranca y termina con un tirón.
            val p0 = control[maxOf(i - 1, 0)]
            val p1 = control[i]
            val p2 = control[i + 1]
            val p3 = control[minOf(i + 2, control.size - 1)]
            for (k in 0 until porTramo) {
                val t = k.toFloat() / porTramo
                puntos.add(catmullRom(p0, p1, p2, p3, t))
                grosores.add(p.radios[i] + (p.radios[i + 1] - p.radios[i]) * t)
            }
        }
        puntos.add(control.last())
        grosores.add(p.radios.last())
        return Cordon(puntos, grosores)
    }

    private fun catmullRom(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Float): Vec3 {
        val t2 = t * t
        val t3 = t2 * t
        return (
            p1 * 2f +
                (p2 - p0) * t +
                (p0 * 2f - p1 * 5f + p2 * 4f - p3) * t2 +
                (p1 * 3f - p0 - p2 * 3f + p3) * t3
            ) * 0.5f
    }

    /** Lo que puede fallar en una curva y no se parece a nada de las otras formas. */
    private fun validarCurva(p: ParteOrganica): String? {
        if (p.puntos.size % 3 != 0) return "${p.id} no da los puntos de la curva en tripletes"
        val control = controlesDe(p)
        if (control.size < 2) return "${p.id} necesita al menos dos puntos de control"
        if (control.size > MAXIMO_DE_CONTROLES) {
            return "${p.id} pasa de $MAXIMO_DE_CONTROLES puntos de control"
        }
        if (p.radios.size != control.size) return "${p.id} necesita un radio por punto de control"
        if (p.puntos.any { !it.isFinite() || abs(it) > 1_000f }) {
            return "${p.id} tiene coordenadas inválidas"
        }
        // El radio puede llegar a cero: es como se afila una punta. Lo que no puede es
        // ser negativo, ni ser tan fino en todo el recorrido que no se imprima.
        if (p.radios.any { !it.isFinite() || it < 0f || it > 300f }) {
            return "${p.id} tiene un radio inválido"
        }
        if (p.radios.max() < 0.2f) return "${p.id} es demasiado fina para imprimirse"
        for (i in 0 until control.size - 1) {
            if ((control[i + 1] - control[i]).length() < 0.1f) {
                return "${p.id} repite un punto de control"
            }
        }
        return null
    }

    private fun orientar(nodo: SdfNode, a: Vec3, b: Vec3): SdfNode {
        val direccion = (b - a) / (b - a).length()
        val rotacion = desdeY(direccion)
        return Transformado(nodo, Transform(rotation = rotacion, translation = (a + b) * 0.5f))
    }

    private fun desdeY(d: Vec3): Quat {
        val producto = d.y
        if (producto < -0.999999f) return Quat.fromAxisAngle(Vec3(1f, 0f, 0f), kotlin.math.PI.toFloat())
        val cruz = Vec3(d.z, 0f, -d.x)
        val q = Quat(cruz.x, cruz.y, cruz.z, 1f + producto)
        return q.normalized()
    }

    private fun longitud(p: ParteOrganica) = (vector(p.b) - vector(p.a)).length()
    private fun vector(v: List<Float>) = if (v.size == 3) Vec3(v[0], v[1], v[2]) else Vec3.ZERO
    private fun radioDeReferencia(p: ParteOrganica) = when (p.forma) {
        FormaOrganica.ESFERA, FormaOrganica.CAPSULA -> p.radio
        FormaOrganica.TRONCO -> minOf(p.radioA, p.radioB)
        // Una punta afilada tiene radio cero en el último control y eso no es un defecto,
        // así que lo que mide el grosor de una curva es su parte más gorda.
        FormaOrganica.CURVA -> p.radios.maxOrNull() ?: 0f
    }
    private fun muestrasDe(p: ParteOrganica): List<Vec3> = when (p.forma) {
        FormaOrganica.ESFERA -> listOf(vector(p.centro))
        FormaOrganica.CAPSULA, FormaOrganica.TRONCO -> {
            val a = vector(p.a); val b = vector(p.b)
            listOf(a, b, (a + b) * 0.5f)
        }
        // Basta con que un punto de control caiga dentro del padre: la comprobación de
        // contacto pregunta por `any`, y una cola solo se une por su arranque.
        FormaOrganica.CURVA -> controlesDe(p)
    }

    // ------------------------------------------------------------------ plano espejo

    private fun punto(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

    private fun normalDeSimetria(c: ContratoOrganico) = eje(c.simetria.normal)

    /** A qué lado del plano cae un punto, y a qué distancia. */
    private fun distanciaAlPlano(c: ContratoOrganico, p: Vec3): Float =
        punto(p, normalDeSimetria(c)) - c.simetria.desplazamiento

    private fun reflejarPunto(c: ContratoOrganico, p: Vec3): Vec3 {
        val n = normalDeSimetria(c)
        return p - n * (2f * distanciaAlPlano(c, p))
    }

    /** Un vector se refleja sin el desplazamiento: una dirección no tiene posición. */
    private fun reflejarVector(c: ContratoOrganico, v: Vec3): Vec3 {
        val n = normalDeSimetria(c)
        return v - n * (2f * punto(v, n))
    }

    private fun rechazo(motivo: String) = ResultadoContratoOrganico(false, motivo = motivo)
}
