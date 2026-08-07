package yunkil.kernel

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Una malla traída de fuera, horneada a campo de distancias.
 *
 * Es la pieza que faltaba para que Yunkil acepte geometría que **no se puede escribir
 * como fórmula**. Un pato de dibujos, una figura escaneada o cualquier STL descargado
 * son cien mil triángulos, y ninguna combinación de primitivas los reproduce. Pero en
 * cuanto la malla se convierte en un campo, deja de ser un cuerpo extraño: se le puede
 * restar una ranura, ahuecar con `pared`, taladrar imanes, acotar a una medida exacta,
 * pasarle el analizador FDM y exportarla verificada. Eso último es lo que no hace
 * nadie: coger un STL cualquiera y devolverlo imprimible **y comprobado**.
 *
 * ## Cómo se hornea
 *
 * Hacer la consulta punto a punto contra todos los triángulos sería inviable: una
 * rejilla de 128³ son dos millones de consultas. Se hace al revés, que es el método
 * estándar y es órdenes de magnitud más barato:
 *
 * 1. **Se rasterizan los triángulos.** Cada triángulo solo toca las celdas de su
 *    entorno, y en ellas se calcula la distancia exacta punto-triángulo. Sale una
 *    banda estrecha alrededor de la superficie con el valor **exacto**.
 * 2. **El signo sale de contar cruces**, no de las normales declaradas —que en un STL
 *    mienten a menudo— sino de cuántas veces una recta vertical atraviesa la
 *    superficie por debajo del punto. Impar es dentro. Esto exige que la malla sea
 *    cerrada; una con agujeros da signos poco fiables, y por eso conviene comprobar
 *    la topología del archivo importado antes de fiarse de lo que salga.
 * 3. **Fuera de la banda** el valor se deja en ±ancho de banda. Es una cota inferior
 *    de la distancia real, que es justo lo que necesitan el trazador de rayos y el
 *    salto de espacio libre del mallador para seguir siendo correctos. Avanzan más
 *    despacio lejos de la pieza, y nada más.
 *
 * ## El límite, dicho por delante
 *
 * Un campo horneado a resolución *r* no reproduce detalle menor que *r* y redondea
 * las aristas vivas. En una figura orgánica no se nota; en una pieza mecánica de
 * cantos limpios sí. Por eso esto es la vía para traer geometría de fuera, no para
 * sustituir a las primitivas: una caja sigue siendo una caja exacta.
 */
class CampoDeMalla(
    private val caja: Aabb,
    private val nx: Int,
    private val ny: Int,
    private val nz: Int,
    private val paso: Float,
    private val valores: FloatArray,
    /** Ancho de la banda exacta, en milímetros. Fuera de ella el valor es una cota. */
    private val banda: Float,
    /** Para el árbol y la interfaz: de dónde salió. */
    val origen: String = "",
) : SdfNode {

    val resolucion: Float get() = paso
    val celdas: Int get() = nx * ny * nz

    override fun cotas(): Aabb = caja

    override fun evaluar(p: Vec3): Float {
        // Fuera de la rejilla se devuelve la distancia a la caja. Es siempre menor o
        // igual que la distancia real —la pieza está dentro de la caja— y esa
        // desigualdad, y no la exactitud, es lo que hace seguro avanzar a saltos.
        val fuera = distanciaALaCaja(p)
        if (fuera > 0f) return fuera + banda

        val fx = ((p.x - caja.min.x) / paso).coerceIn(0f, (nx - 1).toFloat())
        val fy = ((p.y - caja.min.y) / paso).coerceIn(0f, (ny - 1).toFloat())
        val fz = ((p.z - caja.min.z) / paso).coerceIn(0f, (nz - 1).toFloat())

        val i = fx.toInt().coerceAtMost(nx - 2)
        val j = fy.toInt().coerceAtMost(ny - 2)
        val k = fz.toInt().coerceAtMost(nz - 2)
        val tx = fx - i
        val ty = fy - j
        val tz = fz - k

        // Trilineal. Interpolar una distancia da otra distancia razonable, y sobre
        // todo continua: un campo con escalones haría que el trazador de rayos viera
        // paredes donde no las hay.
        val c000 = valores[indice(i, j, k)]
        val c100 = valores[indice(i + 1, j, k)]
        val c010 = valores[indice(i, j + 1, k)]
        val c110 = valores[indice(i + 1, j + 1, k)]
        val c001 = valores[indice(i, j, k + 1)]
        val c101 = valores[indice(i + 1, j, k + 1)]
        val c011 = valores[indice(i, j + 1, k + 1)]
        val c111 = valores[indice(i + 1, j + 1, k + 1)]

        val c00 = c000 + (c100 - c000) * tx
        val c10 = c010 + (c110 - c010) * tx
        val c01 = c001 + (c101 - c001) * tx
        val c11 = c011 + (c111 - c011) * tx
        val c0 = c00 + (c10 - c00) * ty
        val c1 = c01 + (c11 - c01) * ty
        return c0 + (c1 - c0) * tz
    }

    private fun distanciaALaCaja(p: Vec3): Float {
        val dx = max(caja.min.x - p.x, p.x - caja.max.x)
        val dy = max(caja.min.y - p.y, p.y - caja.max.y)
        val dz = max(caja.min.z - p.z, p.z - caja.max.z)
        val fuera = Vec3(max(dx, 0f), max(dy, 0f), max(dz, 0f))
        return fuera.length() + min(max(dx, max(dy, dz)), 0f)
    }

    private fun indice(i: Int, j: Int, k: Int) = (k * ny + j) * nx + i

    /**
     * Solo viaja la envolvente: el campo son megas, no ocho números.
     *
     * El shader necesita una textura 3D para evaluarlo de verdad, y eso es un enlace
     * de recursos que el renderizador todavía no hace. Mientras tanto el viewport
     * pinta esta caja, que además seguirá haciendo falta después para acotar el
     * trazado. **La evaluación en CPU sí es el campo real**, así que restar, ahuecar,
     * analizar y exportar son exactos desde ya; lo aproximado es la vista previa.
     */
    override val escalares: List<Float> get() = listOf(
        caja.min.x, caja.min.y, caja.min.z, caja.max.x, caja.max.y, caja.max.z,
    )

    // El campo, para quien tenga que subirlo a la GPU. Se expone en bruto y de solo
    // lectura en vez de copiarlo: son hasta 28 MB y el renderizador lo va a volcar a
    // una textura tal cual.
    val anchoEnCeldas: Int get() = nx
    val altoEnCeldas: Int get() = ny
    val fondoEnCeldas: Int get() = nz
    val bandaDelCampo: Float get() = banda
    val muestras: FloatArray get() = valores

    companion object {

        /** Tope de celdas. A 192³ son siete millones: unos 28 MB y unos segundos. */
        const val CELDAS_MAXIMAS = 192

        /**
         * Hornea una malla.
         *
         * [resolucionDeseada] es el tamaño de celda en milímetros. Se respeta salvo
         * que dispare el número de celdas, en cuyo caso se afloja: más vale una pieza
         * algo redondeada que una espera de diez minutos y medio giga de memoria.
         */
        fun hornear(
            vertices: FloatArray,
            triangulos: IntArray,
            resolucionDeseada: Float,
            origen: String = "",
        ): CampoDeMalla {
            require(triangulos.size >= 3) { "una malla sin triángulos no se puede hornear" }
            require(resolucionDeseada > 0f) { "la resolución debe ser positiva" }

            val cotas = cotasDe(vertices)
            // El margen deja sitio para que la banda quepa entera y para que el borde
            // de la rejilla esté limpio: la inundación del signo arranca de ahí.
            val tamano = cotas.size
            val mayor = max(tamano.x, max(tamano.y, tamano.z))
            var paso = max(resolucionDeseada, mayor / (CELDAS_MAXIMAS - 6))
            val margen = paso * 3f
            val caja = cotas.expanded(margen)

            val nx = (ceil(caja.size.x / paso).toInt() + 1).coerceIn(2, CELDAS_MAXIMAS)
            val ny = (ceil(caja.size.y / paso).toInt() + 1).coerceIn(2, CELDAS_MAXIMAS)
            val nz = (ceil(caja.size.z / paso).toInt() + 1).coerceIn(2, CELDAS_MAXIMAS)
            paso = max(caja.size.x / (nx - 1), max(caja.size.y / (ny - 1), caja.size.z / (nz - 1)))

            val banda = paso * 2.5f
            val total = nx * ny * nz
            val valores = FloatArray(total) { banda }

            fun indice(i: Int, j: Int, k: Int) = (k * ny + j) * nx + i

            // --- 1. banda exacta alrededor de cada triángulo
            val numeroDeTriangulos = triangulos.size / 3
            for (t in 0 until numeroDeTriangulos) {
                val ia = triangulos[t * 3]
                val ib = triangulos[t * 3 + 1]
                val ic = triangulos[t * 3 + 2]
                if (ia < 0 || ib < 0 || ic < 0) continue

                val ax = vertices[ia * 3]; val ay = vertices[ia * 3 + 1]; val az = vertices[ia * 3 + 2]
                val bx = vertices[ib * 3]; val by = vertices[ib * 3 + 1]; val bz = vertices[ib * 3 + 2]
                val cx = vertices[ic * 3]; val cy = vertices[ic * 3 + 1]; val cz = vertices[ic * 3 + 2]

                val i0 = celda(min(ax, min(bx, cx)) - banda, caja.min.x, paso, nx)
                val i1 = celda(max(ax, max(bx, cx)) + banda, caja.min.x, paso, nx)
                val j0 = celda(min(ay, min(by, cy)) - banda, caja.min.y, paso, ny)
                val j1 = celda(max(ay, max(by, cy)) + banda, caja.min.y, paso, ny)
                val k0 = celda(min(az, min(bz, cz)) - banda, caja.min.z, paso, nz)
                val k1 = celda(max(az, max(bz, cz)) + banda, caja.min.z, paso, nz)

                for (k in k0..k1) {
                    val pz = caja.min.z + k * paso
                    for (j in j0..j1) {
                        val py = caja.min.y + j * paso
                        for (i in i0..i1) {
                            val px = caja.min.x + i * paso
                            val d = distanciaAlTriangulo(
                                px, py, pz, ax, ay, az, bx, by, bz, cx, cy, cz,
                            )
                            if (d < banda) {
                                val n = indice(i, j, k)
                                if (d < valores[n]) valores[n] = d
                            }
                        }
                    }
                }
            }

            // --- 2. el signo, por paridad de cruces a lo largo de Z
            //
            // La primera versión inundaba «fuera» desde el borde y paraba al llegar a
            // la banda. Está mal, y la prueba lo cazó: la banda es de dos celdas y
            // media, así que una celda que está fuera pero a dos celdas de la
            // superficie no toca ninguna celda de fuera y salía marcada como dentro.
            // Resultado: bolsas de material inventado y seis aristas abiertas al
            // volver a mallar.
            //
            // La paridad no tiene ese problema y además es exacta: una recta vertical
            // que entra y sale de un sólido cerrado lo cruza un número par de veces,
            // así que contando cruces por encima de cada punto se sabe si está dentro
            // sin depender de vecindades ni de anchos de banda.
            // Queda una degeneración conocida: un **vértice** compartido por varios
            // triángulos puede aceptarse en ninguno o en dos, y entonces la paridad de
            // esa columna queda mal. Se probó a desplazar las columnas una milésima de
            // celda para que ningún vértice caiga justo encima: no movió el defecto de
            // exportación que sigue abierto y en cambio abrió una arista en un caso que
            // antes cerraba, así que se retiró. Está anotado porque hay que volver.
            val cruces = Array(nx * ny) { mutableListOf<Float>() }
            for (t in 0 until numeroDeTriangulos) {
                val ia = triangulos[t * 3]; val ib = triangulos[t * 3 + 1]; val ic = triangulos[t * 3 + 2]
                if (ia < 0 || ib < 0 || ic < 0) continue

                var ax = vertices[ia * 3]; var ay = vertices[ia * 3 + 1]; var az = vertices[ia * 3 + 2]
                var bx = vertices[ib * 3]; var by = vertices[ib * 3 + 1]; var bz = vertices[ib * 3 + 2]
                var cx = vertices[ic * 3]; var cy = vertices[ic * 3 + 1]; var cz = vertices[ic * 3 + 2]

                // Se orienta antihorario en la proyección para que la regla de bordes
                // de abajo tenga un único criterio. Los triángulos de canto proyectan
                // área nula y no los cruza ningún rayo vertical: fuera.
                var area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
                if (area < 0f) {
                    val tx = bx; val ty = by; val tz = bz
                    bx = cx; by = cy; bz = cz
                    cx = tx; cy = ty; cz = tz
                    area = -area
                }
                if (area < 1e-9f) continue

                val i0 = celda(min(ax, min(bx, cx)), caja.min.x, paso, nx)
                val i1 = celda(max(ax, max(bx, cx)), caja.min.x, paso, nx)
                val j0 = celda(min(ay, min(by, cy)), caja.min.y, paso, ny)
                val j1 = celda(max(ay, max(by, cy)), caja.min.y, paso, ny)

                for (j in j0..j1) {
                    val py = caja.min.y + j * paso
                    for (i in i0..i1) {
                        val px = caja.min.x + i * paso

                        val e0 = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
                        val e1 = (cx - bx) * (py - by) - (cy - by) * (px - bx)
                        val e2 = (ax - cx) * (py - cy) - (ay - cy) * (px - cx)

                        if (!aceptaBorde(e0, bx - ax, by - ay)) continue
                        if (!aceptaBorde(e1, cx - bx, cy - by)) continue
                        if (!aceptaBorde(e2, ax - cx, ay - cy)) continue

                        // Baricéntricas: el peso de cada vértice es la función de
                        // arista de enfrente.
                        cruces[j * nx + i].add((e1 * az + e2 * bz + e0 * cz) / area)
                    }
                }
            }

            for (j in 0 until ny) for (i in 0 until nx) {
                val columna = cruces[j * nx + i]
                if (columna.isEmpty()) continue
                columna.sort()
                var siguiente = 0
                var dentro = false
                for (k in 0 until nz) {
                    val z = caja.min.z + k * paso
                    while (siguiente < columna.size && columna[siguiente] <= z) {
                        dentro = !dentro
                        siguiente++
                    }
                    if (dentro) {
                        val n = indice(i, j, k)
                        valores[n] = -valores[n]
                    }
                }
            }

            // Las celdas que ningún triángulo tocó conservan el valor de banda con
            // signo positivo, que es correcto: están fuera y a más de la banda.

            return CampoDeMalla(caja, nx, ny, nz, paso, valores, banda, origen)
        }

        /**
         * Regla top-left del rasterizado clásico, y aquí no es un adorno.
         *
         * Cuando una columna cae **exactamente** sobre la arista que comparten dos
         * triángulos —y cae siempre, porque las mallas tienen diagonales que pasan por
         * los puntos de la rejilla— los dos la darían por buena, se contarían dos
         * cruces en la misma z y la paridad se anularía. El resultado era un cubo cuyo
         * centro salía fuera. Aceptando el borde en un solo lado, cada punto pertenece
         * a un único triángulo y la cuenta vuelve a ser exacta.
         */
        private fun aceptaBorde(e: Float, dx: Float, dy: Float): Boolean = when {
            e > 0f -> true
            e < 0f -> false
            else -> dy > 0f || (dy == 0f && dx < 0f)
        }

        private fun celda(coordenada: Float, origen: Float, paso: Float, cuenta: Int): Int =
            ((coordenada - origen) / paso).toInt().coerceIn(0, cuenta - 1)

        private fun cotasDe(vertices: FloatArray): Aabb {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            var i = 0
            while (i + 2 < vertices.size) {
                minX = min(minX, vertices[i]); maxX = max(maxX, vertices[i])
                minY = min(minY, vertices[i + 1]); maxY = max(maxY, vertices[i + 1])
                minZ = min(minZ, vertices[i + 2]); maxZ = max(maxZ, vertices[i + 2])
                i += 3
            }
            return Aabb(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
        }

        /**
         * Distancia de un punto a un triángulo, exacta.
         *
         * Se proyecta al plano y se mira si el pie cae dentro usando coordenadas
         * baricéntricas; si no, la distancia es la de la arista más cercana. Es el
         * núcleo del horneado y se llama millones de veces, así que va con escalares
         * sueltos en vez de vectores: cada `Vec3` intermedio sería una asignación.
         */
        internal fun distanciaAlTriangulo(
            px: Float, py: Float, pz: Float,
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float,
        ): Float {
            val abx = bx - ax; val aby = by - ay; val abz = bz - az
            val acx = cx - ax; val acy = cy - ay; val acz = cz - az
            val apx = px - ax; val apy = py - ay; val apz = pz - az

            val d1 = abx * apx + aby * apy + abz * apz
            val d2 = acx * apx + acy * apy + acz * apz
            if (d1 <= 0f && d2 <= 0f) return sqrt(apx * apx + apy * apy + apz * apz)

            val bpx = px - bx; val bpy = py - by; val bpz = pz - bz
            val d3 = abx * bpx + aby * bpy + abz * bpz
            val d4 = acx * bpx + acy * bpy + acz * bpz
            if (d3 >= 0f && d4 <= d3) return sqrt(bpx * bpx + bpy * bpy + bpz * bpz)

            val cpx = px - cx; val cpy = py - cy; val cpz = pz - cz
            val d5 = abx * cpx + aby * cpy + abz * cpz
            val d6 = acx * cpx + acy * cpy + acz * cpz
            if (d6 >= 0f && d5 <= d6) return sqrt(cpx * cpx + cpy * cpy + cpz * cpz)

            val vc = d1 * d4 - d3 * d2
            if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
                val v = d1 / (d1 - d3)
                val qx = apx - abx * v; val qy = apy - aby * v; val qz = apz - abz * v
                return sqrt(qx * qx + qy * qy + qz * qz)
            }

            val vb = d5 * d2 - d1 * d6
            if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
                val w = d2 / (d2 - d6)
                val qx = apx - acx * w; val qy = apy - acy * w; val qz = apz - acz * w
                return sqrt(qx * qx + qy * qy + qz * qz)
            }

            val va = d3 * d6 - d5 * d4
            if (va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f) {
                val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
                val qx = px - (bx + (cx - bx) * w)
                val qy = py - (by + (cy - by) * w)
                val qz = pz - (bz + (cz - bz) * w)
                return sqrt(qx * qx + qy * qy + qz * qz)
            }

            // Cara: distancia al plano.
            val suma = va + vb + vc
            val v = vb / suma
            val w = vc / suma
            val qx = apx - abx * v - acx * w
            val qy = apy - aby * v - acy * w
            val qz = apz - abz * v - acz * w
            return sqrt(qx * qx + qy * qy + qz * qz)
        }
    }
}

/** Distancia sin signo a un triángulo. Se expone para poder verificar el horneado. */
internal fun distanciaPuntoTriangulo(p: Vec3, a: Vec3, b: Vec3, c: Vec3): Float =
    CampoDeMalla.distanciaAlTriangulo(
        p.x, p.y, p.z, a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z,
    )
