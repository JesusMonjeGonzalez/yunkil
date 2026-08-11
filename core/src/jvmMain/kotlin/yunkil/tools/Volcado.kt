package yunkil.tools

import yunkil.kernel.AcuerdoLocal
import yunkil.kernel.AlisadoLocal
import yunkil.kernel.Axis
import yunkil.kernel.Caja
import yunkil.kernel.Capsula
import yunkil.kernel.Cilindro
import yunkil.kernel.Cono
import yunkil.kernel.Cordon
import yunkil.kernel.Diferencia
import yunkil.kernel.Desfase
import yunkil.kernel.Esfera
import yunkil.kernel.Extrusion
import yunkil.kernel.Interseccion
import yunkil.kernel.ModoDeAcuerdo
import yunkil.kernel.MoverLocal
import yunkil.kernel.PellizcoLocal
import yunkil.kernel.Perfil2D
import yunkil.kernel.PerfilDeAcuerdo
import yunkil.kernel.Punto2
import yunkil.kernel.Quat
import yunkil.kernel.Repeticion
import yunkil.kernel.Revolucion
import yunkil.kernel.RepeticionCircular
import yunkil.kernel.SdfNode
import yunkil.kernel.Simetria
import yunkil.kernel.SitioDeArrastre
import yunkil.kernel.SitioDeMezcla
import yunkil.kernel.SitioDePellizco
import yunkil.kernel.Toro
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vaciado
import yunkil.kernel.Vec3
import yunkil.kernel.empaquetarUniforms
import yunkil.kernel.normal
import yunkil.msl.MslGenerator
import yunkil.organico.MotorOrganico
import java.io.File
import kotlin.random.Random

/**
 * Vuelca los casos de paridad a disco para que el arnés de Metal los verifique.
 *
 * El invariante que protege es el más importante del proyecto: si `evaluar` en
 * Kotlin y el MSL generado divergen, el viewport enseña una geometría y el
 * analizador de fabricación razona sobre otra distinta.
 */
fun main(args: Array<String>) {
    val destino = File(args.firstOrNull() ?: "build/paridad")
    destino.deleteRecursively()
    destino.mkdirs()

    val todos = casos().map { (nombre, modelo) -> Triple(nombre, modelo, null as SdfNode?) } +
        casosConFantasma()
    todos.forEach { (nombre, modelo, fantasma) -> volcar(destino, nombre, modelo, fantasma) }

    println("Volcados ${todos.size} casos en ${destino.absolutePath}")
}

private fun volcar(destino: File, nombre: String, modelo: SdfNode, fantasma: SdfNode?) {
    val generador = MslGenerator()
    run {
        val dir = File(destino, nombre).apply { mkdirs() }
        val shader = generador.generar(modelo, fantasma)

        File(dir, "shader.metal").writeText(shader.fuente)

        // Concatenados y en este orden: es el acuerdo que el generador da por hecho al
        // emitir los índices del segundo árbol desplazados por el tamaño entero del
        // primero. Un desfase aquí no da error de compilación: da un fantasma con otras
        // cotas, que es exactamente lo que este caso existe para cazar.
        val uniforms = modelo.empaquetarUniforms() +
            (fantasma?.empaquetarUniforms() ?: FloatArray(0))
        check(uniforms.size == shader.numeroDeUniforms) {
            "$nombre: el shader espera ${shader.numeroDeUniforms} uniforms pero el árbol empaqueta ${uniforms.size}"
        }

        // Los campos horneados, cuando el caso lleva una malla importada. Van en
        // binario y aparte del texto porque son millones de floats: escribirlos como
        // decimales multiplicaría por seis el archivo y metería error de redondeo justo
        // en los números cuya igualdad se está comprobando.
        for ((i, campo) in shader.campos.withIndex()) {
            File(dir, "campo$i.bin").writeBytes(
                java.nio.ByteBuffer
                    .allocate(campo.muestras.size * 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .apply { campo.muestras.forEach { putFloat(it) } }
                    .array(),
            )
        }

        // Con fantasma se muestrea sobre la unión de los dos árboles: los puntos pegados
        // a la superficie tienen que caer también sobre la del fantasma, que es donde su
        // fórmula se rompería si leyera los uniforms del vecino.
        val puntos = puntosDeMuestra(fantasma?.let { Union(modelo, it, 0f) } ?: modelo)
        File(dir, "datos.txt").writeText(
            buildString {
                appendLine("uniforms ${uniforms.size}")
                appendLine(uniforms.joinToString(" ") { it.toString() })
                appendLine("campos ${shader.campos.size}")
                for (campo in shader.campos) appendLine("${campo.nx} ${campo.ny} ${campo.nz}")
                appendLine("fantasma ${if (fantasma == null) 0 else 1}")
                appendLine("puntos ${puntos.size}")
                for (p in puntos) {
                    val cola = fantasma?.let { " ${it.evaluar(p)}" } ?: ""
                    appendLine("${p.x} ${p.y} ${p.z} ${modelo.evaluar(p)}$cola")
                }
            },
        )
    }
}

/**
 * Puntos de muestra en dos poblaciones: una nube uniforme que cubre el dominio y
 * otra pegada a la superficie, que es donde las fórmulas se rompen si están mal.
 */
private fun puntosDeMuestra(modelo: SdfNode): List<Vec3> {
    val r = Random(20260803)
    val cotas = modelo.cotas()
    val centro = cotas.center
    val alcance = maxOf(cotas.radius * 1.8f, 1f)

    fun aleatorio() = centro + Vec3(
        (r.nextFloat() * 2f - 1f) * alcance,
        (r.nextFloat() * 2f - 1f) * alcance,
        (r.nextFloat() * 2f - 1f) * alcance,
    )

    val uniformes = List(600) { aleatorio() }

    // Proyección aproximada sobre la superficie siguiendo el gradiente.
    val superficie = ArrayList<Vec3>(400)
    var intentos = 0
    while (superficie.size < 400 && intentos < 20000) {
        intentos++
        var p = aleatorio()
        repeat(24) {
            val d = modelo.evaluar(p)
            if (kotlin.math.abs(d) < 0.05f) return@repeat
            p -= modelo.normal(p) * d
        }
        if (kotlin.math.abs(modelo.evaluar(p)) < 0.5f) superficie.add(p)
    }

    return uniformes + superficie
}

private fun casos(): List<Pair<String, SdfNode>> = listOf(
    "esfera" to Esfera(10f),
    "caja" to Caja(Vec3(12f, 8f, 5f)),
    "caja_redondeada" to Caja(Vec3(12f, 8f, 5f), redondeo = 2f),
    "cilindro" to Cilindro(6f, 20f),
    "cilindro_redondeado" to Cilindro(6f, 20f, redondeo = 1.5f),
    "cono" to Cono(radioInferior = 10f, radioSuperior = 3f, altura = 18f),
    "cono_punta" to Cono(radioInferior = 9f, radioSuperior = 0f, altura = 14f),
    "toro" to Toro(radioMayor = 15f, radioMenor = 4f),
    "capsula" to Capsula(radio = 5f, altura = 16f),

    "union" to Union(Esfera(10f), desplazada(Esfera(9f), 8f)),
    "union_fusionada" to Union(Esfera(10f), desplazada(Esfera(9f), 8f), fusion = 4f),
    "diferencia" to Diferencia(Caja(Vec3(12f, 12f, 12f)), Esfera(15f)),
    "diferencia_fusionada" to Diferencia(Caja(Vec3(12f, 12f, 12f)), Esfera(15f), fusion = 2f),
    "interseccion" to Interseccion(Caja(Vec3(10f, 10f, 10f)), Esfera(13f)),

    // Los filetes locales llevan una caída propia en el shader (`yk_caida`). Es la parte
    // del acuerdo local que puede divergir en silencio: la matemática es corta pero si el
    // peso de la mezcla no sale igual en los dos lados, el viewport enseñaría un canto
    // redondeado donde el analizador ve una arista viva. Los tres modos, porque cada uno
    // emite una expresión distinta.
    "acuerdo_union" to AcuerdoLocal(
        Caja(Vec3(10f, 10f, 10f)),
        desplazada(Caja(Vec3(10f, 10f, 10f)), 20f),
        ModoDeAcuerdo.UNION,
        centro = Vec3(10f, 10f, 0f),
        radio = 6f,
        fusion = 4f,
    ),
    "acuerdo_diferencia" to AcuerdoLocal(
        Caja(Vec3(14f, 14f, 14f)),
        Esfera(16f),
        ModoDeAcuerdo.DIFERENCIA,
        centro = Vec3(8f, 8f, 8f),
        radio = 7f,
        fusion = 3f,
    ),
    "acuerdo_interseccion" to AcuerdoLocal(
        Caja(Vec3(10f, 10f, 10f)),
        Esfera(13f),
        ModoDeAcuerdo.INTERSECCION,
        centro = Vec3(6f, 6f, 0f),
        radio = 5f,
        fusion = 2.5f,
    ),

    // El chaflán, los tres modos. Van aparte de los tres de arriba y no como una
    // variante suya porque emiten funciones distintas (`yk_cmin`/`yk_cmax`) y porque el
    // tope que hace utilizable la mezcla —acotarla a √½·k por debajo de la booleana—
    // solo se puede comprobar contra Metal: en la CPU es una línea de Kotlin y en la GPU
    // es otra línea de MSL, y que digan lo mismo es justo lo que no se puede suponer.
    "chaflan_union" to AcuerdoLocal(
        Caja(Vec3(10f, 10f, 10f)),
        desplazada(Caja(Vec3(10f, 10f, 10f)), 20f),
        ModoDeAcuerdo.UNION,
        centro = Vec3(10f, 10f, 0f),
        radio = 6f,
        fusion = 4f,
        perfil = PerfilDeAcuerdo.CHAFLAN,
    ),
    "chaflan_diferencia" to AcuerdoLocal(
        Caja(Vec3(14f, 14f, 14f)),
        Esfera(16f),
        ModoDeAcuerdo.DIFERENCIA,
        centro = Vec3(8f, 8f, 8f),
        radio = 7f,
        fusion = 3f,
        perfil = PerfilDeAcuerdo.CHAFLAN,
    ),
    "chaflan_interseccion" to AcuerdoLocal(
        Caja(Vec3(10f, 10f, 10f)),
        Esfera(13f),
        ModoDeAcuerdo.INTERSECCION,
        centro = Vec3(6f, 6f, 0f),
        radio = 5f,
        fusion = 2.5f,
        perfil = PerfilDeAcuerdo.CHAFLAN,
    ),

    // El perfil poligonal, que no tenía ni un caso y es la función más larga del
    // preludio: la única con un bucle sobre los uniforms y la única con una excepción
    // —la arista que cae sobre el eje de revolución no cuenta para la distancia—. Esa
    // excepción existe en dos sitios, Kotlin y MSL, así que hacen falta los tres casos:
    // sin eje, con el contorno tocándolo y con el eje desplazado fuera del perfil.
    "extrusion_perfil" to Extrusion(contornoDeVaso(), altura = 6f),
    "revolucion_al_eje" to Revolucion(contornoDeVaso(), 0f),
    "revolucion_desplazada" to Revolucion(Perfil2D.rectangulo(4f, 4f), desplazamiento = 12f),

    "transformado" to Transformado(
        Caja(Vec3(10f, 4f, 6f)),
        Transform(
            rotation = Quat.fromAxisAngle(Vec3(0.3f, 1f, 0.2f), 0.9f),
            translation = Vec3(5f, -3f, 2f),
            scale = 1.4f,
        ),
    ),
    "vaciado" to Vaciado(Caja(Vec3(15f, 10f, 10f), redondeo = 2f), grosor = 2.4f),
    "desfase" to Desfase(Caja(Vec3(12f, 8f, 6f), redondeo = 1f), distancia = 2.5f),
    "simetria" to Simetria(desplazada(Cilindro(3f, 20f), 12f), Axis.X),
    "repeticion" to Repeticion(Esfera(3f), cuenta = 5, paso = 9f, eje = Axis.Z),
    "repeticion_circular" to RepeticionCircular(
        Transformado(Esfera(3f), Transform(translation = Vec3(14f, 0f, 0f))),
        cuenta = 7,
        angulo = 300f,
        eje = Axis.Y,
    ),

    // El caso que de verdad importa: todo mezclado, como una pieza real.
    "compuesto" to Diferencia(
        Union(
            Vaciado(Caja(Vec3(30f, 12f, 20f), redondeo = 3f), grosor = 2.4f),
            Simetria(
                Transformado(
                    Cilindro(radio = 4f, altura = 30f, redondeo = 0.5f),
                    Transform(
                        rotation = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), 0.6f),
                        translation = Vec3(20f, 6f, 0f),
                        scale = 1.25f,
                    ),
                ),
                eje = Axis.X,
            ),
            fusion = 2f,
        ),
        Repeticion(
            Transformado(Esfera(3.5f), Transform(translation = Vec3(0f, 8f, 0f))),
            cuenta = 4,
            paso = 12f,
            eje = Axis.Z,
        ),
    ),

    // Las tres brochas de escultura, una a una y luego juntas.
    //
    // Son los primeros nodos del proyecto cuyo campo **no** es 1-Lipschitz, y esa es
    // justamente la razón de que no baste con mirar si el shader menciona la palabra
    // correcta: el alisado promedia cuatro muestras y el orden de la suma cambia el
    // último dígito; los dos deformadores mueven el punto antes de preguntar, y un signo
    // cambiado en el desplazamiento da una figura plausible y distinta. Eso solo lo
    // distingue comparar números contra la GPU.
    "alisado" to AlisadoLocal(
        Union(Esfera(10f), desplazada(Esfera(7f), 9f)),
        listOf(SitioDeMezcla(Vec3(9f, 0f, 0f), 8f, 0.9f)),
        paso = 3f,
    ),
    // Varios sitios: el peso es el máximo y no la suma, y esa diferencia solo se ve donde
    // dos bolas se solapan.
    "alisado_varios" to AlisadoLocal(
        Union(Union(Esfera(10f), desplazada(Esfera(7f), 9f)), desplazada(Esfera(6f), -11f)),
        listOf(
            SitioDeMezcla(Vec3(9f, 0f, 0f), 8f, 0.9f),
            SitioDeMezcla(Vec3(4f, 3f, 0f), 7f, 0.5f),
            SitioDeMezcla(Vec3(-11f, 0f, 0f), 6f, 1f),
        ),
        paso = 2.5f,
    ),
    "pellizco" to PellizcoLocal(
        Esfera(12f),
        listOf(SitioDePellizco(Vec3(0f, 12f, 0f), 8f, 0.28f, Vec3(0f, 1f, 0f))),
    ),
    // Con signo negativo el mapa expande en vez de contraer: es el pellizco al revés y
    // emite la misma línea, así que si el signo se perdiera por el camino este caso lo ve.
    "pellizco_doble" to PellizcoLocal(
        Caja(Vec3(18f, 8f, 10f), redondeo = 3f),
        listOf(
            SitioDePellizco(Vec3(12f, 8f, 0f), 7f, 0.28f, Vec3(0f, 1f, 0f)),
            SitioDePellizco(Vec3(-12f, 8f, 0f), 7f, -0.25f, Vec3(0.6f, 0.8f, 0f)),
        ),
    ),
    "mover" to MoverLocal(
        Capsula(radio = 5f, altura = 20f),
        listOf(SitioDeArrastre(Vec3(0f, 10f, 0f), 9f, Vec3(3f, 2f, 0f))),
    ),
    // Con máscara: el factor que protege una zona multiplica el peso de la brocha, y
    // eso son líneas nuevas en el shader de los tres nodos. Un fallo ahí no da error de
    // compilación: da una brocha que sí entra donde el usuario dijo que no.
    "alisado_enmascarado" to AlisadoLocal(
        Union(Esfera(10f), desplazada(Esfera(7f), 9f)),
        listOf(SitioDeMezcla(Vec3(9f, 0f, 0f), 9f, 0.9f)),
        paso = 3f,
        mascara = listOf(SitioDeMezcla(Vec3(14f, 2f, 0f), 5f, 1f)),
    ),
    "mover_enmascarado" to MoverLocal(
        Capsula(radio = 5f, altura = 20f),
        listOf(SitioDeArrastre(Vec3(5f, 10f, 0f), 9f, Vec3(2f, 1.5f, 0f))),
        mascara = listOf(SitioDeMezcla(Vec3(4f, 15f, 0f), 6f, 0.8f)),
    ),
    "escultura_esculpida" to esculturaEsculpida(),
    "escultura_protegida" to esculturaProtegida(),

    // Y los modelos que la aplicación abre de verdad, compilados desde el documento.
    //
    // Los casos de arriba se escriben a mano para cubrir cada nodo; estos cubren lo
    // que el usuario tiene delante al arrancar. La distinción dejó de ser teórica el
    // día que la marcha podada devolvía 20 mm de más: el fallo estaba en la unión de
    // varios hijos con acuerdo, que es exactamente la forma del demo del soporte.
    "demo_soporte" to (yunkil.doc.ModelosDemo.soporte().compilar() ?: Esfera(1f)),
    "demo_rejilla" to (yunkil.doc.ModelosDemo.rejilla().compilar() ?: Esfera(1f)),

    // Malla importada. Es el único nodo cuyo shader no es aritmética sino una lectura
    // de textura, así que es también el único donde la paridad puede romperse por algo
    // que no está escrito en el generador: el filtro del muestreador, el redondeo de la
    // coordenada, el orden de las capas. Nada de eso se ve leyendo el código.
    //
    // Y compuesta, no suelta: el caso que importa es el de verdad —un STL al que se le
    // resta una ranura— porque ahí el campo horneado tiene que convivir con el resto
    // del árbol y con el buffer de uniforms compartido.
    "malla_cubo" to campoDeCubo(20f, 2f),
    "malla_restada" to Diferencia(campoDeCubo(20f, 2f), Cilindro(4f, 40f)),

    // El cordón. Es el segundo nodo con bucle sobre uniforms —después del barrido— y el
    // primero que lee cuartetos en vez de pares, así que un desfase de un hueco daría
    // una curva parecida pero desplazada, que es justo el fallo que no se ve mirando el
    // viewport. Los tres casos separan las tres ramas de la fórmula del tronco: las dos
    // tapas y la tangente común, más la degeneración de la bola tragada, que es la única
    // que puede devolver NaN.
    "cordon_recto" to Cordon(
        listOf(Vec3(-14f, 0f, 0f), Vec3(14f, 0f, 0f)),
        listOf(5f, 5f),
    ),
    "cordon_afilado" to Cordon(
        listOf(Vec3(0f, -16f, 0f), Vec3(0f, 16f, 0f)),
        listOf(9f, 0.6f),
    ),
    "cordon_curvo" to Cordon(
        listOf(
            Vec3(-12f, -10f, -4f),
            Vec3(-4f, 2f, 6f),
            Vec3(6f, 10f, 2f),
            Vec3(13f, 16f, -6f),
        ),
        listOf(6f, 4.5f, 2.5f, 1f),
    ),
    "cordon_bola_tragada" to Cordon(
        listOf(Vec3(0f, 0f, 0f), Vec3(0f, 1.5f, 0f)),
        listOf(11f, 2f),
    ),
    // Y compuesto, porque en una figura real el cordón nunca va suelto: la cola sale del
    // cuerpo y se funde con él, y ahí comparte el buffer con el resto del árbol.
    "cordon_fundido" to Union(
        Esfera(12f),
        Cordon(
            listOf(Vec3(8f, 4f, 0f), Vec3(20f, 12f, 4f), Vec3(26f, 24f, -2f)),
            listOf(4f, 2.5f, 0.8f),
        ),
        fusion = 3f,
    ),
)

/**
 * Los casos con previsualización fantasma: dos árboles en un shader.
 *
 * Aquí el invariante es otro y hace falta decirlo, porque no se parece al de los demás
 * casos. Lo que puede romperse no es la fórmula de ningún nodo —esas ya están cubiertas
 * una a una— sino el **reparto del buffer**: los dos árboles comparten un solo `u`, el
 * fantasma emite sus índices desplazados por el tamaño entero del documento, y un
 * desfase de un hueco no da error de compilación ni de enlace. Da un fantasma con otras
 * cotas, dibujado encima de la pieza justo cuando alguien va a decidir si lo acepta.
 *
 * Por eso los tres casos crecen en tamaño del primer bloque: cuanto más largo, más
 * grande es el desplazamiento que hay que acertar.
 */
private fun casosConFantasma(): List<Triple<String, SdfNode, SdfNode?>> {
    val base = Caja(Vec3(20f, 8f, 14f), redondeo = 1.5f)
    val torre = Transformado(Cilindro(5f, 24f), Transform(translation = Vec3(0f, 12f, 0f)))

    // El compuesto de arriba, que es el árbol largo: 20 y pico nodos por delante del
    // fantasma. Si el desplazamiento se calculara con los escalares y no con el buffer
    // entero —cajas incluidas—, este caso es el que lo enseña.
    val compuesto = casos().first { it.first == "compuesto" }.second

    return listOf(
        // Lo más común: la propuesta añade una pieza encima de lo que ya hay.
        Triple("fantasma_anade", base as SdfNode, Union(base, torre, fusion = 2f) as SdfNode?),
        // Y lo simétrico: la propuesta quita material. El color de esa zona es el otro,
        // y la geometría que lo decide es esta resta.
        Triple(
            "fantasma_quita",
            Union(base, torre, fusion = 2f) as SdfNode,
            Diferencia(Union(base, torre, fusion = 2f), Cilindro(2.5f, 60f)) as SdfNode?,
        ),
        Triple(
            "fantasma_compuesto",
            compuesto,
            Diferencia(compuesto, Transformado(Esfera(9f), Transform(translation = Vec3(0f, 10f, 0f)))) as SdfNode?,
        ),
    )
}

/**
 * Una escultura de verdad: anatomía, arrastre, pellizco y alisado encadenados.
 *
 * Los cinco casos anteriores prueban cada nodo suelto sobre una primitiva. Este prueba lo
 * que la aplicación construye de verdad al esculpir, que es otra cosa: tres deformaciones
 * apiladas sobre una unión suavizada de cápsulas y troncos, con el reparto de uniforms
 * repartido entre cuatro niveles. Se construye llamando al motor —no montando los nodos a
 * mano— para que también quede cubierto que lo que el motor guarda en el contrato compila
 * al campo que aquí se comprueba.
 */
private fun esculturaEsculpida(protegida: Boolean = false): SdfNode {
    val anatomia = """
        {"esquema":"yunkil.organico.v1","nombre":"Muñeco","unidades":"mm","fusionMm":2.5,
         "partes":[
          {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,10,0],"b":[0,34,0],"radio":12},
          {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,46,0],"radio":11,"unidoA":"cuerpo"},
          {"id":"oreja","rol":"OREJA","forma":"TRONCO","a":[7,52,0],"b":[11,60,0],
           "radioA":3.5,"radioB":1.2,"unidoA":"cabeza"}
         ]}
    """.trimIndent()

    var contrato = MotorOrganico.interpretar(anatomia).contratoCanonico
        ?: error("la anatomía de referencia del arnés dejó de ser válida")

    fun brochazo(
        modo: String,
        centro: Vec3,
        radio: Float,
        intensidad: Float = 0f,
        gesto: Vec3 = Vec3.ZERO,
    ) {
        val r = MotorOrganico.aplicarDeformacion(
            contratoCanonico = contrato,
            modo = modo,
            x = centro.x, y = centro.y, z = centro.z,
            radio = radio,
            intensidad = intensidad,
            dx = gesto.x, dy = gesto.y, dz = gesto.z,
        )
        contrato = r.contratoCanonico ?: error("el motor rechazó el brochazo $modo: ${r.motivo}")
    }

    // Las fuerzas están elegidas para que la cadena entera quepa bajo el tope de
    // gradiente sin que el motor tenga que recortarlas: así el caso comprueba las tres
    // capas y no el recorte.
    if (protegida) {
        // La máscara va primero y **al lado** de los brochazos, no encima: solapada del
        // todo el motor rechazaría el brochazo, y lo que hay que comprobar aquí es el
        // factor a medio camino, que es donde el shader y la CPU pueden discrepar.
        brochazo("PROTEGER", Vec3(6f, 40f, -8f), radio = 9f, intensidad = 1f)
    }
    brochazo("MOVER", Vec3(11f, 60f, 0f), radio = 8f, gesto = Vec3(1.2f, 1.4f, 0.6f))
    brochazo("PELLIZCAR", Vec3(0f, 46f, -11f), radio = 5f, intensidad = 0.12f, gesto = Vec3(0f, 0f, -1f))
    brochazo("ALISAR", Vec3(0f, 34f, -12f), radio = 8f, intensidad = 0.6f)

    return MotorOrganico.nodoDeContrato(contrato)
        ?: error("la escultura esculpida del arnés no compila")
}

/** La misma figura con una zona protegida, que es lo que mete el factor de máscara. */
private fun esculturaProtegida(): SdfNode = esculturaEsculpida(protegida = true)

/**
 * Un cubo horneado a campo, como el que sale de importar un STL.
 *
 * Se construye a mano y no leyendo un archivo para que el caso de paridad no dependa
 * de que haya un STL en disco: un arnés que se cae porque falta un archivo es un arnés
 * que alguien acaba desactivando.
 */
private fun campoDeCubo(lado: Float, resolucion: Float): yunkil.kernel.CampoDeMalla {
    val h = lado * 0.5f
    val vertices = FloatArray(8 * 3)
    for (i in 0 until 8) {
        vertices[i * 3] = if (i and 1 == 0) -h else h
        vertices[i * 3 + 1] = if (i and 2 == 0) -h else h
        vertices[i * 3 + 2] = if (i and 4 == 0) -h else h
    }
    val triangulos = intArrayOf(
        0, 2, 1, 1, 2, 3, 4, 5, 6, 5, 7, 6, 0, 1, 4, 1, 5, 4,
        2, 6, 3, 3, 6, 7, 0, 4, 2, 2, 4, 6, 1, 3, 5, 3, 7, 5,
    )
    return yunkil.kernel.CampoDeMalla.hornear(vertices, triangulos, resolucion, origen = "cubo")
}

private fun desplazada(n: SdfNode, x: Float) =
    Transformado(n, Transform(translation = Vec3(x, 0f, 0f)))

/** Un contorno de pieza torneada: pie ancho, vástago, y cierre por el propio eje. */
private fun contornoDeVaso() = Perfil2D.poligono(
    listOf(
        Punto2(0f, 0f), Punto2(8f, 0f), Punto2(8f, 1.6f),
        Punto2(4f, 1.6f), Punto2(4f, 15f), Punto2(0f, 15f),
    ),
)
