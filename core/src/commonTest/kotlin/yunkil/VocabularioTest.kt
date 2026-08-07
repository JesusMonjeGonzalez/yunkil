package yunkil

import yunkil.doc.TipoPieza
import yunkil.ia.Vocabulario
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * El prompt es código: si enseña una operación que no existe, todos los planes que
 * la usen se rechazan y el modelo no tiene forma de averiguar por qué.
 *
 * Estas pruebas atan el texto al esquema. Son baratas y cubren el fallo más tonto y
 * más caro de todos: añadir una operación al vocabulario y olvidarse de una de las
 * dos puntas, o quitarla y dejar el ejemplo escrito.
 */
class VocabularioTest {

    private val instrucciones = Vocabulario.instrucciones()

    @Test
    fun `toda operacion que enseña el prompt existe de verdad`() {
        val citadas = Regex("\"op\"\\s*:\\s*\"([a-zA-Zñáéíóú_]+)\"")
            .findAll(instrucciones)
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(citadas.isNotEmpty(), "el prompt no enseña ninguna operación")

        val desconocidas = citadas - Vocabulario.OPERACIONES.toSet()
        assertTrue(
            desconocidas.isEmpty(),
            "el prompt enseña operaciones que no existen: $desconocidas",
        )
    }

    @Test
    fun `toda operacion que existe aparece en el prompt`() {
        // El otro lado del mismo nudo: una operación que el núcleo acepta pero que
        // nadie le ha contado al modelo es una operación que no se usa nunca.
        val citadas = Regex("\"op\"\\s*:\\s*\"([a-zA-Zñáéíóú_]+)\"")
            .findAll(instrucciones)
            .map { it.groupValues[1] }
            .toSet()

        val olvidadas = Vocabulario.OPERACIONES.toSet() - citadas
        assertTrue(olvidadas.isEmpty(), "estas operaciones no se le enseñan al modelo: $olvidadas")
    }

    @Test
    fun `todo tipo de pieza que cita el prompt existe en el catalogo`() {
        val citados = Regex("\"tipo\"\\s*:\\s*\"([A-ZÁÉÍÓÚ_]+)\"")
            .findAll(instrucciones)
            .map { it.groupValues[1] }
            .toSet()

        val validos = TipoPieza.entries.map { it.name }.toSet()
        val desconocidos = citados - validos
        assertTrue(desconocidos.isEmpty(), "el prompt cita tipos que no existen: $desconocidos")
    }

    @Test
    fun `el prompt lleva los ejemplares que vienen a cuento de la peticion`() {
        // Estuvieron escritos y verificados durante días sin que nadie los inyectara:
        // el catálogo existía y el modelo no lo veía nunca. Esta prueba ata las dos
        // puntas para que no vuelva a pasar en silencio.
        val editor = yunkil.doc.Editor()
        val sinPeticion = editor.instruccionesParaModelo(null, null)
        val conPeticion = editor.instruccionesParaModelo(
            null, "unas orejas para montar esto en el rack de 19 pulgadas",
        )

        // Se comprueba con la petición del ejemplar y no con «RACK_19_OREJA», que
        // aparece también en el prompt base porque el catálogo de estándares se
        // interpola en las reglas. Un aserto que puede pasar por el motivo equivocado
        // no comprueba nada.
        val delEjemplar = "unas orejas para montar algo en mi rack de 19 pulgadas"
        assertTrue(conPeticion.length > sinPeticion.length, "no se añadió ningún ejemplar")
        assertTrue(delEjemplar in conPeticion, "debería traer el ejemplar de la oreja de rack")
        assertTrue("Ejemplos de planes correctos" in conPeticion, "faltan las instrucciones del few-shot")

        // Y una petición de otra familia trae otra cosa, no siempre lo mismo.
        val otra = editor.instruccionesParaModelo(null, "una caja con tapa para tornillos")
        assertTrue(delEjemplar !in otra, "la recuperación no está discriminando")
        assertTrue("caja con tapa" in otra, "una petición de caja debería traer el ejemplar de la caja")
    }

    @Test
    fun `el editor compone el prompt de imagen sobre el de siempre`() {
        // Lo que consume la app. Si compusiera mal, la mitad de las reglas —el
        // catálogo, las roscas, los mínimos de fabricación— desaparecería justo en
        // el caso en el que el modelo tiene menos información fiable.
        val editor = yunkil.doc.Editor()
        val base = editor.instruccionesParaModelo(null)
        val conImagen = editor.instruccionesParaModeloConImagen(null, "el ancho son 80 mm")

        assertTrue(conImagen.startsWith(base), "el prompt de imagen debe añadirse, no sustituir")
        assertTrue("el ancho son 80 mm" in conImagen, "la medida no llegó al modelo")

        // Una medida en blanco es lo mismo que no tener medida: si se colara como
        // texto vacío, el modelo leería «la medida real que te han dado es:» y nada.
        val enBlanco = editor.instruccionesParaModeloConImagen(null, "   ")
        assertTrue("no pongas «acotar»" in enBlanco, "una medida en blanco debe tratarse como ausente")
    }

    @Test
    fun `sin medida conocida el prompt de imagen prohibe acotar y pide la cota`() {
        val texto = Vocabulario.instruccionesDeImagen(null)

        assertTrue("no pongas «acotar»" in texto, "debería prohibir acotar sin medida")
        assertTrue("resumen" in texto, "debería pedir que se diga qué cota falta")
        assertTrue("NO ESTIMES MILÍMETROS" in texto, "la regla de la escala es la que sostiene todo")
    }

    @Test
    fun `con medida conocida el prompt de imagen manda cerrar con acotar`() {
        val texto = Vocabulario.instruccionesDeImagen("el ancho total son 80 mm")

        assertTrue("el ancho total son 80 mm" in texto, "la medida dada tiene que llegar al modelo")
        assertTrue("acotar" in texto, "debería mandar cerrar el plan con acotar")
        assertTrue("no pongas «acotar»" !in texto, "no puede prohibir y mandar lo mismo a la vez")
    }
}
