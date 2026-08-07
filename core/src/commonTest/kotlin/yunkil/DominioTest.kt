package yunkil

import kotlinx.serialization.json.Json
import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.compilar
import yunkil.fabricacion.AjusteDeTaladro
import yunkil.fabricacion.PerfilFabricacion
import yunkil.fabricacion.Roscas
import yunkil.kernel.SdfNode
import yunkil.kernel.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pruebas de las operaciones de dominio del puente IA.
 *
 * Existen porque el catálogo del kernel es geometría pura —caja, cilindro, unión— y
 * un modelo obligado a expresar «un agujero para un M3» con ese vocabulario tiene
 * que recordar de memoria que un M3 de paso son 3,4 mm, calcular el largo para que
 * atraviese, y acordarse de que una impresora deja los agujeros estrechos. Falla en
 * los tres sitios. Aquí ese conocimiento está en el núcleo, medido y con pruebas, y
 * el modelo solo tiene que decir «M3».
 */
class DominioTest {

    private fun campoDe(editor: Editor): SdfNode = assertNotNull(
        Json { ignoreUnknownKeys = true }
            .decodeFromString(Documento.serializer(), editor.aJson())
            .compilar(),
        "el documento no compila a ningún campo",
    )

    private fun aplicar(editor: Editor, json: String) {
        val leido = editor.interpretarPlan(json.trimIndent())
        val plan = assertNotNull(leido.plan, "plan no interpretado: ${leido.motivoDelRechazo}")
        val resultado = editor.aplicarPlan(plan, null)
        assertTrue(resultado.exito, "no se aplicó: ${resultado.resumen} ${resultado.omitidas}")
    }

    @Test
    fun `un taladro M3 atraviesa la pieza con el diametro normalizado`() {
        val editor = Editor(Documento.vacio())
        aplicar(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"placa","nombre":"Placa",
               "parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"taladro","objetivo":"placa","designacion":"M3"}
            ]}
            """,
        )

        val campo = campoDe(editor)

        // Atraviesa: la placa va de y=-5 a y=+5 y no queda material en el eje.
        assertTrue(campo.evaluar(Vec3(0f, 4.5f, 0f)) > 0f, "el taladro no llega arriba del todo")
        assertTrue(campo.evaluar(Vec3(0f, -4.5f, 0f)) > 0f, "el taladro no sale por abajo")

        // M3 de paso ISO 273 son 3,4 mm; con 0,2 de holgura por lado el radio es 1,9.
        assertTrue(campo.evaluar(Vec3(1.2f, 0f, 0f)) > 0f, "el agujero sale más estrecho que un M3")
        assertTrue(campo.evaluar(Vec3(2.4f, 0f, 0f)) < 0f, "el agujero sale más ancho de lo debido")
    }

    @Test
    fun `el taladro para roscar es mas estrecho que el de paso`() {
        // Es la razón de ser del ajuste: en uno el tornillo pasa, en el otro muerde.
        // Si salieran iguales, roscar en el plástico no agarraría nada.
        val perfil = PerfilFabricacion.PREDETERMINADO
        val m4 = assertNotNull(Roscas.porDesignacion("M4"))
        val paso = Roscas.diametroPara(m4, AjusteDeTaladro.PASANTE, perfil)
        val rosca = Roscas.diametroPara(m4, AjusteDeTaladro.ROSCA, perfil)

        assertTrue(rosca < m4.nominal, "la broca de roscar ($rosca) no puede llegar al nominal")
        assertTrue(paso > m4.nominal, "el agujero de paso ($paso) tiene que dejar pasar el tornillo")
    }

    @Test
    fun `el taladro admite como lo escriben los modelos`() {
        // Un modelo escribe «M3», «m3» o «3» según el día, y las tres son la misma.
        val esperado = assertNotNull(Roscas.porDesignacion("M3"))
        assertEquals(esperado, Roscas.porDesignacion("m3"))
        assertEquals(esperado, Roscas.porDesignacion("3"))
        assertEquals(esperado, Roscas.porDesignacion(" M3 "))
        assertEquals(null, Roscas.porDesignacion("M7"))
    }

    @Test
    fun `una rosca que no existe se rechaza diciendo cuales hay`() {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"placa","parametros":{"anchura":40,"altura":10,"profundidad":40}},
              {"op":"taladro","objetivo":"placa","designacion":"M7"}
            ]}
            """.trimIndent(),
        )
        val resultado = editor.aplicarPlan(assertNotNull(leido.plan), null)

        // La caja sí se creó, así que el plan «funciona»; lo que importa es que la
        // omisión salga nombrada y con el catálogo, que es lo que el modelo corrige.
        val omision = resultado.omitidas.joinToString(" ")
        assertTrue("M7" in omision, "no dice qué rosca falló: $omision")
        assertTrue("M6" in omision, "no ofrece el catálogo de roscas: $omision")
    }

    @Test
    fun `un taladro en el eje X atraviesa la pieza a lo ancho`() {
        val editor = Editor(Documento.vacio())
        aplicar(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"bloque","nombre":"Bloque",
               "parametros":{"anchura":40,"altura":20,"profundidad":20}},
              {"op":"taladro","objetivo":"bloque","designacion":"M5","eje":"X"}
            ]}
            """,
        )

        val campo = campoDe(editor)
        assertTrue(campo.evaluar(Vec3(19f, 0f, 0f)) > 0f, "no sale por la cara derecha")
        assertTrue(campo.evaluar(Vec3(-19f, 0f, 0f)) > 0f, "no sale por la cara izquierda")
        // Fuera del eje del taladro sigue habiendo material.
        assertTrue(campo.evaluar(Vec3(0f, 8f, 0f)) < 0f, "se ha comido material que no tocaba")
    }

    @Test
    fun `pared deja la caja hueca sin cambiarle las cotas exteriores`() {
        val editor = Editor(Documento.vacio())
        aplicar(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"caja","nombre":"Caja",
               "parametros":{"anchura":40,"altura":30,"profundidad":40,"redondeo":0}},
              {"op":"pared","objetivo":"caja"}
            ]}
            """,
        )

        val campo = campoDe(editor)
        val cotas = campo.cotas()

        // Lo que hace inservible al VACIADO crudo: es un cascarón centrado en la
        // superficie, así que la pieza engorda medio grosor por cada cara. Quien pide
        // «ahueca esta caja de 40» espera que siga midiendo 40.
        assertTrue(abs(cotas.size.x - 40f) < 0.05f, "la anchura pasó a ${cotas.size.x}")
        assertTrue(abs(cotas.size.y - 30f) < 0.05f, "la altura pasó a ${cotas.size.y}")
        assertTrue(abs(cotas.size.z - 40f) < 0.05f, "la profundidad pasó a ${cotas.size.z}")

        assertTrue(campo.evaluar(Vec3.ZERO) > 0f, "la caja no ha quedado hueca por dentro")
        // Justo por dentro de la cara sigue habiendo material: eso es la pared.
        assertTrue(campo.evaluar(Vec3(19.5f, 0f, 0f)) < 0f, "no hay pared pegada a la cara")
    }

    @Test
    fun `una pared por debajo del minimo imprimible se rechaza con el numero`() {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"caja","parametros":{"anchura":40,"altura":30,"profundidad":40}},
              {"op":"pared","objetivo":"caja","grosor":0.3}
            ]}
            """.trimIndent(),
        )
        val resultado = editor.aplicarPlan(assertNotNull(leido.plan), null)

        val omision = resultado.omitidas.joinToString(" ")
        assertTrue("0.3" in omision || "0,3" in omision, "no dice qué grosor se pidió: $omision")
        assertTrue("0.8" in omision || "0,8" in omision, "no dice cuál es el mínimo: $omision")
    }

    @Test
    fun `varios taladros comparten una sola diferencia`() {
        val editor = Editor(Documento.vacio())
        aplicar(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"c","nombre":"Caja",
               "parametros":{"anchura":60,"altura":30,"profundidad":40}},
              {"op":"taladro","objetivo":"c","designacion":"M3","desplazamiento":[20,15]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M3","desplazamiento":[-20,15]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M3","desplazamiento":[20,-15]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M3","desplazamiento":[-20,-15]}
            ]}
            """,
        )

        val filas = editor.filas()
        val diferencias = filas.count { it.tipo == "DIFERENCIA" }
        // Anidar una diferencia por agujero da el mismo sólido, pero deja un árbol
        // que crece en profundidad con cada taladro: ilegible para quien lo edita a
        // mano y caro en tokens para el modelo, que lo recibe entero en cada vuelta.
        assertEquals(1, diferencias, "cada taladro ha creado su propia diferencia")

        // Y el nombre no se acumula: «Caja taladrada taladrada taladrada» era el
        // síntoma visible de lo mismo.
        val nombres = filas.map { it.nombre }
        assertTrue(nombres.none { it.contains("taladrada taladrada") }, "el nombre se repite: $nombres")
    }

    @Test
    fun `un taladro nunca produce una resta que no corta`() {
        // Es la propiedad que justifica la operación entera: el largo sale de las
        // cotas de la pieza, así que el fallo que el revisor tiene que perseguir
        // cuando el modelo monta el agujero a mano aquí no puede llegar a ocurrir.
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CILINDRO","alias":"brida","nombre":"Brida",
               "parametros":{"radio":30,"altura":6}},
              {"op":"taladro","objetivo":"brida","designacion":"M5","desplazamiento":[22,0]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M5","desplazamiento":[-22,0]}
            ]}
            """.trimIndent(),
        )
        val plan = assertNotNull(leido.plan, leido.motivoDelRechazo)

        val revision = editor.revisarPlan(plan, null)

        assertTrue(revision.aceptable, "la revisión reprocha algo: ${revision.informeParaModelo}")
    }

    @Test
    fun `el desplazamiento situa el agujero fuera del centro`() {
        val editor = Editor(Documento.vacio())
        aplicar(
            editor,
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"placa","nombre":"Placa",
               "parametros":{"anchura":60,"altura":8,"profundidad":60}},
              {"op":"taladro","objetivo":"placa","designacion":"M3","desplazamiento":[20,15]}
            ]}
            """,
        )

        val campo = campoDe(editor)
        assertTrue(campo.evaluar(Vec3(20f, 0f, 15f)) > 0f, "el agujero no está donde se pidió")
        assertTrue(campo.evaluar(Vec3(0f, 0f, 0f)) < 0f, "ha taladrado el centro, que no se pidió")
    }
}
