package yunkil

import yunkil.doc.Documento
import yunkil.doc.FormatoYunkil
import yunkil.doc.Editor
import yunkil.doc.VERSION_ESQUEMA_ACTUAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormatoYunkilTest {

    @Test
    fun `un documento nuevo declara su esquema`() {
        val texto = Editor(Documento.vacio()).aJson()
        assertTrue("\"versionEsquema\": $VERSION_ESQUEMA_ACTUAL" in texto)
    }

    @Test
    fun `un documento historico sin version migra al esquema actual`() {
        val original = Editor(Documento.vacio())
        original.anadir("CAJA", null)
        val historico = original.aJson().replace(Regex("\\s*\"versionEsquema\": \\$VERSION_ESQUEMA_ACTUAL,"), "")
        val abierto = Editor(Documento.vacio())

        assertTrue(abierto.desdeJson(historico), abierto.ultimoError)
        assertEquals(original.cotaMaxima, abierto.cotaMaxima)
        assertTrue("\"versionEsquema\": $VERSION_ESQUEMA_ACTUAL" in abierto.aJson())
    }

    @Test
    fun `una version futura se rechaza sin tocar el documento abierto`() {
        val editor = Editor(Documento.vacio())
        editor.anadir("ESFERA", null)
        val antes = editor.aJson()
        val futuro = antes.replace("\"versionEsquema\": $VERSION_ESQUEMA_ACTUAL", "\"versionEsquema\": 999")

        assertFalse(editor.desdeJson(futuro))
        assertTrue(editor.ultimoError?.contains("esquema 999") == true)
        assertEquals(antes, editor.aJson())
    }

    // ------------------------------------------------------------- coherencia semántica
    //
    // Hasta aquí el formato solo comprobaba la versión y que el JSON encajara en las
    // clases. Un archivo puede cumplir las dos cosas y aun así no ser un documento: dos
    // piezas con el mismo identificador, un parámetro en NaN, una malla que no dice de
    // dónde salió. Ninguno de esos casos daba error al abrir; daban un fallo más tarde y
    // en otro sitio, que es la peor forma de fallar.

    /** Un documento con dos piezas de cotas distintas, para poder tocarle el JSON. */
    private fun conDosPiezas(): String {
        val editor = Editor(Documento.vacio())
        editor.anadir("CAJA", null)
        editor.anadir("ESFERA", null)
        return editor.aJson()
    }

    private fun abrirEsperandoRechazo(texto: String, fragmentoDelMotivo: String) {
        val editor = Editor(Documento.vacio())
        editor.anadir("CILINDRO", null)
        val antes = editor.aJson()

        assertFalse(editor.desdeJson(texto), "se abrió un documento que no debería abrirse")
        assertTrue(
            editor.ultimoError?.contains(fragmentoDelMotivo) == true,
            "el motivo no explica nada útil: ${editor.ultimoError}",
        )
        // Y lo esencial: el documento que había abierto sigue exactamente igual.
        assertEquals(antes, editor.aJson(), "abrir un archivo malo tocó el documento abierto")
    }

    @Test
    fun `dos piezas con el mismo identificador se rechazan`() {
        // `buscar` y `mapear` aciertan a la primera que encuentran, así que con un id
        // repetido editar una cota mueve la pieza equivocada y borrar una borra la otra.
        val texto = conDosPiezas()
        val ids = Regex("\"id\": \"([^\"]+)\"").findAll(texto).map { it.groupValues[1] }.toList()
        val repetido = texto.replaceFirst("\"id\": \"${ids.last()}\"", "\"id\": \"${ids[1]}\"")

        abrirEsperandoRechazo(repetido, "repetido")
    }

    @Test
    fun `un NaN en el archivo lo para el lector de JSON`() {
        // Este no llega a la validación semántica y conviene saberlo: JSON no admite NaN
        // y el lector corta antes, nombrando la ruta del campo. Se ata igual, porque el
        // comportamiento que importa —no se abre y el documento actual no se toca— es el
        // mismo, y si algún día se permitieran valores especiales al leer, esta prueba
        // avisaría de que la única defensa ha desaparecido.
        val texto = conDosPiezas().replaceFirst(Regex("\"anchura\": [0-9.]+"), "\"anchura\": NaN")
        abrirEsperandoRechazo(texto, "anchura")
    }

    @Test
    fun `un parametro que no es un numero se rechaza aunque no venga de un archivo`() {
        // La otra puerta: un documento construido en memoria —por la IA, o por cualquier
        // camino que no pase por el lector de JSON— también tiene que pasar el examen.
        // NaN no falla, se propaga: la pieza desaparece del viewport, el analizador mide
        // cero y no hay ni un mensaje que apunte a la causa.
        val editor = Editor(Documento.vacio())
        editor.anadir("CAJA", null)
        val sano = editor.documentoActual
        val caja = sano.raiz.hijos.first()
        val roto = sano.copy(
            raiz = sano.raiz.copy(
                hijos = listOf(caja.copy(parametros = caja.parametros + ("anchura" to Float.NaN))),
            ),
        )

        val motivo = FormatoYunkil.validar(roto)
        assertTrue(motivo != null && motivo.contains("anchura"), "no se detectó el NaN: $motivo")
        assertEquals(null, FormatoYunkil.validar(sano), "el documento sano no debería tener reparos")
    }

    @Test
    fun `una escala imposible se rechaza`() {
        val texto = conDosPiezas().replaceFirst("\"scale\": 1.0", "\"scale\": 0.0")
        abrirEsperandoRechazo(texto, "escala")
    }

    @Test
    fun `una malla sin archivo de origen se rechaza`() {
        // El campo horneado no viaja en el `.yunkil`: sin ruta no se puede reconstruir
        // nunca, y queda una fila en el árbol que no es nada.
        val texto = conDosPiezas().replaceFirst("\"tipo\": \"CAJA\"", "\"tipo\": \"MALLA\"")
        abrirEsperandoRechazo(texto, "archivo")
    }

    @Test
    fun `una seleccion que ya no existe se arregla en vez de impedir abrir`() {
        // Es incoherente pero no se pierde nada por arreglarlo: negarse a abrir el
        // archivo por una selección obsoleta sería castigar a quien no ha hecho nada.
        val editor = Editor(Documento.vacio())
        editor.anadir("CAJA", null)
        val texto = editor.aJson().replaceFirst(
            Regex("\"seleccionado\": \"[^\"]+\""),
            "\"seleccionado\": \"caja-inexistente\"",
        )

        val abierto = Editor(Documento.vacio())
        assertTrue(abierto.desdeJson(texto), abierto.ultimoError)
        assertEquals(null, abierto.seleccionado, "la selección fantasma debería haberse limpiado")
    }

    @Test
    fun `abrir un proyecto de otra sesion no reutiliza identificadores`() {
        // El contador de identificadores arranca en cero con el proceso. Sin reservar los
        // que ya trae el archivo, la primera pieza nueva nacía con un id que ya estaba
        // dentro del árbol, y a partir de ahí `buscar` acertaba a la que encontrara antes.
        //
        // El archivo trae un número deliberadamente altísimo. Comparar contra un proyecto
        // recién hecho no probaría nada: todas las pruebas comparten el proceso, el
        // contador ya viene alto y el choque no se reproduciría aunque el arreglo no
        // estuviera. Con 777777 el único modo de no chocar es haberlo leído del archivo.
        val hecho = Editor(Documento.vacio())
        hecho.anadir("CAJA", null)
        val idOriginal = hecho.filas()[1].id
        val proyecto = hecho.aJson().replace(idOriginal, "caja-777777")

        val abierto = Editor(Documento.vacio())
        assertTrue(abierto.desdeJson(proyecto), abierto.ultimoError)
        assertTrue("caja-777777" in abierto.filas().map { it.id }, "no se leyó el id del archivo")

        abierto.anadir("CAJA", abierto.filas().first().id)
        val ids = abierto.filas().map { it.id }
        assertEquals(ids.size, ids.toSet().size, "hay identificadores repetidos: $ids")

        val nuevo = ids.first { it != "caja-777777" && it.startsWith("caja-") }
        val numero = nuevo.substringAfterLast('-').toIntOrNull()
        assertTrue(
            numero != null && numero > 777777,
            "la pieza nueva nació con «$nuevo», por debajo de lo que ya usaba el archivo",
        )
    }

    @Test
    fun `un documento sano sigue abriendose y no cambia`() {
        // La otra mitad del trato: la validación no puede volverse un portero que
        // rechaza documentos buenos.
        val original = conDosPiezas()
        val abierto = Editor(Documento.vacio())
        assertTrue(abierto.desdeJson(original), abierto.ultimoError)
        assertEquals(original, abierto.aJson(), "abrir y guardar cambió el documento")
    }
}
