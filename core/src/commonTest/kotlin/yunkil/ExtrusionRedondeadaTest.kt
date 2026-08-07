package yunkil

import yunkil.kernel.Extrusion
import yunkil.kernel.Perfil2D
import yunkil.kernel.Vec3
import yunkil.malla.ContorneadoDual
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `EXTRUSION` con `redondeo > 0` sacaba mallas con agujeros a resolución fina.
 *
 * El redondeo de arista se aplicaba **restando** el radio al perfil en vez de
 * sumándolo, así que la pieza crecía el doble del radio a lo ancho mientras que
 * `cotas()` solo declaraba uno. La rejilla de muestreo se apoya en esas cotas: con
 * un margen de `2 · resolución`, a resolución gruesa el error cabía dentro del
 * margen y la malla cerraba, pero en cuanto la resolución bajaba de la mitad del
 * radio la superficie se salía de la caja y quedaba cortada. Por eso el fallo solo
 * aparecía en el mallado fino del analizador FDM.
 *
 * Las tres pruebas atacan los tres eslabones: el campo conserva las cotas, la caja
 * contiene de verdad al campo, y la malla fina cierra.
 */
class ExtrusionRedondeadaTest {

    private val pieza = Extrusion(
        perfil = Perfil2D.rectangulo(ancho = 40f, alto = 20f),
        altura = 10f,
        redondeo = 1f,
    )

    @Test
    fun elRedondeoNoCambiaLasCotasExteriores() {
        // A media altura, la cara lateral tiene que seguir estando en x = ±20.
        val enLaCara = pieza.evaluar(Vec3(20f, 0f, 0f))
        val enLaCaraZ = pieza.evaluar(Vec3(0f, 0f, 10f))
        val enLaTapa = pieza.evaluar(Vec3(0f, 5f, 0f))

        assertTrue(abs(enLaCara) < 1e-3f, "cara X en x=20 mide $enLaCara, debería ser 0")
        assertTrue(abs(enLaCaraZ) < 1e-3f, "cara Z en z=10 mide $enLaCaraZ, debería ser 0")
        assertTrue(abs(enLaTapa) < 1e-3f, "tapa en y=5 mide $enLaTapa, debería ser 0")
    }

    @Test
    fun lasCotasContienenTodoElMaterial() {
        // Es la invariante que el mallador da por supuesta: fuera de la caja no
        // puede quedar campo negativo, o la rejilla corta la pieza.
        val c = pieza.cotas()
        var peor = Float.POSITIVE_INFINITY
        var donde = Vec3.ZERO

        val pasos = 24
        for (i in 0..pasos) for (j in 0..pasos) {
            val u = i.toFloat() / pasos
            val v = j.toFloat() / pasos
            val x = c.min.x + (c.max.x - c.min.x) * u
            val y = c.min.y + (c.max.y - c.min.y) * u
            val z = c.min.z + (c.max.z - c.min.z) * v
            val yv = c.min.y + (c.max.y - c.min.y) * v
            val zv = c.min.z + (c.max.z - c.min.z) * v

            for (p in listOf(
                Vec3(c.min.x, y, zv), Vec3(c.max.x, y, zv),
                Vec3(x, c.min.y, zv), Vec3(x, c.max.y, zv),
                Vec3(x, yv, c.min.z), Vec3(x, yv, c.max.z),
            )) {
                val d = pieza.evaluar(p)
                if (d < peor) { peor = d; donde = p }
            }
        }

        assertTrue(peor >= -1e-3f, "hay material fuera de las cotas: $peor mm en $donde")
    }

    @Test
    fun laMallaFinaCierra() {
        // 0,15 mm es del orden de lo que pide el analizador FDM, y es menor que
        // la mitad del redondeo: justo el régimen donde aparecía el agujero.
        val malla = ContorneadoDual(pieza, 0.15f).generar()
        val topologia = malla.revisarTopologia()

        assertTrue(
            topologia.esCerrada,
            "malla abierta: ${topologia.aristasAbiertas} aristas de ${topologia.aristas}",
        )
        assertTrue(topologia.estaBienOrientada, "${topologia.aristasInvertidas} aristas invertidas")
    }
}
