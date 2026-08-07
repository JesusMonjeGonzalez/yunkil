package yunkil.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import yunkil.doc.TipoPieza
import kotlin.math.abs

/** Un plan aceptado, o el motivo exacto por el que no lo fue. */
sealed interface ResultadoDeInterpretacion {
    data class Aceptado(val plan: PlanDeModelado, val avisos: List<String>) : ResultadoDeInterpretacion
    data class Rechazado(val motivo: String) : ResultadoDeInterpretacion
}

/**
 * Convierte lo que devuelve un modelo de lenguaje en un plan válido.
 *
 * El principio es asimétrico a propósito: **se es generoso leyendo y estricto
 * aplicando**. Un modelo que escribe `"60 mm"`, `"width"` o `"add"` en vez de
 * `60`, `anchura` o `crear` ha entendido perfectamente la tarea y no tiene sentido
 * castigarlo por la ortografía; pero nada que no encaje en el esquema llega jamás
 * al documento.
 *
 * Todo lo que se normaliza queda anotado como aviso, así que la tolerancia es
 * visible y auditable en vez de silenciosa.
 */
object Interprete {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "op"
        coerceInputValues = true
    }

    const val MAXIMO_DE_OPERACIONES = 60
    private const val MAGNITUD_MAXIMA = 100_000f

    fun interpretar(respuesta: String): ResultadoDeInterpretacion {
        val bruto = extraerJson(respuesta)
            ?: return ResultadoDeInterpretacion.Rechazado(
                "la respuesta no contiene ningún objeto JSON: " + recorte(respuesta),
            )

        val arbol = try {
            json.parseToJsonElement(bruto)
        } catch (e: Exception) {
            return ResultadoDeInterpretacion.Rechazado("el JSON está incompleto o mal formado: ${e.message}")
        }
        if (arbol !is JsonObject) {
            return ResultadoDeInterpretacion.Rechazado("se esperaba un objeto JSON con «operaciones»")
        }

        val avisos = ArrayList<String>()
        val normalizado = normalizarPlan(arbol, avisos)

        val plan = try {
            json.decodeFromJsonElement(PlanDeModelado.serializer(), normalizado)
        } catch (e: Exception) {
            return ResultadoDeInterpretacion.Rechazado(diagnosticar(e.message ?: "estructura no reconocida"))
        }

        if (plan.operaciones.isEmpty()) {
            return ResultadoDeInterpretacion.Rechazado("el plan no contiene ninguna operación")
        }
        if (plan.operaciones.size > MAXIMO_DE_OPERACIONES) {
            return ResultadoDeInterpretacion.Rechazado(
                "el plan tiene ${plan.operaciones.size} operaciones y el máximo es $MAXIMO_DE_OPERACIONES; " +
                    "agrupa la geometría en menos piezas",
            )
        }
        comprobarMagnitudes(plan)?.let { return ResultadoDeInterpretacion.Rechazado(it) }
        comprobarAlias(plan)?.let { return ResultadoDeInterpretacion.Rechazado(it) }

        return ResultadoDeInterpretacion.Aceptado(plan, avisos)
    }

    /**
     * Un plan de **edición** sobre lo que ya existe —nada de `crear`— se limita a
     * [MAXIMO_DE_EDICION] operaciones.
     *
     * Es la diferencia entre copiloto y generador: «achaflana estas aristas 0,5» se
     * usa cincuenta veces por sesión y debe acertar en la primera, mientras que
     * «hazme una carcasa» razona largo. Un plan de edición con doce operaciones no
     * está editando: está reconstruyendo, y eso ya tiene su propio canal. Rechazarlo
     * aquí es lo que mantiene al modelo en el presupuesto corto.
     */
    const val MAXIMO_DE_EDICION = 3

    fun interpretar(respuesta: String, edicion: Boolean = false): ResultadoDeInterpretacion {
        val bruto = extraerJson(respuesta)
            ?: return ResultadoDeInterpretacion.Rechazado(
                "la respuesta no contiene ningún objeto JSON: " + recorte(respuesta),
            )

        val arbol = try {
            json.parseToJsonElement(bruto)
        } catch (e: Exception) {
            return ResultadoDeInterpretacion.Rechazado("el JSON está incompleto o mal formado: ${e.message}")
        }
        if (arbol !is JsonObject) {
            return ResultadoDeInterpretacion.Rechazado("se esperaba un objeto JSON con «operaciones»")
        }

        val avisos = ArrayList<String>()
        val normalizado = normalizarPlan(arbol, avisos)

        val plan = try {
            json.decodeFromJsonElement(PlanDeModelado.serializer(), normalizado)
        } catch (e: Exception) {
            return ResultadoDeInterpretacion.Rechazado(diagnosticar(e.message ?: "estructura no reconocida"))
        }

        if (plan.operaciones.isEmpty()) {
            return ResultadoDeInterpretacion.Rechazado("el plan no contiene ninguna operación")
        }
        if (plan.operaciones.size > MAXIMO_DE_OPERACIONES) {
            return ResultadoDeInterpretacion.Rechazado(
                "el plan tiene ${plan.operaciones.size} operaciones y el máximo es $MAXIMO_DE_OPERACIONES; " +
                    "agrupa la geometría en menos piezas",
            )
        }
        if (edicion && plan.operaciones.any { it is Crear }) {
            return ResultadoDeInterpretacion.Rechazado(
                "esta es una orden de edición sobre lo ya existente y el plan intenta crear " +
                    "piezas nuevas. Si quieres una pieza nueva, dilo como «crea/haz/una pieza…»; " +
                    "si editas, limítate a las piezas que ya hay.",
            )
        }
        if (edicion && plan.operaciones.size > MAXIMO_DE_EDICION) {
            return ResultadoDeInterpretacion.Rechazado(
                "una orden de edición se limita a $MAXIMO_DE_EDICION operaciones y este plan " +
                    "trae ${plan.operaciones.size}. Simplifica: describe solo el cambio sobre la " +
                    "pieza seleccionada.",
            )
        }
        comprobarMagnitudes(plan)?.let { return ResultadoDeInterpretacion.Rechazado(it) }
        comprobarAlias(plan)?.let { return ResultadoDeInterpretacion.Rechazado(it) }

        return ResultadoDeInterpretacion.Aceptado(plan, avisos)
    }

    // ------------------------------------------------------------------ límites

    /**
     * Ninguna magnitud puede ser infinita, NaN ni astronómica. Un `NaN` colado en
     * una transformación envenena el campo entero y el viewport se queda negro sin
     * explicación; es más barato rechazarlo aquí.
     */
    private fun comprobarMagnitudes(plan: PlanDeModelado): String? {
        for (op in plan.operaciones) {
            val numeros: List<Float> = when (op) {
                is Crear -> op.parametros.values + op.posicion.orEmpty() + op.giro.orEmpty() +
                    listOfNotNull(op.escala)
                is Envolver -> op.parametros.values.toList()
                is Fijar -> listOf(op.valor)
                is Mover -> listOf(op.x, op.y, op.z)
                is Girar -> listOf(op.x, op.y, op.z)
                is Escalar -> listOf(op.factor)
                is Colocar -> listOf(op.holgura)
                else -> emptyList()
            }
            for (v in numeros) {
                if (!v.isFinite()) return "la operación «${nombreDe(op)}» contiene un número no finito"
                if (abs(v) > MAGNITUD_MAXIMA) {
                    return "la operación «${nombreDe(op)}» usa ${v} mm, fuera del rango permitido " +
                        "(±${MAGNITUD_MAXIMA.toInt()} mm)"
                }
            }
            if (op is Escalar && op.factor <= 0f) return "una escala debe ser positiva, no ${op.factor}"
            if (op is Crear && op.escala != null && op.escala <= 0f) {
                return "una escala debe ser positiva, no ${op.escala}"
            }
        }
        return null
    }

    /**
     * Un alias repetido convierte el plan en ambiguo: dos operaciones posteriores
     * apuntarían a piezas distintas según el orden. Es preferible pedir al modelo
     * que lo arregle que adivinar cuál quería.
     */
    private fun comprobarAlias(plan: PlanDeModelado): String? {
        val vistos = HashSet<String>()
        for (op in plan.operaciones) {
            val alias = when (op) {
                is Crear -> op.alias
                is Envolver -> op.alias
                is Duplicar -> op.alias
                is Taladro -> op.alias
                is Pared -> op.alias
                else -> null
            } ?: continue
            if (!vistos.add(alias)) return "el alias «$alias» está definido dos veces"
        }
        return null
    }

    private fun nombreDe(op: Operacion): String = when (op) {
        is Crear -> "crear ${op.tipo}"
        is Envolver -> "envolver ${op.objetivo}"
        is Fijar -> "fijar ${op.clave}"
        is Mover -> "mover ${op.objetivo}"
        is Girar -> "girar ${op.objetivo}"
        is Escalar -> "escalar ${op.objetivo}"
        is Acotar -> "acotar ${op.objetivo} a ${op.medida} mm en ${op.eje}"
        is Renombrar -> "renombrar ${op.objetivo}"
        is Eliminar -> "eliminar ${op.objetivo}"
        is Duplicar -> "duplicar ${op.objetivo}"
        is Colocar -> "colocar ${op.objetivo}"
        is Taladro -> "taladro ${op.designacion ?: op.diametro} en ${op.objetivo}"
        is Pared -> "pared en ${op.objetivo}"
        is Patron -> "patrón ${op.estandar} en ${op.objetivo}"
        is DefinirPerfil -> "perfil ${op.forma}"
        is Alinear -> "alinear ${op.objetivo}"
        is Asentar -> "asentar"
        is Filete -> "filete de ${op.radio} mm en ${op.objetivo}" +
            (op.contra?.let { " contra $it" } ?: "")
        is Apoyar -> "apoyar ${op.objetivo} por ${op.cara.etiqueta}"
        is Seleccionar -> "seleccionar ${op.objetivo}"
    }

    /** Traduce el error del deserializador a algo que un modelo pueda corregir. */
    private fun diagnosticar(mensaje: String): String = when {
        "Serializer for subclass" in mensaje || "polymorphic" in mensaje ||
            "class discriminator" in mensaje ->
            "hay una operación con un «op» que no existe. Los válidos son: " +
                Vocabulario.OPERACIONES.joinToString(", ") + "."
        "missing" in mensaje.lowercase() || "required" in mensaje.lowercase() ->
            "a una operación le falta un campo obligatorio: $mensaje"
        else -> "el plan no encaja en el esquema: $mensaje"
    }

    // ------------------------------------------------------------------ extracción

    /**
     * Recorta el objeto JSON de una respuesta que puede venir con vallas de markdown,
     * un preámbulo cortés o ambas cosas. Se busca el primer `{` y se equilibran las
     * llaves ignorando las que caen dentro de una cadena, porque un nombre de pieza
     * puede contener perfectamente una llave.
     */
    internal fun extraerJson(texto: String): String? {
        val inicio = texto.indexOf('{')
        if (inicio < 0) return null
        var profundidad = 0
        var enCadena = false
        var escapado = false
        for (i in inicio until texto.length) {
            val c = texto[i]
            when {
                escapado -> escapado = false
                c == '\\' && enCadena -> escapado = true
                c == '"' -> enCadena = !enCadena
                enCadena -> {}
                c == '{' -> profundidad++
                c == '}' -> {
                    profundidad--
                    if (profundidad == 0) return texto.substring(inicio, i + 1)
                }
            }
        }
        return null
    }

    private fun recorte(texto: String): String {
        val limpio = texto.trim()
        return if (limpio.isEmpty()) "respuesta vacía" else "«" + limpio.take(200) + "»"
    }

    // ------------------------------------------------------------------ normalización

    private fun normalizarPlan(objeto: JsonObject, avisos: MutableList<String>): JsonObject {
        val campos = LinkedHashMap<String, JsonElement>()
        for ((clave, valor) in objeto) {
            when (val canonica = CLAVES_DE_PLAN[clave.lowercase()] ?: clave) {
                "operaciones" -> campos["operaciones"] = buildJsonArray {
                    (valor as? JsonArray)?.forEach { elemento ->
                        (elemento as? JsonObject)?.let { add(normalizarOperacion(it, avisos)) }
                    }
                }
                "reemplazar" -> campos["reemplazar"] = JsonPrimitive(comoBooleano(valor))
                "resumen" -> campos["resumen"] = JsonPrimitive(comoTexto(valor))
                else -> campos[canonica] = valor
            }
        }
        campos.getOrPut("operaciones") { buildJsonArray {} }
        return JsonObject(campos)
    }

    private fun normalizarOperacion(objeto: JsonObject, avisos: MutableList<String>): JsonObject =
        buildJsonObject {
            for ((clave, valor) in objeto) {
                val canonica = CLAVES_DE_OPERACION[clave.lowercase()]
                    ?: CLAVES_COMPACTAS[compactar(clave.lowercase())]
                    ?: clave
                when (canonica) {
                    "op" -> {
                        val bruto = comoTexto(valor).lowercase().trim()
                        // Un modelo escribe «set_size», «setSize» y «set size» para lo
                        // mismo. Comparar sin separadores cubre las tres de una vez, en
                        // lugar de ir añadiendo variantes a la tabla cada vez que falla.
                        val resuelta = OPERACIONES_SINONIMAS[bruto]
                            ?: OPERACIONES_COMPACTAS[compactar(bruto)]
                            ?: bruto
                        if (resuelta != bruto) avisos.add("«$bruto» interpretado como «$resuelta»")
                        put("op", JsonPrimitive(resuelta))
                    }

                    "tipo" -> {
                        val bruto = comoTexto(valor).trim().uppercase()
                        val resuelta = TIPOS_SINONIMOS[bruto] ?: bruto
                        if (resuelta != bruto) avisos.add("tipo «$bruto» interpretado como «$resuelta»")
                        put("tipo", JsonPrimitive(resuelta))
                    }

                    "parametros" -> put("parametros", normalizarParametros(valor, avisos))

                    "posicion", "giro" -> put(canonica, normalizarTerna(valor))

                    "cara" -> put("cara", JsonPrimitive(normalizarEnum(valor, CARAS_SINONIMAS, "ARRIBA")))
                    "modo" -> put("modo", JsonPrimitive(normalizarEnum(valor, MODOS_SINONIMOS, "CENTRO")))
                    "eje" -> put("eje", JsonPrimitive(comoTexto(valor).trim().uppercase().take(1).ifEmpty { "Y" }))

                    "valor", "escala", "factor", "holgura", "medida", "x", "y", "z" ->
                        put(canonica, JsonPrimitive(comoNumero(valor) ?: 0f))

                    "absoluto", "centrar" -> put(canonica, JsonPrimitive(comoBooleano(valor)))

                    // Los identificadores llegan a veces con la almohadilla con la que
                    // se los presentamos al modelo; quitarla es más amable que fallar.
                    "objetivo", "referencia", "padre", "alias" ->
                        put(canonica, JsonPrimitive(comoTexto(valor).trim().removePrefix("#")))

                    else -> put(canonica, valor)
                }
            }
        }

    private fun normalizarParametros(valor: JsonElement, avisos: MutableList<String>): JsonObject =
        buildJsonObject {
            (valor as? JsonObject)?.forEach { (clave, bruto) ->
                val canonica = PARAMETROS_SINONIMOS[clave.lowercase()] ?: clave
                if (canonica != clave) avisos.add("parámetro «$clave» interpretado como «$canonica»")
                comoNumero(bruto)?.let { put(canonica, JsonPrimitive(it)) }
            }
        }

    private fun normalizarTerna(valor: JsonElement): JsonArray {
        val componentes = when (valor) {
            is JsonArray -> valor.map { comoNumero(it) ?: 0f }
            is JsonObject -> listOf("x", "y", "z").map { comoNumero(valor[it] ?: JsonPrimitive(0)) ?: 0f }
            else -> emptyList()
        }
        // Una terna corta se completa con ceros en vez de rechazarse: es un descuido
        // de escritura, no una intención distinta.
        return buildJsonArray {
            for (i in 0 until 3) add(JsonPrimitive(componentes.getOrElse(i) { 0f }))
        }
    }

    private fun normalizarEnum(valor: JsonElement, tabla: Map<String, String>, defecto: String): String {
        val bruto = comoTexto(valor).trim().lowercase()
        return tabla[bruto] ?: bruto.uppercase().ifEmpty { defecto }
    }

    // ------------------------------------------------------------------ conversión

    private fun comoTexto(valor: JsonElement): String =
        (valor as? JsonPrimitive)?.content ?: valor.toString()

    private fun comoBooleano(valor: JsonElement): Boolean {
        val texto = comoTexto(valor).trim().lowercase()
        return texto == "true" || texto == "sí" || texto == "si" || texto == "1" || texto == "yes"
    }

    /**
     * Lee un número escrito como lo escribe un humano —o un modelo entrenado con
     * humanos—: `12`, `"12"`, `"12 mm"`, `"12,5"`, `"45°"`.
     */
    internal fun comoNumero(valor: JsonElement): Float? {
        val primitivo = valor as? JsonPrimitive ?: return null
        primitivo.content.toFloatOrNull()?.let { return it }
        val limpio = primitivo.content
            .trim()
            .lowercase()
            .removeSuffix("mm")
            .removeSuffix("milímetros")
            .removeSuffix("milimetros")
            .removeSuffix("grados")
            .removeSuffix("°")
            .removeSuffix("º")
            .trim()
            // Coma decimal española: solo si no hay ya un punto, para no destrozar
            // un millar escrito como 1,234.5
            .let { if (it.count { c -> c == ',' } == 1 && '.' !in it) it.replace(',', '.') else it }
        return limpio.toFloatOrNull()
    }

    // ------------------------------------------------------------------ tablas

    private val CLAVES_DE_PLAN = mapOf(
        "operaciones" to "operaciones", "operations" to "operaciones",
        "actions" to "operaciones", "acciones" to "operaciones", "steps" to "operaciones",
        "plan" to "operaciones",
        "resumen" to "resumen", "summary" to "resumen", "descripcion" to "resumen",
        "reemplazar" to "reemplazar", "replace" to "reemplazar",
        "replacedocument" to "reemplazar", "reemplazardocumento" to "reemplazar",
    )

    /** Sinónimos indexados sin separadores, para casar `set_size` con `setsize`. */
    private val OPERACIONES_COMPACTAS by lazy {
        OPERACIONES_SINONIMAS.entries.associate { (clave, valor) -> compactar(clave) to valor }
    }

    private fun compactar(texto: String) = texto.filter { it.isLetterOrDigit() }

    private val CLAVES_DE_OPERACION = mapOf(
        "op" to "op", "kind" to "op", "accion" to "op", "acción" to "op",
        "action" to "op", "operacion" to "op", "operación" to "op", "type_of" to "op",
        "tipo" to "tipo", "type" to "tipo", "primitiva" to "tipo", "shape" to "tipo",
        "objetivo" to "objetivo", "target" to "objetivo", "id" to "objetivo",
        "pieza" to "objetivo", "piece" to "objetivo",
        "referencia" to "referencia", "reference" to "referencia", "respecto_a" to "referencia",
        "relativeto" to "referencia", "anchor" to "referencia",
        "padre" to "padre", "parent" to "padre", "dentro_de" to "padre",
        "nombre" to "nombre", "name" to "nombre", "label" to "nombre",
        "alias" to "alias", "ref" to "alias",
        "parametros" to "parametros", "parameters" to "parametros",
        "params" to "parametros", "parámetros" to "parametros", "dimensiones" to "parametros",
        "posicion" to "posicion", "position" to "posicion", "posición" to "posicion",
        "pos" to "posicion", "translation" to "posicion",
        "giro" to "giro", "rotation" to "giro", "rotacion" to "giro", "rotación" to "giro",
        "escala" to "escala", "scale" to "escala",
        "factor" to "factor",
        "medida" to "medida", "size" to "medida", "longitud" to "medida",
        "tamano" to "medida", "tamaño" to "medida", "target_size" to "medida",
        "clave" to "clave", "key" to "clave", "parametro" to "clave", "property" to "clave",
        "valor" to "valor", "value" to "valor",
        "cara" to "cara", "face" to "cara", "side" to "cara", "lado" to "cara",
        "holgura" to "holgura", "gap" to "holgura", "clearance" to "holgura",
        "separacion" to "holgura", "offset" to "holgura",
        "centrar" to "centrar", "center" to "centrar", "centered" to "centrar",
        "eje" to "eje", "axis" to "eje",
        "modo" to "modo", "mode" to "modo", "alignment" to "modo",
        "absoluto" to "absoluto", "absolute" to "absoluto",
        "forma" to "forma", "form" to "forma", "contorno" to "forma",
        "puntos" to "puntos", "points" to "puntos", "vertices" to "puntos",
        "estandar" to "estandar", "standard" to "estandar", "norma" to "estandar",
        "unidades" to "unidades", "units" to "unidades", "u" to "unidades", "rack_units" to "unidades",
        "punto" to "punto", "point" to "punto", "coordenada" to "punto",
        "en_perfil" to "punto", "posicion_en_perfil" to "punto", "profile_point" to "punto",
        "nota" to "nota", "note" to "nota", "comment" to "nota", "razon" to "nota",
        "x" to "x", "y" to "y", "z" to "z",
    )

    /** Las mismas claves sin separadores, para casar `target_size` con `targetsize`. */
    private val CLAVES_COMPACTAS by lazy {
        CLAVES_DE_OPERACION.entries.associate { (clave, valor) -> compactar(clave) to valor }
    }

    private val OPERACIONES_SINONIMAS = mapOf(
        "add" to "crear", "añadir" to "crear", "anadir" to "crear", "agregar" to "crear",
        "create" to "crear", "new" to "crear", "nueva" to "crear", "nuevo" to "crear",
        "insert" to "crear", "primitiva" to "crear",
        "wrap" to "envolver", "group" to "envolver", "agrupar" to "envolver",
        "boolean" to "envolver", "combinar" to "envolver",
        "set" to "fijar", "setparameter" to "fijar", "parametro" to "fijar",
        "update" to "fijar", "modificar" to "fijar", "cambiar" to "fijar",
        "move" to "mover", "translate" to "mover", "trasladar" to "mover", "desplazar" to "mover",
        "rotate" to "girar", "rotar" to "girar", "turn" to "girar",
        "scale" to "escalar", "resize" to "escalar", "redimensionar" to "escalar",
        "dimension" to "acotar", "dimensionar" to "acotar", "acota" to "acotar",
        "setsize" to "acotar", "sizeto" to "acotar", "scaleto" to "acotar",
        "resizeto" to "acotar", "fitto" to "acotar", "ajustar" to "acotar",
        "escalar_a" to "acotar", "medir" to "acotar",
        "rename" to "renombrar",
        "delete" to "eliminar", "remove" to "eliminar", "borrar" to "eliminar", "quitar" to "eliminar",
        "duplicate" to "duplicar", "copy" to "duplicar", "copiar" to "duplicar", "clone" to "duplicar",
        "place" to "colocar", "put" to "colocar", "poner" to "colocar", "stack" to "colocar",
        "attach" to "colocar", "pegar" to "colocar", "apoyar_en" to "colocar",
        "align" to "alinear",
        // «apoyar» ya no cae aquí: ahora es una operación propia que apoya *una cara de
        // una pieza* en el plato, y redirigirla a «asentar» tiraba su objetivo por el
        // camino y bajaba el modelo entero sin girar nada.
        "ground" to "asentar", "dropToPlate".lowercase() to "asentar",
        "al_plato" to "asentar", "bajaralplato" to "asentar",
        "fillet" to "filete", "redondear" to "filete", "chaflan" to "filete",
        "chaflán" to "filete", "acuerdo" to "filete", "roundedge" to "filete",
        "orient" to "apoyar", "orientar" to "apoyar", "layflat" to "apoyar",
        "lay_flat" to "apoyar", "tumbar" to "apoyar",
        "select" to "seleccionar",
        "pattern" to "patron", "patrón" to "patron", "montaje" to "patron",
        "mounting" to "patron", "bolt_pattern" to "patron", "estandar" to "patron",
        "sketch" to "perfil", "boceto" to "perfil", "contorno" to "perfil",
        "profile" to "perfil", "shape" to "perfil",
    )

    private val TIPOS_SINONIMOS = mapOf(
        "SPHERE" to "ESFERA", "BALL" to "ESFERA", "BOLA" to "ESFERA",
        "BOX" to "CAJA", "CUBE" to "CAJA", "CUBO" to "CAJA", "BLOCK" to "CAJA",
        "BLOQUE" to "CAJA", "PLACA" to "CAJA", "PLATE" to "CAJA", "SLAB" to "CAJA",
        "CYLINDER" to "CILINDRO", "TUBE" to "CILINDRO", "ROD" to "CILINDRO",
        "DISCO" to "CILINDRO", "DISC" to "CILINDRO",
        "SWEEP" to "BARRIDO", "TUBO" to "BARRIDO", "TUBE" to "BARRIDO",
        "PIPE" to "BARRIDO", "MARCO" to "BARRIDO", "FRAME" to "BARRIDO",
        "ARO" to "BARRIDO", "RING" to "BARRIDO", "ASA" to "BARRIDO",
        "HANDLE" to "BARRIDO", "LOFT" to "BARRIDO", "EXTRUDE_ALONG" to "BARRIDO",
        "CONE" to "CONO", "FRUSTUM" to "CONO", "TRONCO" to "CONO",
        "TORUS" to "TORO", "RING" to "TORO", "ANILLO" to "TORO", "DONUT" to "TORO",
        "CAPSULE" to "CAPSULA", "PILL" to "CAPSULA", "CÁPSULA" to "CAPSULA",
        "DIFFERENCE" to "DIFERENCIA", "SUBTRACT" to "DIFERENCIA", "RESTA" to "DIFERENCIA",
        "CUT" to "DIFERENCIA", "MINUS" to "DIFERENCIA", "SUBSTRACT" to "DIFERENCIA",
        "INTERSECTION" to "INTERSECCION", "INTERSECT" to "INTERSECCION",
        "INTERSECCIÓN" to "INTERSECCION", "COMMON" to "INTERSECCION",
        "SHELL" to "VACIADO", "HOLLOW" to "VACIADO", "CASCARA" to "VACIADO",
        "CÁSCARA" to "VACIADO", "THICKEN" to "VACIADO",
        "MIRROR" to "SIMETRIA", "SIMETRÍA" to "SIMETRIA", "ESPEJO" to "SIMETRIA",
        "ARRAY" to "REPETICION", "PATTERN" to "REPETICION", "REPEAT" to "REPETICION",
        "REPETICIÓN" to "REPETICION", "PATRON" to "REPETICION",
        "EXTRUDE" to "EXTRUSION", "EXTRUIR" to "EXTRUSION", "EXTRUSIÓN" to "EXTRUSION",
        "REVOLVE" to "REVOLUCION", "LATHE" to "REVOLUCION", "REVOLUCIÓN" to "REVOLUCION",
        "TORNEADO" to "REVOLUCION",
        "GROUP" to "UNION", "GRUPO" to "UNION", "COMBINE" to "UNION",
        "ADD" to "UNION", "UNIÓN" to "UNION", "MERGE" to "UNION",
    )

    private val PARAMETROS_SINONIMOS = mapOf(
        "width" to "anchura", "ancho" to "anchura", "anchura" to "anchura",
        "height" to "altura", "alto" to "altura", "altura" to "altura", "largo" to "altura",
        "length" to "altura",
        "depth" to "profundidad", "fondo" to "profundidad", "profundidad" to "profundidad",
        "radius" to "radio", "radio" to "radio", "r" to "radio",
        "diameter" to "radio", "diametro" to "radio", "diámetro" to "radio",
        "fillet" to "redondeo", "redondeo" to "redondeo", "corner" to "redondeo",
        "roundness" to "redondeo", "bevel" to "redondeo", "chaflan" to "redondeo",
        "thickness" to "grosor", "grosor" to "grosor", "espesor" to "grosor",
        "wall" to "grosor", "pared" to "grosor",
        "blend" to "fusion", "fusion" to "fusion", "fusión" to "fusion",
        "smooth" to "fusion", "acuerdo" to "fusion", "suavizado" to "fusion",
        "step" to "paso", "paso" to "paso", "spacing" to "paso", "separacion" to "paso",
        "majorradius" to "radioMayor", "radiomayor" to "radioMayor", "radio_mayor" to "radioMayor",
        "minorradius" to "radioMenor", "radiomenor" to "radioMenor", "radio_menor" to "radioMenor",
        "bottomradius" to "radioInferior", "radioinferior" to "radioInferior",
        "radiobase" to "radioInferior", "radio_inferior" to "radioInferior",
        "topradius" to "radioSuperior", "radiosuperior" to "radioSuperior",
        "radio_superior" to "radioSuperior",
    )

    private val CARAS_SINONIMAS = mapOf(
        "arriba" to "ARRIBA", "top" to "ARRIBA", "up" to "ARRIBA", "encima" to "ARRIBA",
        "superior" to "ARRIBA", "above" to "ARRIBA", "+y" to "ARRIBA",
        "abajo" to "ABAJO", "bottom" to "ABAJO", "down" to "ABAJO", "debajo" to "ABAJO",
        "inferior" to "ABAJO", "below" to "ABAJO", "under" to "ABAJO", "-y" to "ABAJO",
        "derecha" to "DERECHA", "right" to "DERECHA", "este" to "DERECHA", "+x" to "DERECHA",
        "izquierda" to "IZQUIERDA", "left" to "IZQUIERDA", "oeste" to "IZQUIERDA", "-x" to "IZQUIERDA",
        "delante" to "DELANTE", "front" to "DELANTE", "frente" to "DELANTE", "+z" to "DELANTE",
        "detras" to "DETRAS", "detrás" to "DETRAS", "back" to "DETRAS",
        "behind" to "DETRAS", "atras" to "DETRAS", "-z" to "DETRAS",
    )

    private val MODOS_SINONIMOS = mapOf(
        "centro" to "CENTRO", "center" to "CENTRO", "middle" to "CENTRO", "medio" to "CENTRO",
        "minimo" to "MINIMO", "mínimo" to "MINIMO", "min" to "MINIMO", "start" to "MINIMO",
        "maximo" to "MAXIMO", "máximo" to "MAXIMO", "max" to "MAXIMO", "end" to "MAXIMO",
    )

    /** Los tipos que el validador acepta de verdad, leídos del propio kernel. */
    val TIPOS_VALIDOS: List<String> get() = TipoPieza.entries.map { it.name }
}
