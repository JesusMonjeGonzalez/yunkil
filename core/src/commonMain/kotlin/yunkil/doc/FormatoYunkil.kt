package yunkil.doc

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import yunkil.organico.MotorOrganico

/** Única frontera de persistencia y migración del formato `.yunkil`. */
object FormatoYunkil {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun codificar(documento: Documento): String {
        require(documento.versionEsquema == VERSION_ESQUEMA_ACTUAL) {
            "No se puede guardar un documento de esquema ${documento.versionEsquema}"
        }
        return json.encodeToString(Documento.serializer(), documento)
    }

    fun decodificar(texto: String): Documento {
        var objeto = json.parseToJsonElement(texto).jsonObject
        var version = objeto["versionEsquema"]?.jsonPrimitive?.intOrNull ?: 0
        require(version >= 0) { "La versión de esquema no puede ser negativa" }
        require(version <= VERSION_ESQUEMA_ACTUAL) {
            "El archivo usa el esquema $version, pero esta versión de Yunkil solo entiende hasta el $VERSION_ESQUEMA_ACTUAL"
        }

        while (version < VERSION_ESQUEMA_ACTUAL) {
            objeto = when (version) {
                0 -> migrarDeCeroAUno(objeto)
                else -> error("No existe migración desde el esquema $version")
            }
            version++
        }

        val documento = json.decodeFromString<Documento>(objeto.toString())
        require(documento.versionEsquema == VERSION_ESQUEMA_ACTUAL) {
            "La migración terminó en el esquema ${documento.versionEsquema}"
        }

        // Que el JSON encaje en las clases no quiere decir que el documento tenga
        // sentido. Lo que sigue es la diferencia entre «se puede leer» y «se puede
        // abrir sin romper nada».
        val saneado = sanear(documento)
        validar(saneado)?.let { throw IllegalArgumentException(it) }
        Pieza.reservarIdentificadores(saneado.raiz.aplanar().map { it.first.id })
        return saneado
    }

    /**
     * Arregla lo que se puede arreglar sin cambiar la pieza.
     *
     * La frontera entre esto y [validar] es una decisión de trato con el usuario, no un
     * detalle técnico. Una selección que apunta a una pieza borrada es incoherente, sí,
     * pero no afecta a la geometría ni a nada que se pueda perder: negarse a abrir el
     * archivo por eso sería castigar a quien no ha hecho nada malo. Lo que **no** se
     * repara en silencio es cualquier cosa que cambie la identidad o la forma de la
     * pieza; eso se cuenta y se rechaza.
     */
    private fun sanear(documento: Documento): Documento {
        val seleccion = documento.seleccionado
        if (seleccion != null && documento.buscar(seleccion) == null) {
            return documento.copy(seleccionado = null)
        }
        return documento
    }

    /**
     * Lo que hace que un `.yunkil` sea un documento y no solo un JSON con la forma buena.
     *
     * Devuelve el motivo del rechazo, o `null` si el documento se puede abrir. Cada
     * comprobación está aquí porque su ausencia producía un fallo distinto y ninguno se
     * parecía a «archivo corrupto»:
     *
     *  - Dos piezas con el mismo identificador hacen que `buscar` y `mapear` acierten a
     *    la primera que encuentran. Editar una cota movía la pieza equivocada, o ninguna.
     *  - Un parámetro con NaN no falla: se propaga. La pieza desaparece del viewport, el
     *    analizador mide cero y no hay ni un mensaje que apunte a la causa.
     *  - Una MALLA sin ruta de origen no se puede volver a hornear nunca, porque el campo
     *    no viaja en el archivo. Queda una pieza que existe en el árbol y no es nada.
     *  - Un contrato orgánico inválido compila a `null` en silencio, así que la escultura
     *    se esfuma y el árbol sigue enseñando su fila.
     *  - Y por último el documento tiene que **construirse**. Es la misma condición que
     *    exige el editor a cualquier edición: un árbol que no compila no puede entrar,
     *    venga de un deslizador o de un archivo.
     */
    fun validar(documento: Documento): String? {
        val piezas = documento.raiz.aplanar().map { it.first }

        // Las medidas del mundo van antes que las piezas porque las piezas dependen de
        // ellas: sin medida no hay forma de justificar la cota de un encaje.
        val medidasVistas = HashSet<String>(documento.medidas.size)
        for (medida in documento.medidas) {
            if (medida.id.isBlank()) return "Hay una medida sin identificador"
            if (!medidasVistas.add(medida.id)) {
                return "El identificador de medida «${medida.id}» está repetido: " +
                    "una pieza encajaría contra una medida o contra la otra según el orden del archivo"
            }
            if (!medida.valor.isFinite() || medida.valor <= 0f) {
                return "La medida «${medida.nombre}» vale ${medida.valor}"
            }
        }
        for (pieza in piezas) {
            val encaje = pieza.encaje ?: continue
            if (encaje.medida !in medidasVistas) {
                // Callarse y seguir dejaría una pieza que dice encajar con algo que nadie
                // sabe cuánto mide: su cota deja de poder derivarse y de poder explicarse.
                return "«${pieza.nombre}» encaja contra la medida «${encaje.medida}», " +
                    "que no está en el archivo"
            }
        }

        val vistos = HashSet<String>(piezas.size)
        for (pieza in piezas) {
            if (pieza.id.isBlank()) return "Hay una pieza sin identificador"
            if (!vistos.add(pieza.id)) {
                return "El identificador «${pieza.id}» está repetido: el archivo está corrupto"
            }
        }

        for (pieza in piezas) {
            val malo = pieza.parametros.entries.firstOrNull { !it.value.isFinite() }
            if (malo != null) {
                return "«${pieza.nombre}» tiene el parámetro ${malo.key} en ${malo.value}"
            }
            val t = pieza.transform
            if (!t.translation.x.isFinite() || !t.translation.y.isFinite() ||
                !t.translation.z.isFinite() || !t.scale.isFinite() || t.scale <= 0f
            ) {
                return "«${pieza.nombre}» está colocada en una posición o escala imposible"
            }
            if (pieza.tipo == TipoPieza.MALLA && pieza.rutaDeMalla.isNullOrBlank()) {
                return "«${pieza.nombre}» es una malla importada y no dice de qué archivo salió"
            }
            if (pieza.tipo == TipoPieza.ESCULTURA) {
                val contrato = pieza.contratoOrganico
                if (contrato.isNullOrBlank()) return "«${pieza.nombre}» es una escultura sin contrato"
                if (MotorOrganico.nodoDeContrato(contrato) == null) {
                    return "«${pieza.nombre}» lleva un contrato orgánico que no se puede construir"
                }
            }
            if (pieza.forma == FormaDePerfil.LIBRE && pieza.puntos.size < 3) {
                return "«${pieza.nombre}» tiene un contorno libre de ${pieza.puntos.size} puntos"
            }
        }

        return try {
            documento.compilar()
            null
        } catch (e: IllegalArgumentException) {
            "El documento no se puede construir: ${e.message}"
        }
    }

    /** Los documentos anteriores al versionado ya tenían la estructura del esquema 1. */
    private fun migrarDeCeroAUno(objeto: JsonObject): JsonObject =
        JsonObject(objeto + ("versionEsquema" to JsonPrimitive(1)))
}
