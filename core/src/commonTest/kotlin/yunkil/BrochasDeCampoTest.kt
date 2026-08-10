package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.compilar
import yunkil.kernel.AlisadoLocal
import yunkil.kernel.Capsula
import yunkil.kernel.Esfera
import yunkil.kernel.MoverLocal
import yunkil.kernel.PellizcoLocal
import yunkil.kernel.SdfNode
import yunkil.kernel.SitioDeArrastre
import yunkil.kernel.SitioDeMezcla
import yunkil.kernel.SitioDePellizco
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vec3
import yunkil.kernel.constanteDeLipschitz
import yunkil.organico.MotorOrganico
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Las tres brochas que no son booleanas.
 *
 * Lo que se comprueba aquí no es que exista una clase con el nombre correcto, sino que
 * cada una **hace geometría distinta** de las otras y de la unión de esferas que ya
 * había. Alisar tiene que quitar de los bultos y poner en los surcos; pellizcar tiene que
 * estrechar un relieve sin bajarlo; mover tiene que llevarse el material de un sitio a
 * otro dejándolo unido. Si las tres se pudieran escribir con una unión, sobrarían dos.
 *
 * Y las tres tienen que seguir siendo trazables: la cota de gradiente que publican es de
 * donde sale el paso del raymarcher y del picking, así que se mide contra el campo real
 * en lugar de creérsela.
 */
class BrochasDeCampoTest {

    // Una bola grande con un pegote encima: el bulto que hay que limar.
    private val conBulto: SdfNode = Union(
        Esfera(10f),
        Transformado(Esfera(2.5f), Transform(translation = Vec3(0f, 10.5f, 0f))),
        0f,
    )

    // Dos bolas que se cruzan: el surco que hay que rellenar.
    private val conSurco: SdfNode = Union(
        Transformado(Esfera(9f), Transform(translation = Vec3(-5f, 0f, 0f))),
        Transformado(Esfera(9f), Transform(translation = Vec3(5f, 0f, 0f))),
        0f,
    )

    @Test
    fun `el alisado quita del bulto, rellena el surco y no toca nada más`() {
        val limado = AlisadoLocal(
            conBulto,
            listOf(SitioDeMezcla(Vec3(0f, 11.5f, 0f), 6f, 1f)),
            paso = 2.7f,
        )
        // La punta del pegote estaba en la superficie; alisar sube el campo ahí, y subir
        // el campo es quitar material.
        val punta = Vec3(0f, 13f, 0f)
        assertTrue(abs(conBulto.evaluar(punta)) < 1e-3f, "el caso de prueba ya no roza la punta")
        assertTrue(
            limado.evaluar(punta) > 0.2f,
            "el alisado no limó el bulto: ${limado.evaluar(punta)}",
        )

        val rellenado = AlisadoLocal(
            conSurco,
            listOf(SitioDeMezcla(Vec3(0f, 7.5f, 0f), 6f, 1f)),
            paso = 2.7f,
        )
        // Justo por fuera del surco donde las dos bolas se cortan. Ahí la superficie es
        // cóncava, la media baja el campo y aparece material: es rellenar, no limar.
        val surco = Vec3(0f, 7.9f, 0f)
        assertTrue(conSurco.evaluar(surco) > 0f, "el caso de prueba ya no está fuera")
        assertTrue(
            rellenado.evaluar(surco) < 0f,
            "el alisado no rellenó el surco: ${rellenado.evaluar(surco)}",
        )
    }

    @Test
    fun `fuera de su bola el alisado devuelve el campo original sin tocar un bit`() {
        val limado = AlisadoLocal(
            conBulto,
            listOf(SitioDeMezcla(Vec3(0f, 11.5f, 0f), 6f, 1f)),
            paso = 2.7f,
        )
        // Igualdad exacta y no «parecido»: una brocha local que dejara un residuo de una
        // milésima al otro lado de la figura no sería local, sería un parámetro global mal
        // disimulado. Y el shader emite el mismo corte, así que esto también protege la
        // paridad.
        for (p in listOf(Vec3(0f, -10f, 0f), Vec3(9f, 0f, 3f), Vec3(0f, 4f, -12f))) {
            assertEquals(conBulto.evaluar(p), limado.evaluar(p), "el alisado se notó en $p")
        }
    }

    @Test
    fun `el pellizco estrecha el relieve contra su eje y no lo baja`() {
        val bola = Esfera(10f)
        val afilada = PellizcoLocal(
            bola,
            listOf(SitioDePellizco(Vec3(0f, 10f, 0f), 8f, 0.28f, Vec3(0f, 1f, 0f))),
        )

        // En el propio eje no se mueve nada: el material se aprieta contra él, no hacia
        // él en las tres direcciones. Por eso la cresta no pierde altura.
        assertEquals(0f, afilada.evaluar(Vec3(0f, 10f, 0f)), absoluteTolerance = 1e-4f)

        // A un lado, la superficie se retira hacia el eje: lo que era superficie ahora es
        // aire. Eso es estrechar el relieve.
        val flanco = Vec3(3f, 9.539f, 0f)
        assertTrue(abs(bola.evaluar(flanco)) < 1e-2f, "el caso de prueba ya no roza la esfera")
        assertTrue(
            afilada.evaluar(flanco) > 0.05f,
            "el pellizco no estrechó el flanco: ${afilada.evaluar(flanco)}",
        )

        // Con el signo cambiado hace lo contrario, que es ensanchar.
        val ensanchada = PellizcoLocal(
            bola,
            listOf(SitioDePellizco(Vec3(0f, 10f, 0f), 8f, -0.28f, Vec3(0f, 1f, 0f))),
        )
        assertTrue(
            ensanchada.evaluar(flanco) < -0.05f,
            "el pellizco negativo no ensanchó: ${ensanchada.evaluar(flanco)}",
        )
    }

    @Test
    fun `mover lleva el material de un sitio a otro sin partirlo`() {
        val barra = Capsula(radio = 5f, altura = 20f)
        // El sitio va donde se pincha, que es **sobre** la superficie: ahí el peso vale 1
        // y el material se lleva el gesto entero. Centrado en el eje interior el mismo
        // gesto apenas movería la piel, porque el peso ya ha caído al llegar a ella.
        val movida = MoverLocal(barra, listOf(SitioDeArrastre(Vec3(5f, 10f, 0f), 9f, Vec3(4f, 0f, 0f))))

        val destino = Vec3(7.5f, 10f, 0f)
        assertTrue(barra.evaluar(destino) > 0f, "el caso de prueba ya sobresalía")
        assertTrue(
            movida.evaluar(destino) < 0f,
            "el arrastre no llevó material al destino: ${movida.evaluar(destino)}",
        )

        // Y sigue siendo una sola pieza: el eje entre el cuerpo quieto y la punta movida
        // es material continuo. Si el arrastre hubiera roto la conectividad, en algún
        // punto de ese recorrido el campo saldría positivo.
        for (i in 0..40) {
            val t = i / 40f
            val p = Vec3(0f, -8f + t * 18f, 0f)
            assertTrue(movida.evaluar(p) < 0f, "el material se partió en $p")
        }
    }

    @Test
    fun `las cotas de las tres brochas no dejan material fuera`() {
        val casos = listOf(
            "alisado" to AlisadoLocal(
                conBulto, listOf(SitioDeMezcla(Vec3(0f, 11.5f, 0f), 6f, 1f)), paso = 2.7f,
            ),
            "pellizco" to PellizcoLocal(
                Esfera(10f),
                listOf(SitioDePellizco(Vec3(0f, 10f, 0f), 8f, -0.28f, Vec3(0f, 1f, 0f))),
            ),
            "mover" to MoverLocal(
                Capsula(5f, 20f), listOf(SitioDeArrastre(Vec3(5f, 10f, 0f), 9f, Vec3(4f, 0f, 0f))),
            ),
        )
        val r = Random(20260809)
        for ((nombre, nodo) in casos) {
            val cotas = nodo.cotas()
            repeat(20_000) {
                val p = Vec3(
                    (r.nextFloat() * 2f - 1f) * 30f,
                    (r.nextFloat() * 2f - 1f) * 30f,
                    (r.nextFloat() * 2f - 1f) * 30f,
                )
                if (nodo.evaluar(p) < 0f) {
                    assertTrue(
                        p.x >= cotas.min.x && p.x <= cotas.max.x &&
                            p.y >= cotas.min.y && p.y <= cotas.max.y &&
                            p.z >= cotas.min.z && p.z <= cotas.max.z,
                        "$nombre deja material en $p fuera de sus cotas",
                    )
                }
            }
        }
    }

    @Test
    fun `la cota de gradiente que publican acota el gradiente medido`() {
        // Es el número del que salen el paso del shader y el del picking. Si se quedara
        // corto, el trazado avanzaría de más y la escultura se llenaría de agujeros justo
        // donde se acaba de esculpir; si sobrara mucho, se pagaría en fotogramas. Se mide
        // sobre veinte mil puntos, que es como se cazó la cifra del acuerdo local.
        val casos = listOf(
            "alisado" to AlisadoLocal(
                conBulto, listOf(SitioDeMezcla(Vec3(0f, 11.5f, 0f), 6f, 1f)), paso = 2.7f,
            ),
            "pellizco" to PellizcoLocal(
                Esfera(10f),
                listOf(SitioDePellizco(Vec3(0f, 10f, 0f), 8f, 0.3f, Vec3(0f, 1f, 0f))),
            ),
            "mover" to MoverLocal(
                Capsula(5f, 20f), listOf(SitioDeArrastre(Vec3(5f, 10f, 0f), 9f, Vec3(4.5f, 0f, 0f))),
            ),
        )
        val r = Random(4242)
        val e = 0.01f
        for ((nombre, nodo) in casos) {
            val tope = nodo.constanteDeLipschitz()
            var peor = 0f
            repeat(20_000) {
                val p = Vec3(
                    (r.nextFloat() * 2f - 1f) * 24f,
                    (r.nextFloat() * 2f - 1f) * 24f,
                    (r.nextFloat() * 2f - 1f) * 24f,
                )
                val g = Vec3(
                    nodo.evaluar(Vec3(p.x + e, p.y, p.z)) - nodo.evaluar(Vec3(p.x - e, p.y, p.z)),
                    nodo.evaluar(Vec3(p.x, p.y + e, p.z)) - nodo.evaluar(Vec3(p.x, p.y - e, p.z)),
                    nodo.evaluar(Vec3(p.x, p.y, p.z + e)) - nodo.evaluar(Vec3(p.x, p.y, p.z - e)),
                ) / (2f * e)
                if (g.length() > peor) peor = g.length()
            }
            assertTrue(peor <= tope, "$nombre midió $peor y declara $tope")
            // Y que la cota no sobre del todo: si el campo siguiera siendo 1-Lipschitz,
            // frenar el trazado sería tirar fotogramas por nada. Las tres brochas tienen
            // que pasarse de 1 de verdad, que es lo que justifica publicar el paso.
            //
            // El margen entre lo medido y lo declarado sí puede ser amplio, y en el
            // alisado lo es: su cota supone el peor campo posible —uno cuya media se
            // aparte del original el estencil entero, que es lo que pasa en una lámina
            // más fina que él— mientras que sobre una figura maciza la media se queda en
            // la curvatura. Apretar esa cota a lo que mide una esfera dejaría el paso
            // corto justo en la geometría fina, que es donde importa.
            assertTrue(peor > 1.0f, "$nombre no llega a romper 1-Lipschitz: mide $peor")
        }
    }

    @Test
    fun `dentro de la máscara las brochas no llegan al material`() {
        val sitio = listOf(SitioDeMezcla(Vec3(0f, 11.5f, 0f), 6f, 1f))
        val punta = Vec3(0f, 13f, 0f)
        val sinMascara = AlisadoLocal(conBulto, sitio, paso = 2.7f)
        val conMascara = AlisadoLocal(
            conBulto, sitio, paso = 2.7f,
            mascara = listOf(SitioDeMezcla(punta, 3f, 1f)),
        )
        assertTrue(sinMascara.evaluar(punta) > 0.2f, "el caso de prueba ya no lima")
        // Igualdad exacta: proteger no es «alisar menos», es no alisar. Y como el peso
        // sale multiplicado por cero, el nodo ni siquiera evalúa el estencil.
        assertEquals(conBulto.evaluar(punta), conMascara.evaluar(punta))

        val gesto = listOf(SitioDeArrastre(Vec3(5f, 10f, 0f), 9f, Vec3(2f, 1.5f, 0f)))
        val barra = Capsula(5f, 20f)
        val movida = MoverLocal(barra, gesto)
        val frenada = MoverLocal(
            barra, gesto, mascara = listOf(SitioDeMezcla(Vec3(5f, 10f, 0f), 9f, 1f)),
        )
        // En el centro de la zona protegida la máscara vale exactamente 1, que es donde
        // la igualdad tiene que ser exacta y no aproximada.
        val p = Vec3(5f, 10f, 0f)
        assertTrue(abs(movida.evaluar(p) - barra.evaluar(p)) > 0.3f, "el caso de prueba ya no arrastra")
        assertEquals(barra.evaluar(p), frenada.evaluar(p))
    }

    // ------------------------------------------------------------------ de punta a punta

    private val figura = """
        {"esquema":"yunkil.organico.v1","nombre":"Mascota","unidades":"mm","fusionMm":2,
         "partes":[
          {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,5,0],"b":[0,30,0],"radio":12},
          {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,42,0],"radio":14,"unidoA":"cuerpo"},
          {"id":"ojo","rol":"OJO","forma":"ESFERA","centro":[0,45,-12],"radio":2,"unidoA":"cabeza"}
         ]}
    """.trimIndent()

    private fun editorConEscultura(): Pair<Editor, String> {
        val contrato = assertNotNull(MotorOrganico.interpretar(figura).contratoCanonico)
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadirEscultura(contrato))
        return editor to assertNotNull(editor.seleccionado)
    }

    @Test
    fun `un trazo entero de brocha es un solo deshacer y sobrevive al guardado`() {
        val (editor, id) = editorConEscultura()
        val antes = assertNotNull(editor.documentoActual.compilar()).evaluar(Vec3(0f, 56f, 0f))

        // Diez muestras seguidas, como un arrastre de verdad, dentro de una transacción.
        val punto = editor.abrirTransaccion()
        repeat(10) {
            assertTrue(
                editor.aplicarDeformacionOrganica(
                    id, "ALISAR", 0f, 56f, 0f, 8f, 0.5f, 0f, 0f, 0f,
                    simetriaX = false, continuandoTrazo = it > 0,
                ),
                editor.ultimoError,
            )
        }
        editor.cerrarTransaccion()
        assertTrue(punto !== editor.documentoActual, "el trazo no cambió el documento")

        val despues = assertNotNull(editor.documentoActual.compilar()).evaluar(Vec3(0f, 56f, 0f))
        assertTrue(despues != antes, "diez brochazos de alisado no cambiaron nada")
        // Fundir las muestras deja una zona, no diez: es lo que impide que un arrastre
        // llene el contrato y recompile el shader en cada fotograma.
        assertEquals(1, editor.zonasDeEscultura(id, "ALISAR"))

        assertTrue(editor.deshacer(), "el trazo no dejó un punto de deshacer")
        assertEquals(
            antes,
            assertNotNull(editor.documentoActual.compilar()).evaluar(Vec3(0f, 56f, 0f)),
            "un solo deshacer no devolvió el trazo entero",
        )
        // El siguiente deshacer tiene que saltarse el trazo entero y llevarse la
        // escultura: si las diez muestras hubieran dejado diez puntos, aquí seguiría
        // habiendo figura y harían falta nueve pulsaciones más.
        assertTrue(editor.deshacer(), "no queda el punto de deshacer de la propia escultura")
        assertTrue(!editor.existe(id), "el trazo dejó más de un punto de deshacer")
    }

    @Test
    fun `las tres brochas persisten en el archivo y vuelven a compilar el mismo campo`() {
        val (editor, id) = editorConEscultura()
        assertTrue(
            editor.aplicarDeformacionOrganica(
                id, "MOVER", 0f, 56f, 0f, 10f, 0f, 3f, 2f, 0f,
                simetriaX = false, continuandoTrazo = false,
            ),
            editor.ultimoError,
        )
        assertTrue(
            editor.aplicarDeformacionOrganica(
                id, "PELLIZCAR", 0f, 45f, -14f, 6f, 0.2f, 0f, 0f, -1f,
                simetriaX = false, continuandoTrazo = false,
            ),
            editor.ultimoError,
        )
        assertTrue(
            editor.aplicarDeformacionOrganica(
                id, "ALISAR", 12f, 20f, 0f, 7f, 0.6f, 0f, 0f, 0f,
                simetriaX = true, continuandoTrazo = false,
            ),
            editor.ultimoError,
        )
        assertEquals(1, editor.zonasDeEscultura(id, "MOVER"))
        assertEquals(1, editor.zonasDeEscultura(id, "PELLIZCAR"))
        assertEquals(2, editor.zonasDeEscultura(id, "ALISAR"), "la simetría no dejó la zona espejo")

        val original = assertNotNull(editor.documentoActual.compilar())
        val reabierto = Editor(Documento.vacio())
        assertTrue(reabierto.desdeJson(editor.aJson()))
        val vuelto = assertNotNull(reabierto.documentoActual.compilar())

        val r = Random(7)
        repeat(4_000) {
            val p = Vec3(
                (r.nextFloat() * 2f - 1f) * 40f,
                r.nextFloat() * 70f,
                (r.nextFloat() * 2f - 1f) * 40f,
            )
            assertEquals(original.evaluar(p), vuelto.evaluar(p), "el archivo no devolvió el mismo campo en $p")
        }
    }

    @Test
    fun `quitar las zonas de una brocha devuelve la anatomía sin rehacerla`() {
        val (editor, id) = editorConEscultura()
        val antes = assertNotNull(editor.documentoActual.compilar())
        assertTrue(
            editor.aplicarDeformacionOrganica(
                id, "MOVER", 0f, 56f, 0f, 10f, 0f, 3f, 2f, 0f,
                simetriaX = false, continuandoTrazo = false,
            ),
        )
        assertTrue(editor.limpiarDeformacionOrganica(id, "MOVER"), editor.ultimoError)
        assertEquals(0, editor.zonasDeEscultura(id, "MOVER"))
        val despues = assertNotNull(editor.documentoActual.compilar())
        assertEquals(antes.evaluar(Vec3(0f, 56f, 0f)), despues.evaluar(Vec3(0f, 56f, 0f)))
    }

    @Test
    fun `una escultura esculpida exporta una malla certificada`() {
        val (editor, id) = editorConEscultura()
        assertTrue(
            editor.aplicarDeformacionOrganica(
                id, "MOVER", 0f, 56f, 0f, 10f, 0f, 2.5f, 2f, 0f,
                simetriaX = false, continuandoTrazo = false,
            ),
        )
        assertTrue(
            editor.aplicarDeformacionOrganica(
                id, "PELLIZCAR", 0f, 45f, -14f, 6f, 0.2f, 0f, 0f, -1f,
                simetriaX = false, continuandoTrazo = false,
            ),
        )
        assertTrue(
            editor.aplicarDeformacionOrganica(
                id, "ALISAR", 0f, 30f, -12f, 8f, 0.6f, 0f, 0f, 0f,
                simetriaX = false, continuandoTrazo = false,
            ),
        )
        val certificado = editor.exportarStl("build/prueba-brochas.stl", 1.2f)
        assertNotNull(certificado)
        assertTrue(certificado.apto, certificado.resumen())
        assertTrue(certificado.triangulos > 500, certificado.resumen())
    }

    @Test
    fun `la máscara persiste, limita las brochas siguientes y se puede quitar`() {
        val (editor, id) = editorConEscultura()
        val protegido = Vec3(0f, 56f, 0f)
        val libre = Vec3(0f, 42f, -14f)
        val antesProtegido = assertNotNull(editor.documentoActual.compilar()).evaluar(protegido)
        val antesLibre = assertNotNull(editor.documentoActual.compilar()).evaluar(libre)

        assertTrue(
            editor.aplicarDeformacionOrganica(
                id, "PROTEGER", protegido.x, protegido.y, protegido.z, 7f, 1f, 0f, 0f, 0f,
                simetriaX = false, continuandoTrazo = false,
            ),
            editor.ultimoError,
        )
        assertEquals(1, editor.zonasDeEscultura(id, "PROTEGER"))

        // Alisar justo encima de lo protegido no se acepta, y se dice por qué: guardar la
        // zona igualmente dejaría una brocha muda en el contrato.
        assertTrue(
            !editor.aplicarDeformacionOrganica(
                id, "ALISAR", protegido.x, protegido.y, protegido.z, 7f, 0.8f, 0f, 0f, 0f,
                simetriaX = false, continuandoTrazo = false,
            ),
        )
        assertTrue(editor.ultimoError?.contains("protegida") == true, editor.ultimoError)

        // Lejos de la máscara la misma brocha sí entra, y lo protegido no se mueve.
        assertTrue(
            editor.aplicarDeformacionOrganica(
                id, "ALISAR", libre.x, libre.y, libre.z, 7f, 0.8f, 0f, 0f, 0f,
                simetriaX = false, continuandoTrazo = false,
            ),
            editor.ultimoError,
        )
        val despues = assertNotNull(editor.documentoActual.compilar())
        assertTrue(despues.evaluar(libre) != antesLibre, "la brocha fuera de la máscara no hizo nada")
        assertEquals(antesProtegido, despues.evaluar(protegido), "la máscara no protegió el material")

        // Y sobrevive al archivo, que es lo que la hace una máscara y no un modo del ratón.
        val reabierto = Editor(Documento.vacio())
        assertTrue(reabierto.desdeJson(editor.aJson()))
        val idReabierto = assertNotNull(reabierto.seleccionado)
        assertEquals(1, reabierto.zonasDeEscultura(idReabierto, "PROTEGER"))

        assertTrue(reabierto.limpiarDeformacionOrganica(idReabierto, "PROTEGER"), reabierto.ultimoError)
        assertEquals(0, reabierto.zonasDeEscultura(idReabierto, "PROTEGER"))
        assertTrue(
            reabierto.aplicarDeformacionOrganica(
                idReabierto, "ALISAR", protegido.x, protegido.y, protegido.z, 7f, 0.8f, 0f, 0f, 0f,
                simetriaX = false, continuandoTrazo = false,
            ),
            reabierto.ultimoError,
        )
    }

    @Test
    fun `el plano de simetría se puede cambiar y la brocha espejo lo sigue`() {
        val (editor, id) = editorConEscultura()
        // Por omisión el espejo es el de siempre —izquierda contra derecha—, así que un
        // contrato guardado antes de que el plano existiera se comporta igual que antes.
        val porOmision = editor.planoDeSimetriaDe(id)
        assertEquals(1f, porOmision[0], absoluteTolerance = 1e-4f)
        assertEquals(0f, porOmision[3], absoluteTolerance = 1e-4f)

        assertTrue(editor.fijarEjeDeSimetria(id, "Z"), editor.ultimoError)
        val frente = Vec3(0f, 42f, -14f)
        val fondo = Vec3(0f, 42f, 14f)
        val antesFondo = assertNotNull(editor.documentoActual.compilar()).evaluar(fondo)
        assertTrue(
            editor.aplicarDeformacionOrganica(
                id, "ALISAR", frente.x, frente.y, frente.z, 6f, 0.8f, 0f, 0f, 0f,
                simetriaX = true, continuandoTrazo = false,
            ),
            editor.ultimoError,
        )
        assertEquals(2, editor.zonasDeEscultura(id, "ALISAR"), "el espejo por Z no dejó su zona")
        assertTrue(
            assertNotNull(editor.documentoActual.compilar()).evaluar(fondo) != antesFondo,
            "la brocha espejo no llegó al otro lado del plano Z",
        )

        // Y un plano cualquiera, dado por una cara señalada: normal y punto, no un eje.
        assertTrue(
            editor.fijarPlanoDeSimetria(id, nx = 1f, ny = 0f, nz = 0f, px = 4f, py = 0f, pz = 0f),
            editor.ultimoError,
        )
        val oblicuo = editor.planoDeSimetriaDe(id)
        assertEquals(1f, oblicuo[0], absoluteTolerance = 1e-4f)
        assertEquals(4f, oblicuo[3], absoluteTolerance = 1e-3f)

        val reabierto = Editor(Documento.vacio())
        assertTrue(reabierto.desdeJson(editor.aJson()))
        val guardado = reabierto.planoDeSimetriaDe(assertNotNull(reabierto.seleccionado))
        assertEquals(4f, guardado[3], absoluteTolerance = 1e-3f, "el plano no sobrevivió al archivo")
    }

    @Test
    fun `el motor recorta la brocha antes de pasarse del tope de gradiente`() {
        val (editor, id) = editorConEscultura()
        // Veinte pellizcos a tope en el mismo sitio. El motor los funde y va recortando
        // la fuerza; lo que no puede pasar es que la cadena acabe con un gradiente que el
        // trazado no sabe recorrer, porque eso son agujeros en pantalla.
        repeat(20) {
            editor.aplicarDeformacionOrganica(
                id, "PELLIZCAR", 0f, 45f, -14f, 6f, 0.3f, 0f, 0f, -1f,
                simetriaX = false, continuandoTrazo = false,
            )
        }
        val nodo = assertNotNull(editor.documentoActual.compilar())
        assertTrue(
            nodo.constanteDeLipschitz() <= MotorOrganico.LIMITE_DE_CADENA + 1e-3f,
            "la escultura acabó con gradiente ${nodo.constanteDeLipschitz()}",
        )
    }
}
