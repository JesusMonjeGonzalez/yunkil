package yunkil

import yunkil.doc.Documento
import yunkil.doc.Pieza
import yunkil.doc.TipoPieza
import yunkil.ia.contextoParaModelo
import yunkil.kernel.Transform
import yunkil.kernel.Vec3
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pruebas de `contextoParaModelo()`: el texto que describe el documento a un LLM
 * en cada vuelta de conversación. Su tamaño se paga en tokens en cada petición, así
 * que aquí se vigilan dos cosas a la vez y en tensión: que sea corto, y que no le
 * falte al modelo nada de lo que necesita para razonar sobre encajes.
 *
 * Los ids de las piezas se fijan a mano (no con `Pieza.nueva()`) porque ese
 * contador es un campo estático compartido por todos los tests del proceso: usarlo
 * haría que los ids —y por tanto las aserciones sobre ellos— dependieran del orden
 * en que Gradle ejecuta los ficheros.
 */
class ContextoTest {

    /**
     * El ejemplo del enunciado: una caja de 60×30×40 con cuatro taladros M3, uno
     * en cada esquina. Tres niveles (unión › diferencia › caja/cilindros), y con
     * `redondeo`/`fusion` puestos deliberadamente a su valor por defecto para que
     * las pruebas de compactado tengan algo real que quitar.
     */
    private fun cajaConCuatroTaladros(): Documento {
        val caja = Pieza(
            id = "caja-1",
            nombre = "Caja",
            tipo = TipoPieza.CAJA,
            parametros = mapOf("anchura" to 60f, "altura" to 30f, "profundidad" to 40f, "redondeo" to 2f),
        )
        fun taladro(id: String, x: Float, z: Float) = Pieza(
            id = id,
            nombre = "Taladro M3",
            tipo = TipoPieza.CILINDRO,
            parametros = mapOf("radio" to 1.9f, "altura" to 34f, "redondeo" to 0f),
            transform = Transform(translation = Vec3(x, 0f, z)),
        )
        val diferencia = Pieza(
            id = "dif-1",
            nombre = "Caja taladrada",
            tipo = TipoPieza.DIFERENCIA,
            parametros = mapOf("fusion" to 0f),
            hijos = listOf(
                caja,
                taladro("taladro-1", 20f, 15f),
                taladro("taladro-2", -20f, 15f),
                taladro("taladro-3", 20f, -15f),
                taladro("taladro-4", -20f, -15f),
            ),
        )
        val raiz = Pieza(id = "union-1", nombre = "Modelo", tipo = TipoPieza.UNION, hijos = listOf(diferencia))
        return Documento(raiz = raiz, seleccionado = "taladro-1")
    }

    // ------------------------------------------------------------------ tamaño

    @Test
    fun `el contexto del ejemplo canonico cabe en un tope de caracteres`() {
        val ctx = cajaConCuatroTaladros().contextoParaModelo()
        // Antes de compactar, este árbol exacto medía 924 caracteres. Al dejar de
        // repetir las cotas idénticas a las del padre y de imprimir parámetros en
        // su valor por defecto, bajó a 737.
        //
        // El tope está en 800 y no en 750 porque omitir un parámetro solo es lícito
        // si se puede recuperar: hizo falta una línea de cabecera que avise de que
        // lo no listado va en su valor por omisión, y publicar esos valores en el
        // catálogo de las instrucciones. Sin eso, una caja creada justo en sus cotas
        // por defecto llegaba al modelo como «#caja-3 "Bloque" CAJA», sin una sola
        // medida. Son unas decenas de caracteres fijos por contexto a cambio de que
        // el ahorro no destruya información.
        assertTrue(ctx.length < 800, "esperaba un contexto compacto, medía ${ctx.length}:\n$ctx")
    }

    // ------------------------------------------------------------------ irrenunciable: ids

    @Test
    fun `conserva el id de cada pieza`() {
        val ctx = cajaConCuatroTaladros().contextoParaModelo()
        for (id in listOf("union-1", "dif-1", "caja-1", "taladro-1", "taladro-2", "taladro-3", "taladro-4")) {
            assertTrue("#$id" in ctx, "falta la referencia #$id:\n$ctx")
        }
    }

    // ------------------------------------------------------------------ irrenunciable: nombre y tipo

    @Test
    fun `conserva el nombre y el tipo de cada pieza`() {
        val ctx = cajaConCuatroTaladros().contextoParaModelo()
        assertTrue("\"Modelo\"" in ctx && "UNION" in ctx, ctx)
        assertTrue("\"Caja taladrada\"" in ctx && "DIFERENCIA" in ctx, ctx)
        assertTrue("\"Caja\"" in ctx && "CAJA" in ctx, ctx)
        assertTrue("\"Taladro M3\"" in ctx && "CILINDRO" in ctx, ctx)
    }

    // ------------------------------------------------------------------ irrenunciable: cotas globales

    @Test
    fun `conserva las cotas del modelo completo`() {
        val ctx = cajaConCuatroTaladros().contextoParaModelo()
        assertTrue("Modelo completo: 60 × 30 × 40 mm" in ctx, ctx)
        assertTrue("x[-30..30] y[-15..15] z[-20..20]" in ctx, ctx)
    }

    @Test
    fun `dice si el modelo esta apoyado en el plato o cuanto le falta`() {
        // Este modelo no está asentado: su cara inferior queda en y=-15.
        val ctxNoApoyado = cajaConCuatroTaladros().contextoParaModelo()
        assertTrue("-15 mm del plato" in ctxNoApoyado, ctxNoApoyado)

        val cajaApoyada = Pieza(
            id = "caja-1",
            nombre = "Base",
            tipo = TipoPieza.CAJA,
            parametros = mapOf("anchura" to 20f, "altura" to 10f, "profundidad" to 20f),
            transform = Transform(translation = Vec3(0f, 5f, 0f)),
        )
        val ctxApoyado = Documento(raiz = cajaApoyada).contextoParaModelo()
        assertTrue("apoyado en el plato" in ctxApoyado, ctxApoyado)
    }

    // ------------------------------------------------------------------ irrenunciable: dimensiones reales

    @Test
    fun `conserva las dimensiones reales de cada pieza en milimetros`() {
        val ctx = cajaConCuatroTaladros().contextoParaModelo()
        assertTrue("anchura=60" in ctx, ctx)
        assertTrue("altura=30" in ctx, ctx)
        assertTrue("profundidad=40" in ctx, ctx)
        assertTrue("radio=1.9" in ctx, ctx)
        assertTrue("altura=34" in ctx, ctx)
    }

    // ------------------------------------------------------------------ irrenunciable: jerarquía

    @Test
    fun `conserva la jerarquia como indentacion creciente`() {
        val ctx = cajaConCuatroTaladros().contextoParaModelo()
        val lineas = ctx.lines()
        fun sangriaDe(idParcial: String) =
            lineas.first { "#$idParcial" in it }.takeWhile { it == ' ' }.length

        val sangriaUnion = sangriaDe("union-1")
        val sangriaDif = sangriaDe("dif-1")
        val sangriaCaja = sangriaDe("caja-1")
        val sangriaTaladro = sangriaDe("taladro-1")

        assertTrue(sangriaDif > sangriaUnion, "dif-1 debería colgar de union-1")
        assertTrue(sangriaCaja > sangriaDif, "caja-1 debería colgar de dif-1")
        assertTrue(sangriaTaladro > sangriaDif, "taladro-1 debería colgar de dif-1")
    }

    // ------------------------------------------------------------------ irrenunciable: selección

    @Test
    fun `dice cual es la pieza seleccionada`() {
        val ctx = cajaConCuatroTaladros().contextoParaModelo()
        assertTrue("Seleccionada: #taladro-1" in ctx, ctx)
    }

    // ------------------------------------------------------------------ lo que sí se puede tirar

    @Test
    fun `no repite cotas identicas a las del padre`() {
        val ctx = cajaConCuatroTaladros().contextoParaModelo()
        val lineas = ctx.lines()
        val lineaUnion = lineas.first { "#union-1" in it }
        val lineaDif = lineas.first { "#dif-1" in it }
        val lineaCaja = lineas.first { "#caja-1" in it }
        val lineaTaladro1 = lineas.first { "#taladro-1 " in it || it.trimEnd().endsWith("#taladro-1") || "#taladro-1\"" in it }

        // union-1 y dif-1 tienen exactamente las mismas cotas que el modelo
        // completo (single-child hasta la caja) y que caja-1 (restar no cambia
        // el bounding box): repetirlas en cada nivel es ruido puro.
        assertFalse("ocupa" in lineaUnion, "union-1: $lineaUnion")
        assertFalse("ocupa" in lineaDif, "dif-1: $lineaDif")
        assertFalse("ocupa" in lineaCaja, "caja-1: $lineaCaja")

        // Un taladro sí ocupa un volumen distinto al de su padre: su posición es
        // información nueva e imprescindible para razonar sobre encajes.
        assertTrue("ocupa" in lineaTaladro1, "taladro-1: $lineaTaladro1")
    }

    @Test
    fun `no imprime parametros en su valor por defecto`() {
        val ctx = cajaConCuatroTaladros().contextoParaModelo()
        // redondeo=2 es el valor por defecto de CAJA; redondeo=0 el de CILINDRO;
        // fusion=0 el de DIFERENCIA. Ninguno aporta nada si nadie lo ha tocado.
        assertFalse("redondeo=2" in ctx, ctx)
        assertFalse("redondeo=0" in ctx, ctx)
        assertFalse("fusion=0" in ctx, ctx)
    }

    // ------------------------------------------------------------------ modelo anidado

    @Test
    fun `un modelo con varios niveles de anidamiento no pierde ninguna pieza`() {
        // union > vaciado > union > caja: cuatro niveles, con dos operaciones de
        // un solo hijo seguidas para forzar que la cadena de "cotas iguales que
        // el padre" se propague más de un nivel sin romperse.
        val cajaInterior = Pieza(
            id = "caja-9",
            nombre = "Núcleo",
            tipo = TipoPieza.CAJA,
            parametros = mapOf("anchura" to 30f, "altura" to 30f, "profundidad" to 30f),
        )
        val unionInterna = Pieza(
            id = "union-8",
            nombre = "Envoltura",
            tipo = TipoPieza.UNION,
            hijos = listOf(cajaInterior),
        )
        val vaciado = Pieza(
            id = "vaciado-7",
            nombre = "Hueco",
            tipo = TipoPieza.VACIADO,
            parametros = mapOf("grosor" to 3f),
            hijos = listOf(unionInterna),
        )
        val raiz = Pieza(id = "union-6", nombre = "Modelo", tipo = TipoPieza.UNION, hijos = listOf(vaciado))
        val ctx = Documento(raiz = raiz, seleccionado = "caja-9").contextoParaModelo()

        for (id in listOf("union-6", "vaciado-7", "union-8", "caja-9")) {
            assertTrue("#$id" in ctx, "falta #$id:\n$ctx")
        }
        assertTrue("grosor=3" in ctx, "el grosor no es el de por defecto (2.4) y debe verse:\n$ctx")
        assertTrue("anchura=30" in ctx, ctx)

        val lineas = ctx.lines()
        fun sangriaDe(idParcial: String) =
            lineas.first { "#$idParcial" in it }.takeWhile { it == ' ' }.length
        assertTrue(sangriaDe("vaciado-7") > sangriaDe("union-6"))
        assertTrue(sangriaDe("union-8") > sangriaDe("vaciado-7"))
        assertTrue(sangriaDe("caja-9") > sangriaDe("union-8"))
    }

    @Test
    fun `el documento vacio sigue diciendolo con claridad`() {
        assertTrue("vacío" in Documento.vacio().contextoParaModelo())
    }
}
