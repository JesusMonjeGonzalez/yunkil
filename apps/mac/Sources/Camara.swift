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
    var params: SIMD4<Float>      // x = tan(fov/2), y = epsilon, z = escalaPasos
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
                0
            ),
            resolucion: SIMD4<Float>(resolucion.x, resolucion.y, 0, 0)
        )
    }
}
