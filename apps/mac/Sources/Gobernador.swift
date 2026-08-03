import Foundation
import QuartzCore

/// Reparte el presupuesto de GPU en lugar de gastar todo lo que haya.
///
/// «Aprovechar el hardware al máximo» y «no poner en riesgo el equipo» solo son
/// compatibles si alguien vigila y cede. Eso es esta clase: mide cuánto cuesta cada
/// fotograma y baja la resolución interna antes de que el equipo se caliente o la
/// interacción se vuelva pastosa. Nunca modifica nada del sistema.
final class GobernadorDeRecursos {

    /// Fracción de la resolución nativa a la que se raymarchea. La imagen se estira
    /// después: es mucho más barato que reducir la calidad del sombreado.
    private(set) var escalaDeRender: Float = 1.0

    /// Multiplicador del paso de raymarch. Por debajo de 1 avanza con más cautela
    /// (mejor con campos que no son distancias exactas); por encima, más rápido.
    private(set) var escalaDePasos: Float = 0.92

    private(set) var fotogramasPorSegundo: Double = 0

    private let escalaMinima: Float
    private let escalaMaxima: Float
    private let objetivoDeFotograma: Double

    private var mediaDeFotograma: Double = 1.0 / 120.0
    private var instanteAnterior: CFTimeInterval = CACurrentMediaTime()
    private var fotogramasDesdeElAjuste = 0

    /// - Parameter objetivoDeFotograma: presupuesto por fotograma en segundos.
    ///   Se deriva de la tasa de refresco de la pantalla, no de una constante.
    init(objetivoDeFotograma: Double, escalaMinima: Float = 0.4, escalaMaxima: Float = 1.0) {
        self.objetivoDeFotograma = objetivoDeFotograma
        self.escalaMinima = escalaMinima
        self.escalaMaxima = escalaMaxima
    }

    func registrarFotograma() {
        let ahora = CACurrentMediaTime()
        let transcurrido = ahora - instanteAnterior
        instanteAnterior = ahora

        // Un fotograma absurdo (la app estuvo parada, o el sistema durmió) no debe
        // arrastrar la media ni provocar un ajuste.
        guard transcurrido > 0, transcurrido < 0.5 else { return }

        mediaDeFotograma += (transcurrido - mediaDeFotograma) * 0.1
        fotogramasPorSegundo = 1.0 / mediaDeFotograma

        fotogramasDesdeElAjuste += 1
        guard fotogramasDesdeElAjuste >= 20 else { return }
        fotogramasDesdeElAjuste = 0

        ajustar()
    }

    private func ajustar() {
        let techoTermico = techoPorTemperatura()

        // El dibujo está sincronizado con la pantalla, así que un fotograma nunca
        // baja del intervalo de vsync por mucho margen que sobre. Medir «va sobrado»
        // como «tarda bastante menos que el objetivo» sería una condición imposible
        // de cumplir, y la resolución caída no podría recuperarse jamás.
        if mediaDeFotograma > objetivoDeFotograma * 1.3 {
            // Vamos justos: ceder resolución de golpe es mejor que ir arrastrando.
            escalaDeRender = max(escalaDeRender * 0.85, escalaMinima)
        } else if mediaDeFotograma <= objetivoDeFotograma * 1.05 {
            // Llegamos a tiempo: se recupera poco a poco para no oscilar.
            escalaDeRender = min(escalaDeRender * 1.06, techoTermico)
        }

        // La temperatura manda por encima del rendimiento medido.
        escalaDeRender = min(escalaDeRender, techoTermico)
    }

    /// La degradación térmica es preventiva: se cede resolución antes de que el
    /// sistema tenga que limitar por sí mismo.
    private func techoPorTemperatura() -> Float {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal:  return escalaMaxima
        case .fair:     return min(escalaMaxima, 0.85)
        case .serious:  return min(escalaMaxima, 0.6)
        case .critical: return escalaMinima
        @unknown default: return min(escalaMaxima, 0.85)
        }
    }

    var descripcionDelEstado: String {
        let temperatura: String
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: temperatura = "normal"
        case .fair: temperatura = "templado"
        case .serious: temperatura = "caliente"
        case .critical: temperatura = "crítico"
        @unknown default: temperatura = "?"
        }
        return String(
            format: "%.0f fps · render %.0f%% · térmico %@",
            fotogramasPorSegundo, escalaDeRender * 100, temperatura
        )
    }
}
