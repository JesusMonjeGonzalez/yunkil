package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.TipoPieza
import yunkil.ia.Vocabulario
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * El contexto omite los parámetros que están en su valor por defecto, lo cual
 * ahorra bastante. Pero eso solo es correcto si el modelo puede *recuperar* el
 * valor omitido, y el catálogo publicaba rangos, no defectos: una caja creada
 * justo en sus cotas por defecto salía como «#caja-3 "Bloque" CAJA», sin una
 * sola medida y sin forma de deducirla. Estas pruebas cierran ese agujero.
 */
class DefectosTest {

    @Test
    fun `el catalogo publica el valor por defecto de cada parametro`() {
        val texto = Vocabulario.instrucciones()
        // Sin esto, omitir un parámetro en el contexto es destruir información.
        for (tipo in TipoPieza.entries) {
            for (parametro in tipo.parametros) {
                assertTrue(
                    "por omisión" in texto,
                    "el catálogo no dice los valores por defecto",
                )
            }
        }
        // Los defectos concretos de CAJA: 40 × 20 × 30 con redondeo 2.
        assertTrue("anchura" in texto && "40" in texto, "falta el defecto de anchura")
    }

    @Test
    fun `el contexto avisa de que lo omitido esta en su valor por defecto`() {
        val editor = Editor(Documento.vacio())
        val plan = editor.interpretarPlan(
            """{"reemplazar":true,"operaciones":[
               {"op":"crear","tipo":"CAJA","alias":"c","nombre":"Bloque",
                "parametros":{"anchura":40,"altura":20,"profundidad":30}}]}""",
        )
        editor.aplicarPlan(plan.plan!!, null)

        val contexto = editor.contextoParaModelo()
        assertTrue(
            "omisión" in contexto || "defecto" in contexto,
            "el contexto omite parámetros sin decir que lo hace: $contexto",
        )
    }
}
