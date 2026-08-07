package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.*
import yunkil.kernel.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El nervio de refuerzo, que el banco señaló como hueco.
 *
 * Es la operación que evita que una escuadra impresa se parta por la esquina, y la
 * que un modelo de lenguaje no sabe construir: exige un triángulo rectángulo en el
 * plano correcto, girado, y con el vértice en el sitio. Puesto a intentarlo, el modelo
 * local devolvió una escuadra en L pelada llamándola «nervio integrado».
 *
 * Lo que se comprueba no es que aparezca una pieza: es que el material caiga **donde
 * refuerza**, medido sobre el campo.
 */
class NervioTest {

    /** Una escuadra: base horizontal en XZ y respaldo vertical, tocándose en el canto. */
    private fun escuadra(): Editor {
        val e = Editor(Documento.vacio())
        e.aplicarPlan(
            PlanDeModelado(
                operaciones = listOf(
                    Crear(
                        tipo = "CAJA", alias = "base", nombre = "Base",
                        parametros = mapOf("anchura" to 60f, "altura" to 6f, "profundidad" to 40f, "redondeo" to 0f),
                    ),
                    Crear(
                        tipo = "CAJA", alias = "resp", nombre = "Respaldo",
                        parametros = mapOf("anchura" to 6f, "altura" to 60f, "profundidad" to 40f, "redondeo" to 0f),
                    ),
                    // El respaldo se apoya en el extremo izquierdo de la base y sube.
                    Mover(objetivo = "resp", x = -27f, y = 33f, z = 0f, absoluto = true),
                ),
            ),
        )
        return e
    }

    private fun idDe(e: Editor, nombre: String) = e.filas().first { it.nombre == nombre }.id

    private fun hayMaterialEn(e: Editor, p: Vec3): Boolean =
        (e.documentoActual.compilar()?.evaluar(p) ?: 1f) < 0f

    @Test
    fun `el nervio aparece y queda dentro del arbol`() {
        val e = escuadra()
        assertTrue(e.ponerNervio(idDe(e, "Base"), idDe(e, "Respaldo")), e.ultimoError ?: "")
        assertTrue(e.filas().any { it.nombre == "Nervio" }, "no se creó el nervio")
    }

    @Test
    fun `el nervio pone material en el angulo interior y no fuera`() {
        val e = escuadra()
        // El ángulo interior está justo encima de la base y a la derecha del respaldo.
        val dentro = Vec3(-18f, 12f, 0f)
        val fuera = Vec3(-40f, 30f, 0f) // al otro lado del respaldo: ahí no debe haber nada

        assertFalse(hayMaterialEn(e, dentro), "el punto de prueba ya tenía material antes")
        e.ponerNervio(idDe(e, "Base"), idDe(e, "Respaldo"), tamano = 20f, grosor = 6f)

        assertTrue(hayMaterialEn(e, dentro), "el nervio no llenó el ángulo interior")
        assertFalse(hayMaterialEn(e, fuera), "el nervio salió por el lado equivocado")
    }

    @Test
    fun `el nervio deja el modelo como un solo solido`() {
        // Es lo único que de verdad justifica la operación: un nervio que no toca a las
        // dos piezas es una cuña suelta que al imprimir se cae.
        val e = escuadra()
        e.ponerNervio(idDe(e, "Base"), idDe(e, "Respaldo"), tamano = 20f, grosor = 6f)
        val reparos = RevisorDeGeometria.reparos(
            e.documentoActual,
            e.documentoActual.compilar()!!,
        )
        assertTrue(
            reparos.none { it.clase == ClaseDeFallo.SOLIDOS_SUELTOS },
            "el nervio quedó suelto: $reparos",
        )
    }

    @Test
    fun `dos piezas que no se tocan se rechazan con su nombre`() {
        val e = escuadra()
        e.mover(idDe(e, "Respaldo"), -200f, 33f, 0f, absoluto = true)
        assertFalse(e.ponerNervio(idDe(e, "Base"), idDe(e, "Respaldo")))
        assertTrue(
            (e.ultimoError ?: "").contains("Respaldo"),
            "el motivo no nombra la pieza: ${e.ultimoError}",
        )
    }

    @Test
    fun `el mismo objetivo dos veces se rechaza`() {
        val e = escuadra()
        val base = idDe(e, "Base")
        assertFalse(e.ponerNervio(base, base))
    }

    @Test
    fun `sin medidas el nucleo pone unas razonables`() {
        val e = escuadra()
        assertTrue(e.ponerNervio(idDe(e, "Base"), idDe(e, "Respaldo")), e.ultimoError ?: "")
        val nervio = idDe(e, "Nervio")
        val espesor = e.parametrosDe(nervio).first { it.clave == "altura" }.valor
        // El doble del grosor mínimo de pared: a una capa un nervio no aguanta nada.
        assertTrue(espesor > 0.8f, "el espesor por omisión salió en $espesor mm")
        assertTrue(espesor < 10f, "el espesor por omisión salió desproporcionado: $espesor mm")
    }

    @Test
    fun `la operacion del vocabulario llega hasta la geometria`() {
        val e = escuadra()
        val r = e.aplicarPlan(
            PlanDeModelado(
                operaciones = listOf(
                    Nervio(objetivo = "Base", contra = "Respaldo", tamano = 18f, alias = "n"),
                ),
            ),
        )
        assertTrue(r.exito, r.resumen)
        assertTrue(r.omitidas.isEmpty(), r.omitidas.toString())
        // Bien dentro del triángulo, no sobre la hipotenusa: con lado 18 el punto
        // (-18, 12) cae justo encima de ella y el campo vale cero, que no es «dentro».
        assertTrue(hayMaterialEn(e, Vec3(-24f, 6f, 0f)), "el plan no dejó material en la esquina")
    }

    @Test
    fun `el plan se cuenta en espanol`() {
        val plan = PlanDeModelado(
            operaciones = listOf(
                Crear(tipo = "CAJA", alias = "b", nombre = "Base"),
                Crear(tipo = "CAJA", alias = "r", nombre = "Respaldo"),
                Nervio(objetivo = "b", contra = "r", tamano = 15f),
            ),
        )
        assertEquals(
            "Refuerza con un nervio de 15 mm la esquina entre Base y Respaldo",
            plan.explicar()[2].texto,
        )
    }
}
