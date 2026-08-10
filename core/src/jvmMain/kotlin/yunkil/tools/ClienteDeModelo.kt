package yunkil.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * El cliente que usan los bancos para hablar con un modelo compatible con OpenAI.
 *
 * Está aquí y no dentro de cada banco porque el primero que lo tuvo dentro se llevó
 * dos fallos de diagnóstico que no eran del modelo, y el segundo banco los habría
 * heredado copiados.
 */
private val jsonDelCliente = Json { ignoreUnknownKeys = true }

/**
 * El motivo del último fallo de red.
 *
 * Existe porque la primera versión devolvía `null` y se tragaba la excepción, así que
 * «el modelo no supo responder» y «el stack está caído» salían por pantalla escritos
 * exactamente igual. Con ocho «SIN RESPUESTA» seguidas delante, lo primero que uno
 * hace es dudar del modelo; era un HTTP 405 por una ruta mal formada.
 */
internal var motivoDelUltimoFallo: String? = null
    private set

/**
 * La clave de OpenCode Go, del mismo sitio del que la lee la aplicación.
 *
 * Se lee aquí y no se pide por argumento para que la clave **no acabe en el historial
 * del intérprete de órdenes** ni en una captura de pantalla del banco. Es el mismo
 * archivo y el mismo campo que usa `OpenCodeCredential` en Swift; si un día cambia de
 * sitio, cambia en los dos lados a la vez o el banco deja de medir en silencio.
 *
 * Devuelve `null` sin ruido si no hay credenciales: medir contra la nube es opcional.
 */
private fun claveDeOpenCode(): String? = try {
    val ruta = java.io.File(System.getProperty("user.home"), ".local/share/opencode/auth.json")
    if (!ruta.exists()) null
    else {
        val raiz = jsonDelCliente.parseToJsonElement(ruta.readText()).jsonObject
        val proveedor = raiz["opencode-go"]?.jsonObject
        if (proveedor?.get("type")?.jsonPrimitive?.content != "api") null
        else proveedor["key"]?.jsonPrimitive?.content
    }
} catch (e: Exception) {
    null
}

/**
 * Una petición a un modelo con visión: una imagen y una pregunta sobre ella.
 *
 * Va aparte de [pedirAlModelo] y no como un parámetro más porque el cuerpo cambia de
 * forma: con imagen, el contenido del mensaje deja de ser una cadena y pasa a ser una
 * lista de partes. Mezclar los dos caminos en una sola función obligaría a construir la
 * lista siempre, y el 95 % de las peticiones del banco no llevan imagen.
 *
 * El presupuesto es corto a propósito: lo que se le pide es un veredicto de dos líneas,
 * no un ensayo, y un crítico que se desboca cuesta el doble que el modelo que critica.
 */
internal fun pedirAlModeloConImagen(
    cliente: HttpClient,
    endpoint: String,
    modelo: String,
    sistema: String,
    imagenPng: ByteArray,
    presupuesto: Int = 600,
): String? {
    val base64 = java.util.Base64.getEncoder().encodeToString(imagenPng)
    val cuerpo = buildJsonObject {
        put("model", JsonPrimitive(modelo))
        put("temperature", JsonPrimitive(0.1))
        put("max_tokens", JsonPrimitive(presupuesto))
        put("stream", JsonPrimitive(false))
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("image_url"))
                                        put(
                                            "image_url",
                                            buildJsonObject {
                                                put("url", JsonPrimitive("data:image/png;base64,$base64"))
                                            },
                                        )
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("text"))
                                        put("text", JsonPrimitive(sistema))
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    val peticion = HttpRequest.newBuilder(URI.create(endpoint))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofMinutes(6))
        .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
        .build()

    return try {
        val respuesta = cliente.send(peticion, HttpResponse.BodyHandlers.ofString())
        if (respuesta.statusCode() !in 200..299) {
            motivoDelUltimoFallo = "HTTP ${respuesta.statusCode()}: ${respuesta.body().take(160)}"
            return null
        }
        jsonDelCliente.parseToJsonElement(respuesta.body())
            .jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
    } catch (e: Exception) {
        motivoDelUltimoFallo = "${e::class.simpleName}: ${e.message ?: "sin mensaje"}"
        null
    }
}

/**
 * Acepta tanto la base del stack como la ruta completa del endpoint.
 *
 * La documentación de los bancos enseña «127.0.0.1:9292» y el argumento se usaba tal
 * cual, lo que da 405 en todas las peticiones. Normalizar aquí cuesta tres líneas y
 * ahorra una tarde de mirar al modelo equivocado.
 */
internal fun normalizarUrl(url: String): String {
    val limpia = url.trimEnd('/')
    // Ya es el endpoint completo: no se toca.
    if (limpia.endsWith("/chat/completions")) return limpia
    // Termina en la versión de la API —`.../v1`, como la base de OpenCode Go— así que
    // solo falta la ruta del método.
    if (limpia.endsWith("/v1")) return "$limpia/chat/completions"
    // Y si no hay ruta ninguna, es la base de un stack local.
    if (URI.create(limpia).path.isNullOrEmpty()) return "$limpia/v1/chat/completions"
    return limpia
}

/**
 * Una petición al modelo. Devuelve el contenido, o `null` con el motivo en
 * [motivoDelUltimoFallo].
 *
 * `temperature` baja y no cero: a cero, un modelo que se equivoca se equivoca siempre
 * igual y las repeticiones del banco dejan de medir nada.
 */
internal fun pedirAlModelo(
    cliente: HttpClient,
    endpoint: String,
    modelo: String,
    sistema: String,
    usuario: String,
    /** Rondas anteriores: lo que respondió el modelo y lo que se midió de ello. */
    conversacion: List<Pair<String, String>> = emptyList(),
    /**
     * En local el presupuesto es también un tope de tiempo: el 9B genera a 46 tokens
     * por segundo medidos, así que 8.000 tokens son casi tres minutos cuando el modelo
     * se desboca repitiéndose.
     *
     * Aun así **se queda en 8.000**, y eso se midió. Bajarlo a 4.000 para acortar las
     * desbocadas costó dos casos del banco —la escuadra en L y el pomo— que no se
     * habían desbocado en absoluto: el 9B razona largo y necesitaba ese sitio para
     * escribir el contorno entero. Acortar la correa a todos por culpa de los que se
     * desbocan castiga justo a los planes buenos, que son los largos.
     */
    presupuesto: Int = 8000,
): String? {
    val cuerpo = buildJsonObject {
        put("model", JsonPrimitive(modelo))
        put("temperature", JsonPrimitive(0.2))
        put("max_tokens", JsonPrimitive(presupuesto))
        put("stream", JsonPrimitive(false))
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive("system"))
                        put("content", JsonPrimitive(sistema))
                    },
                )
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive(usuario))
                    },
                )
                for ((respuesta, reproche) in conversacion) {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("assistant"))
                            put("content", JsonPrimitive(respuesta))
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(reproche))
                        },
                    )
                }
            },
        )
    }

    val constructor = HttpRequest.newBuilder(URI.create(endpoint))
        .header("Content-Type", "application/json")
    // La clave solo se busca cuando el endpoint no es local. Un stack en 127.0.0.1 no
    // pide autorización, y mandarla igualmente significaría leer un fichero de
    // credenciales para nada en el caso normal.
    if (!endpoint.contains("127.0.0.1") && !endpoint.contains("localhost")) {
        claveDeOpenCode()?.let { constructor.header("Authorization", "Bearer $it") }
    }
    val peticion = constructor
        // Sin límite corto: un modelo local con razonamiento tarda medio minuto largo, y
        // cortarlo antes contaría como fallo del modelo algo que es del cliente.
        .timeout(Duration.ofMinutes(6))
        .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
        .build()

    return try {
        val respuesta = cliente.send(peticion, HttpResponse.BodyHandlers.ofString())
        if (respuesta.statusCode() !in 200..299) {
            motivoDelUltimoFallo = "HTTP ${respuesta.statusCode()}: ${respuesta.body().take(160)}"
            return null
        }
        val contenido = jsonDelCliente.parseToJsonElement(respuesta.body())
            .jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
        if (contenido == null) {
            motivoDelUltimoFallo = "respuesta sin contenido: ${respuesta.body().take(160)}"
        }
        contenido
    } catch (e: Exception) {
        motivoDelUltimoFallo = "${e::class.simpleName}: ${e.message ?: "sin mensaje"}"
        null
    }
}
