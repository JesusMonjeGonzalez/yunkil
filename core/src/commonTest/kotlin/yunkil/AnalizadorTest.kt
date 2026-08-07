package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.ModelosDemo
import yunkil.doc.Pieza
import yunkil.doc.TipoPieza
import yunkil.fabricacion.AccionCorrectora
import yunkil.fabricacion.AnalizadorFdm
import yunkil.fabricacion.BuscadorDeOrientacion
import yunkil.fabricacion.PerfilFabricacion
import yunkil.fabricacion.Regla
import yunkil.fabricacion.Severidad
import yunkil.fabricacion.atribuir
import yunkil.fabricacion.hojasEnMundo
import yunkil.kernel.Caja
import yunkil.kernel.Cilindro
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalizadorTest {

    private val perfil = PerfilFabricacion.PREDETERMINADO

    private fun pieza(
        tipo: TipoPieza,
        nombre: String,
        parametros: Map<String, Float> = emptyMap(),
        transform: Transform = Transform.IDENTITY,
        hijos: List<Pieza> = emptyList(),
    ) = Pieza.nueva(tipo, nombre).let {
        it.copy(
            parametros = it.parametros + parametros,
            transform = transform,
            hijos = hijos,
        )
    }

    // ------------------------------------------------------------------ espesor

    @Test
    fun `el espesor de una losa es su grosor real`() {
        // Losa de 2 mm de canto: se mide desde la cara de arriba hacia dentro.
        val losa = Caja(Vec3(20f, 1f, 20f))
        val analizador = AnalizadorFdm(losa, perfil, resolucion = 0.5f)
        val medido = analizador.espesorEn(Vec3(0f, 1f, 0f), Vec3(0f, 1f, 0f))
        assertTrue(abs(medido - 2f) < 0.15f, "esperaba 2 mm, medí $medido")
    }

    @Test
    fun `el espesor de una pared mas fina que el paso sigue siendo correcto`() {
        // 0,3 mm es menos que la boquilla: el caso que hay que detectar sin fallar.
        val pared = Caja(Vec3(20f, 0.15f, 20f))
        val analizador = AnalizadorFdm(pared, perfil, resolucion = 0.5f)
        val medido = analizador.espesorEn(Vec3(0f, 0.15f, 0f), Vec3(0f, 1f, 0f))
        assertTrue(abs(medido - 0.3f) < 0.05f, "esperaba 0,3 mm, medí $medido")
    }

    @Test
    fun `el espesor de una varilla fina es su diametro`() {
        // 0,6 mm de diámetro: entra de lleno en la zona en la que hay que avisar.
        val varilla = Cilindro(radio = 0.3f, altura = 40f)
        val analizador = AnalizadorFdm(varilla, perfil, resolucion = 0.1f)
        val medido = analizador.espesorEn(Vec3(0.3f, 0f, 0f), Vec3(1f, 0f, 0f))
        assertTrue(abs(medido - 0.6f) < 0.05f, "esperaba 0,6 mm, medí $medido")
    }

    @Test
    fun `medir un solido grueso se detiene en cuanto deja de importar`() {
        // Medir 60 mm de macizo no aporta nada y cuesta evaluaciones: basta con saber
        // que hay pared de sobra. El tope es lo que mantiene el análisis interactivo.
        val cilindro = Cilindro(radio = 30f, altura = 40f)
        val analizador = AnalizadorFdm(cilindro, perfil, resolucion = 0.5f)
        val medido = analizador.espesorEn(Vec3(30f, 0f, 0f), Vec3(1f, 0f, 0f))
        assertTrue(medido >= perfil.grosorMinimoPared * 4f, "se quedó corto: $medido")
    }

    @Test
    fun `una aleta pegada a un bloque no se mide como el bloque`() {
        // Bloque grueso con una aleta de 1 mm encima. Medir desde la aleta debe dar
        // la aleta, no la suma: parar en el primer eje medio es justo lo que evita
        // que una pared fina desaparezca del informe por estar apoyada en algo gordo.
        val bloque = Transformado(Caja(Vec3(10f, 10f, 10f)), Transform(translation = Vec3(0f, -10f, 0f)))
        val aleta = Transformado(Caja(Vec3(10f, 5f, 0.5f)), Transform(translation = Vec3(0f, 5f, 0f)))
        val modelo = Union(bloque, aleta)
        val analizador = AnalizadorFdm(modelo, perfil, resolucion = 0.5f)
        val medido = analizador.espesorEn(Vec3(0f, 5f, 0.5f), Vec3(0f, 0f, 1f))
        assertTrue(abs(medido - 1f) < 0.15f, "esperaba 1 mm de aleta, medí $medido")
    }

    // ------------------------------------------------------------------ reglas

    @Test
    fun `un cubo asentado en el plato no dispara ningun aviso grave`() {
        val cubo = Transformado(Caja(Vec3(15f, 15f, 15f)), Transform(translation = Vec3(0f, 15f, 0f)))
        val informe = AnalizadorFdm(cubo, perfil, resolucion = 0.6f).analizar()
        assertTrue(informe.aptoParaImprimir, "avisos: ${informe.hallazgos.map { it.titulo }}")
        assertTrue(informe.metricas.areaDeContacto > 800f, "base: ${informe.metricas.areaDeContacto}")
        assertTrue(informe.metricas.fraccionEnVoladizo < 0.02f)
        assertTrue(informe.puntuacion >= 90, "puntuación ${informe.puntuacion}")
    }

    @Test
    fun `una pared de un decimo de milimetro se declara imposible`() {
        // Sin resolución forzada: el analizador debe elegirla lo bastante fina como
        // para resolver una chapa de una décima. Si no lo hace, no la ve y calla.
        val hoja = Transformado(Caja(Vec3(5f, 4f, 0.05f)), Transform(translation = Vec3(0f, 4f, 0f)))
        val informe = AnalizadorFdm(hoja, perfil).analizar()
        val aviso = informe.hallazgos.firstOrNull { it.regla == Regla.DETALLE_MINIMO }
        assertNotNull(aviso, "no detectó el detalle mínimo: ${informe.hallazgos.map { it.titulo }}")
        assertEquals(Severidad.FALLARA, aviso.severidad)
        assertTrue(aviso.medido < perfil.detalleMinimo)
        assertTrue(!informe.aptoParaImprimir)
    }

    @Test
    fun `un techo plano en el aire se detecta como voladizo y como isla`() {
        // El plato está en la cota inferior de la pieza, así que una losa suelta no
        // flota: descansa. La isla de verdad es material que no tiene *nada* en su
        // columna hasta el plato, ni siquiera lejos: aquí, una losa desplazada de
        // lado por encima de una base estrecha.
        val base = Transformado(Caja(Vec3(5f, 2f, 5f)), Transform(translation = Vec3(0f, 2f, 0f)))
        val volada = Transformado(Caja(Vec3(5f, 1f, 5f)), Transform(translation = Vec3(30f, 25f, 0f)))
        val informe = AnalizadorFdm(Union(base, volada), perfil, resolucion = 0.6f).analizar()

        val voladizo = informe.hallazgos.firstOrNull { it.regla == Regla.VOLADIZO }
        assertNotNull(voladizo, "no detectó el voladizo")
        assertTrue(voladizo.medido > 80f, "ángulo medido ${voladizo.medido}")

        val isla = informe.hallazgos.firstOrNull { it.regla == Regla.SIN_APOYO }
        assertNotNull(isla, "no detectó que arranca en el aire")
        assertEquals(Severidad.FALLARA, isla.severidad)
    }

    @Test
    fun `una torre delgada avisa de base y de esbeltez`() {
        val torre = Transformado(Cilindro(radio = 2f, altura = 80f), Transform(translation = Vec3(0f, 40f, 0f)))
        val informe = AnalizadorFdm(torre, perfil, resolucion = 0.4f).analizar()
        assertTrue(informe.hallazgos.any { it.regla == Regla.BASE_APOYO })
        assertTrue(informe.hallazgos.any { it.regla == Regla.ESBELTEZ })
    }

    @Test
    fun `una pieza mayor que la bandeja no se puede imprimir`() {
        val enorme = Caja(Vec3(200f, 200f, 200f))
        val informe = AnalizadorFdm(enorme, perfil, resolucion = 3f).analizar()
        val aviso = informe.hallazgos.firstOrNull { it.regla == Regla.VOLUMEN_DE_IMPRESION }
        assertNotNull(aviso)
        assertEquals(Severidad.FALLARA, aviso.severidad)
        val escalar = aviso.correcciones.first { it.accion == AccionCorrectora.ESCALAR_MODELO }
        assertTrue(escalar.valor < 1f, "el factor debería encoger, era ${escalar.valor}")
    }

    @Test
    fun `un umbral mas exigente cambia el veredicto sin tocar la pieza`() {
        // La misma pieza contra dos perfiles: es la prueba de que el analizador no
        // lleva constantes propias escondidas.
        val cono = yunkil.kernel.Cono(radioInferior = 2f, radioSuperior = 14f, altura = 20f)
        val modelo = Transformado(cono, Transform(translation = Vec3(0f, 10f, 0f)))
        val permisivo = perfil.copy(anguloVoladizoMaximo = 85f)
        val estricto = perfil.copy(anguloVoladizoMaximo = 20f)

        val conPermisivo = AnalizadorFdm(modelo, permisivo, resolucion = 0.5f).analizar()
        val conEstricto = AnalizadorFdm(modelo, estricto, resolucion = 0.5f).analizar()

        assertTrue(conEstricto.metricas.fraccionEnVoladizo > conPermisivo.metricas.fraccionEnVoladizo)
        assertTrue(conEstricto.hallazgos.any { it.regla == Regla.VOLADIZO })
    }

    // ------------------------------------------------------------------ atribución

    @Test
    fun `el aviso nombra la pieza que lo causa`() {
        // Tubo: cilindro exterior menos cilindro interior. La pared queda en 0,5 mm,
        // por debajo de los 0,8 que pide el perfil.
        val exterior = pieza(TipoPieza.CILINDRO, "Cuerpo", mapOf("radio" to 6f, "altura" to 20f))
        val interior = pieza(TipoPieza.CILINDRO, "Agujero del eje", mapOf("radio" to 5.5f, "altura" to 30f))
        val resta = pieza(TipoPieza.DIFERENCIA, "Tubo", hijos = listOf(exterior, interior))
        val raiz = pieza(
            TipoPieza.UNION,
            "Modelo",
            transform = Transform(translation = Vec3(0f, 10f, 0f)),
            hijos = listOf(resta),
        )
        val documento = Documento(raiz = raiz, seleccionado = raiz.id)
        val nodo = assertNotNull(documento.compilar())

        val informe = AnalizadorFdm(nodo, perfil, documento, resolucion = 0.25f).analizar()
        val aviso = informe.hallazgos.firstOrNull { it.regla == Regla.GROSOR_PARED }
        assertNotNull(aviso, "no detectó la pared fina: ${informe.hallazgos.map { it.titulo }}")
        assertTrue(abs(aviso.medido - 0.5f) < 0.15f, "pared medida ${aviso.medido}")
        assertNotNull(aviso.piezaNombre, "el aviso no nombra ninguna pieza")
        assertTrue(
            aviso.piezaNombre in setOf("Cuerpo", "Agujero del eje"),
            "atribuido a ${aviso.piezaNombre}",
        )
    }

    @Test
    fun `un sustraendo se reconoce como tal aunque este anidado`() {
        val exterior = pieza(TipoPieza.CAJA, "Bloque")
        val agujero = pieza(TipoPieza.CILINDRO, "Agujero", mapOf("radio" to 3f, "altura" to 60f))
        val resta = pieza(TipoPieza.DIFERENCIA, "Resta", hijos = listOf(exterior, agujero))
        val raiz = pieza(TipoPieza.UNION, "Modelo", hijos = listOf(resta))
        val hojas = Documento(raiz = raiz).hojasEnMundo()

        assertEquals(2, hojas.size)
        assertTrue(hojas.first { it.nombre == "Agujero" }.esSustraendo)
        assertTrue(!hojas.first { it.nombre == "Bloque" }.esSustraendo)
    }

    @Test
    fun `la atribucion respeta la transformacion acumulada de los padres`() {
        // La esfera está a 50 mm por el desplazamiento de su padre, no por el suyo.
        val esfera = pieza(TipoPieza.ESFERA, "Bolita", mapOf("radio" to 4f))
        val grupo = pieza(
            TipoPieza.UNION,
            "Grupo",
            transform = Transform(translation = Vec3(50f, 0f, 0f)),
            hijos = listOf(esfera),
        )
        val raiz = pieza(TipoPieza.UNION, "Modelo", hijos = listOf(grupo))
        val hojas = Documento(raiz = raiz).hojasEnMundo()

        assertEquals("Bolita", hojas.atribuir(Vec3(54f, 0f, 0f), 0.5f)?.nombre)
        assertNull(hojas.atribuir(Vec3(4f, 0f, 0f), 0.5f), "no hay nada en el origen")
    }

    @Test
    fun `una pieza oculta no aparece en el informe`() {
        val visible = pieza(TipoPieza.CAJA, "Visible")
        val oculta = pieza(TipoPieza.ESFERA, "Oculta").copy(visible = false)
        val raiz = pieza(TipoPieza.UNION, "Modelo", hijos = listOf(visible, oculta))
        val hojas = Documento(raiz = raiz).hojasEnMundo()
        assertEquals(listOf("Visible"), hojas.map { it.nombre })
    }

    // ------------------------------------------------------------------ orientación

    @Test
    fun `tumbar una losa vertical gana a dejarla de pie`() {
        // Una losa fina y alta: de pie apenas toca el plato, tumbada apoya entera.
        val losa = Caja(Vec3(30f, 30f, 1.5f))
        val candidatas = BuscadorDeOrientacion(losa, perfil, resolucion = 0.8f).buscar()
        assertTrue(candidatas.isNotEmpty())

        val mejor = candidatas.first()
        val actual = candidatas.first { it.esLaActual }
        assertTrue(
            mejor.areaDeContacto > actual.areaDeContacto,
            "mejor ${mejor.areaDeContacto} mm² frente a ${actual.areaDeContacto} mm² actual",
        )
        assertTrue(mejor.coste <= actual.coste)
    }

    @Test
    fun `una esfera da practicamente lo mismo en cualquier orientacion`() {
        val esfera = Esfera(12f)
        val candidatas = BuscadorDeOrientacion(esfera, perfil, resolucion = 0.8f).buscar()
        val costes = candidatas.map { it.coste }
        assertTrue(
            costes.max() - costes.min() < 6f,
            "una esfera no debería tener orientación preferida: $costes",
        )
    }

    // ------------------------------------------------------------------ correcciones

    @Test
    fun `girar el modelo lo deja asentado en el plato y se puede deshacer`() {
        val editor = Editor(
            Documento(
                raiz = pieza(
                    TipoPieza.UNION,
                    "Modelo",
                    hijos = listOf(pieza(TipoPieza.CAJA, "Losa", mapOf("anchura" to 40f, "altura" to 4f, "profundidad" to 40f))),
                ),
            ),
        )

        assertTrue(editor.girarModelo(90f, 0f))
        assertTrue(abs(editor.cotaMinima[1]) < 0.01f, "no quedó apoyada: ${editor.cotaMinima[1]}")
        // Tras girar 90° en X, los 40 mm de fondo pasan a ser los 40 mm de alto.
        assertTrue(abs(editor.cotaMaxima[1] - 40f) < 0.01f)

        // Girar y asentar es un solo punto de deshacer: al usuario le parecen una
        // sola acción y deshacerla a medias lo dejaría con la pieza atravesada.
        assertTrue(editor.deshacer())
        assertTrue(!editor.puedeDeshacer, "girar debería dejar un único punto de deshacer")
        assertTrue(abs(editor.cotaMaxima[1] - 2f) < 0.01f, "el deshacer no restauró la altura")
    }

    @Test
    fun `una correccion de parametro cambia la pieza que nombra`() {
        val vaciado = pieza(TipoPieza.VACIADO, "Cáscara", mapOf("grosor" to 0.3f), hijos = listOf(pieza(TipoPieza.ESFERA, "Bola", mapOf("radio" to 15f))))
        val editor = Editor(Documento(raiz = pieza(TipoPieza.UNION, "Modelo", hijos = listOf(vaciado))))

        val informe = assertNotNull(editor.analizarFabricacion())
        val aviso = informe.hallazgos.firstOrNull {
            it.regla == Regla.DETALLE_MINIMO || it.regla == Regla.GROSOR_PARED
        }
        assertNotNull(aviso, "una cáscara de 0,3 mm debería avisar: ${informe.hallazgos.map { it.titulo }}")

        val arreglo = aviso.correcciones.firstOrNull { it.accion == AccionCorrectora.ESCALAR_MODELO }
        assertNotNull(arreglo, "el aviso debería traer un arreglo ejecutable")
        editor.aplicarCorreccion(arreglo)
        assertNull(editor.ultimoError)
    }

    @Test
    fun `el informe se niega a analizar un documento vacio`() {
        val editor = Editor(Documento.vacio())
        assertNull(editor.analizarFabricacion())
        assertNotNull(editor.ultimoError)
    }

    @Test
    fun `los perfiles verificados son coherentes`() {
        for (p in PerfilFabricacion.VERIFICADOS) {
            assertTrue(p.grosorMinimoPared >= p.boquilla, "${p.nombre}: pared mínima menor que la boquilla")
            assertTrue(p.alturaCapa <= p.boquilla, "${p.nombre}: capa más alta que la boquilla")
            assertTrue(p.anguloVoladizoMaximo in 30f..70f, "${p.nombre}: umbral de voladizo poco creíble")
        }
        assertEquals(
            PerfilFabricacion.VERIFICADOS.size,
            PerfilFabricacion.VERIFICADOS.map { it.nombre }.toSet().size,
            "hay perfiles con el nombre repetido",
        )
    }

    @Test
    fun `un umbral editado a mano queda marcado como editado`() {
        val editado = perfil.editado { copy(anguloVoladizoMaximo = 40f) }
        assertEquals(yunkil.fabricacion.OrigenDelPerfil.EDITADO, editado.origen)
        assertEquals(40f, editado.anguloVoladizoMaximo)
    }

    @Test
    fun `la diferencia de dos solidos deja el sustraendo fuera de la superficie`() {
        // Comprobación de cordura del propio montaje: si el modelo compilado no
        // coincide con lo que el analizador cree estar midiendo, todo lo demás sobra.
        val bloque = Caja(Vec3(10f, 10f, 10f))
        val agujero = Cilindro(radio = 3f, altura = 40f)
        val modelo = Diferencia(bloque, agujero)
        assertTrue(modelo.evaluar(Vec3(0f, 0f, 0f)) > 0f, "el agujero debería estar vacío")
        assertTrue(modelo.evaluar(Vec3(12f, 0f, 0f)) > 0f, "fuera del bloque de 20 mm de lado")
        assertTrue(modelo.evaluar(Vec3(8f, 0f, 8f)) < 0f, "esquina con material")
    }

    // -------------------------------------------------------------- encuentros

    @Test
    fun `dos piezas sueltas que se solapan se denuncian con su volumen`() {
        // Dos cajas hermanas bajo una raíz sin fusión, desplazadas para que se
        // atraviesen: es el caso del montaje que se montó mal.
        val editor = Editor(
            Documento(
                raiz = pieza(
                    TipoPieza.UNION,
                    "Modelo",
                    hijos = listOf(
                        pieza(
                            TipoPieza.CAJA, "Macho",
                            mapOf("anchura" to 10f, "altura" to 10f, "profundidad" to 10f),
                        ),
                        pieza(
                            TipoPieza.CAJA, "Hembra",
                            mapOf("anchura" to 10f, "altura" to 10f, "profundidad" to 10f),
                            transform = Transform(translation = Vec3(5f, 0f, 0f)),
                        ),
                    ),
                ),
            ),
        )

        val informe = assertNotNull(editor.analizarFabricacion(nombrePerfil = perfil.nombre))
        val aviso = informe.hallazgos.firstOrNull { it.regla == Regla.INTERFERENCIA }
        assertNotNull(aviso, "el solape debería denunciarse: ${informe.hallazgos.map { it.titulo }}")
        // Dos cajas de 10 mm con 5 de solape: unos 500 mm³ (el muestreo tiene paso).
        assertTrue(aviso.medido > 300f, "volumen solapado: ${aviso.medido}")
        assertTrue(
            aviso.correcciones.any { it.accion == AccionCorrectora.MOVER_PIEZA },
            "debería ofrecer separarlas",
        )
    }

    @Test
    fun `dos piezas a menos de la holgura del perfil no entraran`() {
        // El macho queda a 0,05 mm de la hembra y el perfil pide 0,20: impreso, no
        // entra. Es lo que hoy solo se descubre imprimiendo.
        val editor = Editor(
            Documento(
                raiz = pieza(
                    TipoPieza.UNION,
                    "Modelo",
                    hijos = listOf(
                        pieza(
                            TipoPieza.CAJA, "Hembra",
                            mapOf("anchura" to 30f, "altura" to 30f, "profundidad" to 30f),
                        ),
                        pieza(
                            TipoPieza.CAJA, "Macho",
                            mapOf("anchura" to 10f, "altura" to 10f, "profundidad" to 10f),
                            transform = Transform(translation = Vec3(20.05f, 0f, 0f)),
                        ),
                    ),
                ),
            ),
        )

        val informe = assertNotNull(editor.analizarFabricacion(nombrePerfil = perfil.nombre))
        val aviso = informe.hallazgos.firstOrNull { it.regla == Regla.HOLGURA_DE_ENCAJE }
        assertNotNull(aviso, "la holgura insuficiente debería denunciarse: ${informe.hallazgos.map { it.titulo }}")
        assertTrue(aviso.medido < 0.1f, "medido: ${aviso.medido}")
        val holgar = aviso.correcciones.firstOrNull { it.accion == AccionCorrectora.MOVER_PIEZA }
        assertNotNull(holgar, "debería ofrecer holgar la pieza")
    }

    @Test
    fun `dos piezas con holgura suficiente no se denuncian`() {
        // 1 mm de separación está muy por encima de la holgura que pide el perfil.
        val editor = Editor(
            Documento(
                raiz = pieza(
                    TipoPieza.UNION,
                    "Modelo",
                    hijos = listOf(
                        pieza(
                            TipoPieza.CAJA, "Una",
                            mapOf("anchura" to 10f, "altura" to 10f, "profundidad" to 10f),
                        ),
                        pieza(
                            TipoPieza.CAJA, "Otra",
                            mapOf("anchura" to 10f, "altura" to 10f, "profundidad" to 10f),
                            transform = Transform(translation = Vec3(30f, 0f, 0f)),
                        ),
                    ),
                ),
            ),
        )

        val informe = assertNotNull(editor.analizarFabricacion(nombrePerfil = perfil.nombre))
        assertTrue(
            informe.hallazgos.none { it.regla == Regla.INTERFERENCIA || it.regla == Regla.HOLGURA_DE_ENCAJE },
            "no debería haber avisos de encuentro: ${informe.hallazgos.map { it.titulo }}",
        )
    }

    @Test
    fun `las piezas fundidas por la raiz no se denuncian como solape`() {
        // El soporte de ejemplo fusiona base y respaldo a propósito: denunciarlo
        // como interferencia sería el falso positivo que inutiliza la regla.
        val editor = Editor(ModelosDemo.soporte())
        val informe = assertNotNull(editor.analizarFabricacion(nombrePerfil = perfil.nombre))
        assertTrue(
            informe.hallazgos.none { it.regla == Regla.INTERFERENCIA || it.regla == Regla.HOLGURA_DE_ENCAJE },
            "un modelo fundido no debería denunciar encuentros: ${informe.hallazgos.map { it.titulo }}",
        )
    }
}
