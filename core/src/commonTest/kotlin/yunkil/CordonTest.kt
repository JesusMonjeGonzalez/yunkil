package yunkil

import yunkil.kernel.Cordon
import yunkil.kernel.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * El cordón: una cola, una pata, un mechón o un cable como **un** nodo.
 *
 * Antes de esto, una cola curvada se escribía encadenando cápsulas orientadas a mano.
 * Funcionaba y se imprimía, pero tenía tres costes que no se ven en una captura:
 *
 *  1. El grosor solo cambiaba a saltos, de pieza a pieza, así que un afilado suave
 *     necesitaba muchas piezas cortas y aun así se notaban los escalones.
 *  2. Cada tramo era una parte más del contrato con su transformación, de modo que la
 *     IA gastaba la mitad de su presupuesto de partes en la cola de un gato.
 *  3. Mover el arranque de la cola obligaba a recolocar todos los tramos siguientes.
 *
 * Lo que este fichero ata no es que el cordón «se vea bien», sino las dos propiedades
 * de las que depende todo lo demás del sistema: que el campo sea una **distancia de
 * verdad** —el trazador de rayos salta a paso completo y se metería dentro de la
 * superficie si mintiera— y que las **cotas no dejen material fuera**, porque el
 * mallador solo visita lo que las cotas declaran y lo que quede fuera desaparece del
 * STL sin avisar. Eso último ya costó un fallo caro en `Extrusion`.
 */
class CordonTest {

    /** Distancia exacta a un segmento engrosado, para contrastar el tramo recto. */
    private fun capsula(p: Vec3, a: Vec3, b: Vec3, radio: Float): Float {
        val ba = b - a
        val pa = p - a
        val l2 = ba.x * ba.x + ba.y * ba.y + ba.z * ba.z
        val t = ((pa.x * ba.x + pa.y * ba.y + pa.z * ba.z) / l2).coerceIn(0f, 1f)
        return (pa - ba * t).length() - radio
    }

    private fun malla(paso: Float, alcance: Float): List<Vec3> = buildList {
        var x = -alcance
        while (x <= alcance) {
            var y = -alcance
            while (y <= alcance) {
                var z = -alcance
                while (z <= alcance) {
                    add(Vec3(x, y, z)); z += paso
                }
                y += paso
            }
            x += paso
        }
    }

    @Test
    fun `un cordon recto de grosor constante es exactamente una capsula`() {
        // El caso degenerado del cordón tiene que coincidir con la primitiva que ya
        // existía: si aquí divergiera, el error estaría en la fórmula del tronco y no
        // en la curvatura, y sería mucho más difícil de ver en una figura completa.
        val a = Vec3(-10f, 0f, 0f)
        val b = Vec3(10f, 0f, 0f)
        val cordon = Cordon(listOf(a, b), listOf(4f, 4f))

        for (p in malla(paso = 3f, alcance = 18f)) {
            val esperado = capsula(p, a, b, 4f)
            assertTrue(
                abs(cordon.evaluar(p) - esperado) < 1e-3f,
                "en $p el cordón dice ${cordon.evaluar(p)} y la cápsula $esperado",
            )
        }
    }

    @Test
    fun `el campo es una distancia de verdad, no una aproximacion`() {
        // 1-Lipschitz: entre dos puntos cualesquiera, el campo no puede cambiar más de
        // lo que se han movido. Es la condición que permite al raymarcher avanzar el
        // valor entero del campo sin saltarse la superficie.
        val cordon = Cordon(
            puntos = listOf(
                Vec3(0f, 0f, 0f),
                Vec3(6f, 10f, 2f),
                Vec3(2f, 20f, -4f),
                Vec3(-8f, 26f, 0f),
            ),
            radios = listOf(5f, 3.5f, 2f, 0.6f),
        )

        val puntos = malla(paso = 4f, alcance = 30f)
        for (i in puntos.indices step 7) {
            for (j in puntos.indices step 11) {
                val p = puntos[i]
                val q = puntos[j]
                val recorrido = (p - q).length()
                val salto = abs(cordon.evaluar(p) - cordon.evaluar(q))
                assertTrue(
                    salto <= recorrido + 1e-3f,
                    "de $p a $q el campo salta $salto habiéndose movido $recorrido",
                )
            }
        }
    }

    @Test
    fun `la superficie esta donde dice el radio de cada vertice`() {
        // Un punto en el ecuador de un vértice está a distancia cero: es lo que hace que
        // «radio 2 mm» signifique 2 mm de verdad y el analizador de pared no mienta.
        val cordon = Cordon(
            puntos = listOf(Vec3(0f, 0f, 0f), Vec3(0f, 20f, 0f), Vec3(0f, 40f, 0f)),
            radios = listOf(6f, 4f, 2f),
        )

        for ((i, r) in listOf(6f, 4f, 2f).withIndex()) {
            val centro = Vec3(0f, i * 20f, 0f)
            val fuera = cordon.evaluar(centro + Vec3(r, 0f, 0f))
            assertTrue(abs(fuera) < 0.35f, "el ecuador del vértice $i evalúa $fuera")
            assertTrue(cordon.evaluar(centro) < 0f, "el eje del vértice $i no está dentro")
        }
    }

    @Test
    fun `el grosor interpola entre vertices en vez de saltar`() {
        // La razón de ser del nodo: una punta que se afila. Medida la sección a distintas
        // alturas, tiene que decrecer de forma monótona, sin escalones.
        val cordon = Cordon(
            puntos = listOf(Vec3(0f, 0f, 0f), Vec3(0f, 30f, 0f)),
            radios = listOf(8f, 0.5f),
        )

        fun seccion(y: Float): Float {
            // Radio de la sección: el primer punto hacia fuera que ya no está dentro.
            var r = 0f
            while (r < 20f && cordon.evaluar(Vec3(r, y, 0f)) < 0f) r += 0.05f
            return r
        }

        var anterior = Float.MAX_VALUE
        for (y in listOf(2f, 8f, 14f, 20f, 26f)) {
            val s = seccion(y)
            assertTrue(s < anterior, "a $y mm la sección es $s y arriba era $anterior")
            anterior = s
        }
        assertTrue(seccion(2f) > 6f, "el arranque debería rondar los 8 mm")
        assertTrue(seccion(26f) < 2f, "la punta debería estar ya afilada")
    }

    @Test
    fun `el codo empalma sin agujero ni pico`() {
        // Dos tramos que se encuentran en ángulo recto. El material del codo tiene que
        // ser continuo: si el mínimo de los dos troncos dejara un hueco, la figura se
        // partiría en dos al mallarla.
        val codo = Vec3(0f, 20f, 0f)
        val cordon = Cordon(
            puntos = listOf(Vec3(0f, 0f, 0f), codo, Vec3(20f, 20f, 0f)),
            radios = listOf(3f, 3f, 3f),
        )

        assertTrue(cordon.evaluar(codo) <= -2.9f, "el centro del codo no está macizo")
        // La cara interior del codo es la que se abriría: se comprueba en diagonal.
        val interior = codo + Vec3(1.5f, -1.5f, 0f)
        assertTrue(cordon.evaluar(interior) < 0f, "la cara interior del codo tiene un hueco")
    }

    @Test
    fun `una bola que se traga a la otra no rompe la formula`() {
        // Sin tangente común entre las dos esferas la fórmula general dividiría por cero
        // y devolvería NaN, que en el shader se ve como un agujero negro en el viewport.
        // La IA propone estos casos sin querer en cuanto une un tronco gordo con una
        // punta corta.
        val cordon = Cordon(
            puntos = listOf(Vec3(0f, 0f, 0f), Vec3(0f, 1f, 0f)),
            radios = listOf(10f, 2f),
        )

        for (p in malla(paso = 2.5f, alcance = 14f)) {
            val d = cordon.evaluar(p)
            assertTrue(d.isFinite(), "en $p el campo vale $d")
            // La unión es la bola grande: la distancia coincide con la suya.
            val bola = p.length() - 10f
            assertTrue(abs(d - bola) < 1e-3f, "en $p el cordón dice $d y la bola $bola")
        }
    }

    @Test
    fun `las cotas no dejan material fuera`() {
        // Las cotas pueden sobrar, nunca faltar: el mallador solo visita lo declarado.
        val cordon = Cordon(
            puntos = listOf(
                Vec3(-12f, 0f, -3f),
                Vec3(0f, 14f, 6f),
                Vec3(11f, 22f, -5f),
            ),
            radios = listOf(4f, 6f, 1.5f),
        )
        val caja = cordon.cotas()

        for (p in malla(paso = 1.5f, alcance = 34f)) {
            if (cordon.evaluar(p) >= 0f) continue
            assertTrue(
                p.x >= caja.min.x - 1e-3f && p.x <= caja.max.x + 1e-3f &&
                    p.y >= caja.min.y - 1e-3f && p.y <= caja.max.y + 1e-3f &&
                    p.z >= caja.min.z - 1e-3f && p.z <= caja.max.z + 1e-3f,
                "$p tiene material y cae fuera de las cotas $caja",
            )
        }
    }

    @Test
    fun `los escalares salen en el orden que lee el shader`() {
        // El generador MSL empaqueta esta lista tal cual y la lee en cuartetos. Si el
        // orden se desalineara, los uniforms de un cordón se leerían corridos y la curva
        // aparecería en otro sitio, sin que ninguna prueba de geometría lo notara.
        val cordon = Cordon(
            puntos = listOf(Vec3(1f, 2f, 3f), Vec3(4f, 5f, 6f)),
            radios = listOf(7f, 8f),
        )
        assertTrue(
            cordon.escalares == listOf(1f, 2f, 3f, 7f, 4f, 5f, 6f, 8f),
            "los escalares salieron como ${cordon.escalares}",
        )
    }

    @Test
    fun `un cordon mal formado se rechaza al construirlo`() {
        // Un cordón con menos radios que puntos habría reventado dentro del bucle de
        // evaluación, a millones de llamadas por malla, en lugar de al construirlo.
        val fallos = listOf(
            runCatching { Cordon(listOf(Vec3.ZERO), listOf(1f)) },
            runCatching { Cordon(listOf(Vec3.ZERO, Vec3(1f, 0f, 0f)), listOf(1f)) },
            runCatching {
                val muchos = List(Cordon.MAXIMO_DE_PUNTOS + 1) { Vec3(it.toFloat(), 0f, 0f) }
                Cordon(muchos, muchos.map { 1f })
            },
        )
        for ((i, r) in fallos.withIndex()) {
            assertTrue(r.isFailure, "el cordón mal formado $i se construyó igualmente")
        }
    }

    @Test
    fun `el minimo de tramos no infla el campo dentro de la union`() {
        // Encadenar tramos con `min` es exacto fuera y conservador dentro. Conservador
        // significa que puede quedarse corto en valor absoluto, jamás pasarse: si se
        // pasara, el trazador saltaría por encima de la superficie desde dentro.
        val a = Vec3(0f, 0f, 0f)
        val b = Vec3(0f, 12f, 0f)
        val c = Vec3(0f, 24f, 0f)
        val cordon = Cordon(listOf(a, b, c), listOf(5f, 5f, 5f))
        val entero = Cordon(listOf(a, c), listOf(5f, 5f))

        for (p in malla(paso = 2f, alcance = 20f)) {
            val partido = cordon.evaluar(p)
            val recto = entero.evaluar(p)
            // Mismo sólido: el partido nunca puede declarar más material que el recto.
            assertTrue(partido >= recto - 1e-3f, "en $p el partido dice $partido y el recto $recto")
            if (recto >= 0f) {
                assertTrue(abs(partido - recto) < 1e-3f, "fuera del sólido deben coincidir en $p")
            }
        }
        // Y el error de dentro está acotado por el radio, no crece sin techo.
        val centro = Vec3(0f, 12f, 0f)
        assertTrue(abs(cordon.evaluar(centro) - entero.evaluar(centro)) < 1e-3f)
    }
}
