package yunkil.doc

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import yunkil.malla.escribirArchivo
import yunkil.malla.leerArchivo

/**
 * Plantillas de pieza reutilizables: el soporte que ya calaste, el adaptador que ya
 * funciona, el pie con las tolerancias buenas.
 *
 * Una plantilla no es un documento: es un **subárbol** con nombre, guardado fuera del
 * documento porque su sitio natural es la biblioteca de quien imprime, no la pieza
 * que tiene abierta. Al insertarla vuelve con identificadores nuevos —los que ya
 * están en el documento son sagrados— y entra como una edición transaccional más.
 */

@Serializable
data class Plantilla(
    val nombre: String,
    val pieza: Pieza,
    /**
     * Las medidas del mundo que los encajes del subárbol nombran.
     *
     * Una pieza que dice «entra en un tubo de 20 mm con calibre» no vale nada sin el
     * número: al insertar la plantilla estas medidas entran en el documento si aún no
     * están, para que el encaje siga pudiendo derivar su cota y su procedencia.
     */
    val medidas: List<Medida> = emptyList(),
)

object BibliotecaDePlantillas {

    private val formato = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Todos las plantillas del archivo, en el orden en que se guardaron. */
    fun cargar(ruta: String): List<Plantilla> {
        val texto = leerArchivo(ruta)?.decodeToString() ?: return emptyList()
        return try {
            formato.decodeFromString(ListSerializer(Plantilla.serializer()), texto)
        } catch (e: Exception) {
            // Una biblioteca corrupta no puede tumbar la aplicación: se sigue sin
            // ella y se dice. Guardar reescribe el archivo entero, así que la
            // siguiente plantilla buena lo repara de paso.
            emptyList()
        }
    }

    /** Nombres disponibles, para el menú de inserción. */
    fun nombres(ruta: String): List<String> = cargar(ruta).map { it.nombre }

    fun leer(nombre: String, ruta: String): Plantilla? =
        cargar(ruta).firstOrNull { it.nombre == nombre }

    /**
     * Guarda [pieza] como plantilla con las [medidas] que sus encajes nombran. Un
     * nombre que ya existe **se reemplaza**: la biblioteca es del usuario, y «guardar
     * mi versión buena de esto» con el mismo nombre es lo normal.
     *
     * Devuelve el motivo si no se puede, y no escribe nada en ese caso.
     */
    fun guardar(nombre: String, pieza: Pieza, ruta: String, medidas: List<Medida> = emptyList()): String? {
        if (nombre.isBlank()) return "La plantilla necesita un nombre"
        val limpias = cargar(ruta).filter { it.nombre != nombre } +
            Plantilla(nombre = nombre, pieza = pieza, medidas = medidas)
        return if (escribirArchivo(
                ruta,
                formato.encodeToString(ListSerializer(Plantilla.serializer()), limpias).encodeToByteArray(),
            )
        ) null else "No se pudo escribir $ruta"
    }

    /** Quita una plantilla de la biblioteca. */
    fun olvidar(nombre: String, ruta: String): String? {
        if (cargar(ruta).none { it.nombre == nombre }) return "No hay ninguna plantilla llamada «$nombre»"
        val restantes = cargar(ruta).filter { it.nombre != nombre }
        return if (escribirArchivo(
                ruta,
                formato.encodeToString(ListSerializer(Plantilla.serializer()), restantes).encodeToByteArray(),
            )
        ) null else "No se pudo escribir $ruta"
    }
}
