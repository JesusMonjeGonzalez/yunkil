package yunkil.malla

import yunkil.kernel.Vec3
import kotlin.math.round

/**
 * Escritura de 3MF: la malla empaquetada como la quiere un laminador moderno.
 *
 * El STL no dice en qué unidades está. Es literalmente una lista de triángulos con
 * tres floats cada vértice y ni una palabra sobre si eso son milímetros, pulgadas o
 * metros; que funcione es una convención, y por eso existe el clásico modelo que
 * entra en el laminador a 1/25 de su tamaño. El 3MF **sí** lo dice —`unit="millimeter"`—
 * y además lleva orientación, transformación de colocación y metadatos.
 *
 * Y hay una razón de producto además de la técnica: Bambu Studio y OrcaSlicer abren
 * 3MF de forma nativa, que es el puente del subproyecto 4. Sin este formato, la
 * pieza llega al laminador sin saber ni cuánto mide ni por qué lado se apoya.
 *
 * ### Lo que se hace aquí y no se hace en el STL
 *
 * El mundo de Yunkil tiene la **Y hacia arriba** —«colocar arriba» sube en Y, apoyar
 * en el plato baja en Y— y el 3MF, como la impresora, tiene la **Z hacia arriba** y el
 * plato en `z = 0`. La conversión no es opcional: sin ella la pieza llega tumbada, y
 * lo que el analizador midió como voladizo deja de tener nada que ver con lo que la
 * máquina va a imprimir. Se hace con `(x, y, z) → (x, −z, y)`, que es un giro y no
 * un espejo: el determinante es +1 y el sentido de giro de los triángulos —de donde
 * sale el lado de fuera— se conserva.
 *
 * Además se traslada la pieza a coordenadas positivas con la base en `z = 0`, que es
 * donde un laminador espera encontrarla.
 */
object TresMf {

    /** El nombre de la pieza dentro del paquete. Un solo objeto, sin ensamblado. */
    private const val PARTE_MODELO = "3D/3dmodel.model"

    /**
     * El paquete 3MF completo, listo para escribir en disco.
     *
     * Un 3MF es un ZIP con tres piezas: los tipos de contenido, la relación que dice
     * cuál es el modelo principal, y el modelo. Se escriben sin comprimir porque un
     * ZIP «almacenado» es un ZIP válido para cualquier lector y ahorra meter un
     * compresor entero en un núcleo que también corre en un iPad.
     */
    fun paquete(malla: Malla, titulo: String = "Yunkil"): ByteArray = Zip.de(
        listOf(
            "[Content_Types].xml" to TIPOS_DE_CONTENIDO.encodeToByteArray(),
            "_rels/.rels" to RELACIONES.encodeToByteArray(),
            PARTE_MODELO to modelo(malla, titulo).encodeToByteArray(),
        ),
    )

    /**
     * El XML del modelo, ya en el sistema de la impresora.
     *
     * Es público porque es lo que hay que poder mirar para saber si el paquete está
     * bien: comprobar la conversión de ejes contra el texto es directo, y hacerlo
     * contra un ZIP obliga a descomprimir en una prueba que también corre en iOS.
     */
    fun modelo(malla: Malla, titulo: String = "Yunkil"): String {
        val cotas = malla.cotas()
        // A coordenadas positivas con la base en el plato: el laminador coloca el
        // objeto por su transformación de construcción, pero una pieza que llega con
        // la mitad en negativo aparece medio hundida en el plato en la vista previa.
        // La Y del 3MF sale de −Z, así que lo que la lleva a cero no es el mínimo de
        // Z sino su máximo: el eje se da la vuelta al girar.
        val desplazamiento = Vec3(-cotas.min.x, cotas.max.z, -cotas.min.y)

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<model unit=\"millimeter\" xml:lang=\"en-US\" " +
                "xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n",
        )
        sb.append(" <metadata name=\"Application\">Yunkil</metadata>\n")
        sb.append(" <metadata name=\"Title\">").append(escapar(titulo)).append("</metadata>\n")
        sb.append(" <resources>\n  <object id=\"1\" type=\"model\">\n   <mesh>\n    <vertices>\n")
        for (i in 0 until malla.numeroDeVertices) {
            val v = enPlato(malla.vertice(i), desplazamiento)
            sb.append("     <vertex x=\"").append(numero(v.x))
                .append("\" y=\"").append(numero(v.y))
                .append("\" z=\"").append(numero(v.z)).append("\"/>\n")
        }
        sb.append("    </vertices>\n    <triangles>\n")
        for (t in 0 until malla.numeroDeTriangulos) {
            sb.append("     <triangle v1=\"").append(malla.triangulos[t * 3])
                .append("\" v2=\"").append(malla.triangulos[t * 3 + 1])
                .append("\" v3=\"").append(malla.triangulos[t * 3 + 2]).append("\"/>\n")
        }
        sb.append("    </triangles>\n   </mesh>\n  </object>\n </resources>\n")
        sb.append(" <build>\n  <item objectid=\"1\"/>\n </build>\n</model>\n")
        return sb.toString()
    }

    /** Del mundo de Yunkil (Y arriba) al de la impresora (Z arriba), y a la base del plato. */
    fun enPlato(v: Vec3, desplazamiento: Vec3 = Vec3.ZERO) =
        Vec3(v.x + desplazamiento.x, -v.z + desplazamiento.y, v.y + desplazamiento.z)

    /**
     * Número con cuatro decimales y sin notación científica.
     *
     * `toString()` de un flotante pequeño da `1.0E-5`, y aunque el esquema del 3MF lo
     * admite, hay lectores que se atragantan. Cuatro decimales son 100 nm: dos órdenes
     * de magnitud por debajo de lo que cualquier impresora FDM puede poner.
     */
    private fun numero(v: Float): String {
        var n = round(v.toDouble() * 10000.0).toLong()
        if (n == 0L) return "0"
        val negativo = n < 0
        if (negativo) n = -n
        val entero = n / 10000L
        val decimales = (n % 10000L).toString().padStart(4, '0').trimEnd('0')
        val texto = if (decimales.isEmpty()) "$entero" else "$entero.$decimales"
        return if (negativo) "-$texto" else texto
    }

    private fun escapar(texto: String) = texto
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val TIPOS_DE_CONTENIDO = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
         <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
         <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
        </Types>
    """.trimIndent()

    private val RELACIONES = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
         <Relationship Id="rel0" Target="/3D/3dmodel.model" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
        </Relationships>
    """.trimIndent()
}

/**
 * El ZIP mínimo, sin comprimir.
 *
 * Se escribe a mano por lo mismo que se escribe el STL a mano: son cuarenta líneas
 * de cabeceras documentadas y la alternativa es una dependencia con implementación
 * distinta en cada plataforma del núcleo —JVM, Apple, iOS—, que es justo lo que un
 * multiplataforma no debe tener en el camino de guardar un archivo.
 */
internal object Zip {

    fun de(entradas: List<Pair<String, ByteArray>>): ByteArray {
        val salida = ArrayList<Byte>()
        val centrales = ArrayList<ByteArray>()

        for ((nombre, datos) in entradas) {
            val bytesNombre = nombre.encodeToByteArray()
            val crc = crc32(datos)
            val desplazamiento = salida.size

            salida.escribir32(0x04034b50)          // firma de cabecera local
            salida.escribir16(20)                  // versión necesaria: 2.0
            salida.escribir16(0)                   // sin banderas
            salida.escribir16(0)                   // método 0: almacenado
            salida.escribir16(0)                   // hora
            salida.escribir16(0x21)                // fecha: 1 de enero de 1980, la mínima válida
            salida.escribir32(crc)
            salida.escribir32(datos.size)
            salida.escribir32(datos.size)
            salida.escribir16(bytesNombre.size)
            salida.escribir16(0)                   // sin campo extra
            bytesNombre.forEach { salida.add(it) }
            datos.forEach { salida.add(it) }

            val central = ArrayList<Byte>()
            central.escribir32(0x02014b50)         // firma de entrada del directorio
            central.escribir16(20)                 // versión con la que se creó
            central.escribir16(20)
            central.escribir16(0)
            central.escribir16(0)
            central.escribir16(0)
            central.escribir16(0x21)
            central.escribir32(crc)
            central.escribir32(datos.size)
            central.escribir32(datos.size)
            central.escribir16(bytesNombre.size)
            central.escribir16(0)                  // extra
            central.escribir16(0)                  // comentario
            central.escribir16(0)                  // disco
            central.escribir16(0)                  // atributos internos
            central.escribir32(0)                  // atributos externos
            central.escribir32(desplazamiento)
            bytesNombre.forEach { central.add(it) }
            centrales.add(central.toByteArray())
        }

        val inicioDelDirectorio = salida.size
        centrales.forEach { entrada -> entrada.forEach { salida.add(it) } }
        val tamanoDelDirectorio = salida.size - inicioDelDirectorio

        salida.escribir32(0x06054b50)              // fin del directorio central
        salida.escribir16(0)
        salida.escribir16(0)
        salida.escribir16(entradas.size)
        salida.escribir16(entradas.size)
        salida.escribir32(tamanoDelDirectorio)
        salida.escribir32(inicioDelDirectorio)
        salida.escribir16(0)                       // sin comentario

        return salida.toByteArray()
    }

    /** CRC-32 del ZIP, el mismo polinomio reflejado de siempre. */
    fun crc32(datos: ByteArray): Int {
        var c = -1
        for (b in datos) c = TABLA[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    private val TABLA = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor -0x12477ce0 else c ushr 1 }
        c
    }

    private fun MutableList<Byte>.escribir16(v: Int) {
        add((v and 0xFF).toByte())
        add(((v ushr 8) and 0xFF).toByte())
    }

    private fun MutableList<Byte>.escribir32(v: Int) {
        add((v and 0xFF).toByte())
        add(((v ushr 8) and 0xFF).toByte())
        add(((v ushr 16) and 0xFF).toByte())
        add(((v ushr 24) and 0xFF).toByte())
    }
}
