import Foundation
import simd

/// Disposición de la cámara tal y como la espera el shader generado.
/// Todo son `float4` para que el empaquetado en Metal no dependa de reglas de
/// alineación sutiles: 96 bytes exactos, sin relleno sorpresa.
struct CamaraGPU {
    var origen: SIMD4<Float>
    var frente: SIMD4<Float>
    var derecha: SIMD4<Float>
    var arriba: SIMD4<Float>
    var params: SIMD4<Float>      // x = tan(fov/2), y = epsilon, z = escalaPasos, w = paralela
    var resolucion: SIMD4<Float>  // xy en píxeles
}

/// Cámara orbital: siempre mira a un objetivo y gira a su alrededor.
/// Es el modelo que espera cualquiera que venga de una herramienta de modelado, y
/// el único que se maneja bien tanto con trackpad como con dos dedos.
struct CamaraOrbital {
    var objetivo = SIMD3<Float>(0, 0, 0)
    var distancia: Float = 120
    var azimut: Float = 0.7
    var elevacion: Float = 0.45
    var campoDeVision: Float = 45 * .pi / 180

    /// Proyección paralela. En una herramienta de cotas es lo que se quiere la mitad del
    /// tiempo: con perspectiva, dos caras del mismo tamaño se dibujan distintas y no se
    /// puede comparar a ojo si dos agujeros están alineados.
    var ortografica = false

    /// Vistas de siempre. Los números son los de cualquier CAD, que es donde los tiene
    /// aprendidos quien vaya a usar esto.
    enum Vista { case planta, alzado, perfil, isometrica }

    mutating func mirarDesde(_ vista: Vista) {
        switch vista {
        case .planta: azimut = -.pi / 2; elevacion = Self.elevacionMaxima
        case .alzado: azimut = -.pi / 2; elevacion = 0
        case .perfil: azimut = 0; elevacion = 0
        case .isometrica: azimut = 0.7; elevacion = 0.45
        }
    }

    /// La elevación se detiene justo antes de los polos: cruzarlos invierte el
    /// vector «arriba» y la escena da un tumbo desconcertante.
    static let elevacionMaxima: Float = .pi / 2 - 0.02

    mutating func orbitar(deltaX: Float, deltaY: Float) {
        azimut += deltaX
        elevacion = min(max(elevacion + deltaY, -Self.elevacionMaxima), Self.elevacionMaxima)
    }

    mutating func acercar(factor: Float) {
        distancia = min(max(distancia * factor, 1), 20_000)
    }

    mutating func desplazar(deltaX: Float, deltaY: Float) {
        let base = baseOrtonormal()
        // El desplazamiento escala con la distancia para que el objeto siga al
        // cursor a cualquier zoom.
        let escala = distancia * 0.0015
        objetivo += base.derecha * (-deltaX * escala) + base.arriba * (deltaY * escala)
    }

    /// Encuadra una caja completa dejando un pequeño margen.
    mutating func encuadrar(minimo: SIMD3<Float>, maximo: SIMD3<Float>) {
        objetivo = (minimo + maximo) * 0.5
        let radio = max(simd_length((maximo - minimo) * 0.5), 0.5)
        distancia = radio / tan(campoDeVision * 0.5) * 1.6
    }

    private func baseOrtonormal() -> (frente: SIMD3<Float>, derecha: SIMD3<Float>, arriba: SIMD3<Float>) {
        let ce = cos(elevacion)
        let posicion = objetivo + SIMD3<Float>(
            ce * cos(azimut) * distancia,
            sin(elevacion) * distancia,
            ce * sin(azimut) * distancia
        )
        let frente = simd_normalize(objetivo - posicion)
        let derecha = simd_normalize(simd_cross(frente, SIMD3<Float>(0, 1, 0)))
        let arriba = simd_cross(derecha, frente)
        return (frente, derecha, arriba)
    }

    var posicion: SIMD3<Float> {
        let ce = cos(elevacion)
        return objetivo + SIMD3<Float>(
            ce * cos(azimut) * distancia,
            sin(elevacion) * distancia,
            ce * sin(azimut) * distancia
        )
    }

    /// El rayo que sale de un punto de la vista, en coordenadas normalizadas.
    ///
    /// Tiene que construirse **igual** que en el fragment shader generado, o señalar
    /// daría una pieza distinta de la que se ve bajo el cursor. De ahí que viva aquí
    /// al lado de `empaquetar`: el día que cambie el encuadre, las dos cosas están en
    /// la misma pantalla.
    ///
    /// - Parameters:
    ///   - uv: coordenadas de pantalla en `[-1, 1]`, con la Y hacia arriba.
    ///   - aspecto: anchura partido por altura del área dibujada.
    func rayo(uv: SIMD2<Float>, aspecto: Float) -> (origen: SIMD3<Float>, direccion: SIMD3<Float>) {
        let base = baseOrtonormal()
        let ndc = SIMD2<Float>(uv.x * aspecto, uv.y) * tan(campoDeVision * 0.5)
        if ortografica {
            // En paralelo todos los rayos van en la misma dirección y lo que cambia es de
            // dónde salen. Es el mismo cambio que hace el shader, y tiene que ser el mismo
            // o señalar apuntaría a un sitio distinto del que se ve.
            let escala = ndc * distancia
            let origen = posicion + base.derecha * escala.x + base.arriba * escala.y
            return (origen, base.frente)
        }
        let direccion = simd_normalize(base.frente + base.derecha * ndc.x + base.arriba * ndc.y)
        return (posicion, direccion)
    }

    /// Cuánto avanza una cara, en milímetros del mundo, al arrastrar el ratón.
    ///
    /// Es lo que convierte un arrastre en pantalla en un empujón sobre una cota. La cara
    /// solo puede moverse a lo largo de su normal, así que se proyecta la normal sobre los
    /// ejes de la pantalla y se toma la componente del arrastre que va en esa dirección:
    /// una cara vista de canto no se mueve por mucho que se arrastre, que es exactamente
    /// lo que debe pasar.
    ///
    /// - Parameters:
    ///   - normal: normal de la cara, en el mundo y normalizada.
    ///   - deltaX: recorrido del ratón en puntos, positivo hacia la derecha.
    ///   - deltaY: recorrido del ratón en puntos, **positivo hacia abajo**, que es como
    ///     lo entrega AppKit. Confundir este signo invierte el gesto entero.
    ///   - distanciaAlImpacto: a qué distancia está el punto que se arrastra.
    ///   - alturaEnPuntos: altura de la vista, para saber cuántos milímetros mide un punto.
    func avanceDeArrastre(
        normal: SIMD3<Float>,
        deltaX: Float,
        deltaY: Float,
        distanciaAlImpacto: Float,
        alturaEnPuntos: Float
    ) -> Float {
        guard alturaEnPuntos > 0 else { return 0 }
        let base = baseOrtonormal()
        // Lo que mide un punto de pantalla en el plano que pasa por el impacto.
        let mmPorPunto = 2 * tan(campoDeVision * 0.5) * distanciaAlImpacto / alturaEnPuntos
        let enPantalla = SIMD2<Float>(simd_dot(normal, base.derecha), simd_dot(normal, base.arriba))
        return (deltaX * enPantalla.x - deltaY * enPantalla.y) * mmPorPunto
    }

    /// - Parameter epsilonRelativo: umbral de impacto como fracción de la distancia
    ///   de la cámara. Fijarlo en unidades absolutas produce bordes sucios de lejos
    ///   y un gasto de pasos innecesario de cerca.
    func empaquetar(resolucion: SIMD2<Float>, escalaPasos: Float, epsilonRelativo: Float) -> CamaraGPU {
        let base = baseOrtonormal()
        return CamaraGPU(
            origen: SIMD4<Float>(posicion, 0),
            frente: SIMD4<Float>(base.frente, 0),
            derecha: SIMD4<Float>(base.derecha, 0),
            arriba: SIMD4<Float>(base.arriba, 0),
            params: SIMD4<Float>(
                tan(campoDeVision * 0.5),
                max(distancia * epsilonRelativo, 1e-4),
                escalaPasos,
                // w: media anchura de la vista en paralelo, o 0 con perspectiva. Va aquí y
                // no en un booleano aparte porque el hueco ya existía en el empaquetado.
                ortografica ? distancia : 0
            ),
            resolucion: SIMD4<Float>(resolucion.x, resolucion.y, 0, 0)
        )
    }
}
