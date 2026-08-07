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
 * Acepta tanto la base del stack como la ruta completa del endpoint.
 *
 * La documentación de los bancos enseña «127.0.0.1:9292» y el argumento se usaba tal
 * cual, lo que da 405 en todas las peticiones. Normalizar aquí cuesta tres líneas y
 * ahorra una tarde de mirar al modelo equivocado.
 */
internal fun normalizarUrl(url: String): String {
    val limpia = url.trimEnd('/')
    return if (URI.create(limpia).path.isNullOrEmpty()) "$limpia/v1/chat/completions" else limpia
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

    val peticion = HttpRequest.newBuilder(URI.create(endpoint))
        .header("Content-Type", "application/json")
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
