import AppKit
import MetalKit
import SwiftUI
import UniformTypeIdentifiers
@preconcurrency import YunkilCore

enum MotorDeIA: String, CaseIterable, Identifiable {
    case parametrico = "Pieza técnica"
    case organico = "Figura orgánica"
    var id: String { rawValue }
}

struct PropuestaOrganica {
    let nombre: String
    let contrato: String
    let objetivoId: String?
}

// MARK: - Vista Metal con gestos

/// `MTKView` que además atiende ratón y trackpad.
///
/// Los eventos se manejan aquí y no en SwiftUI porque orbitar necesita el delta
/// crudo del dispositivo: pasarlo por un `DragGesture` pierde precisión y añade
/// latencia perceptible al arrastrar.
final class VistaMetalInteractiva: MTKView {

    var renderizador: Renderizador?

    /// Se avisa con el rayo que sale del cursor cuando el clic no fue un arrastre.
    var alPinchar: ((SIMD3<Float>, SIMD3<Float>) -> Void)?

    /// Empieza a tirar de una cara: se le pasa el rayo y decide el núcleo.
    /// Devuelve la normal de la cara y la distancia al impacto, o `nil` si no dio en nada.
    var alEmpezarAEmpujar: ((SIMD3<Float>, SIMD3<Float>) -> (normal: SIMD3<Float>, distancia: Float)?)?

    /// Cada fotograma del arrastre, en milímetros del mundo.
    var alEmpujar: ((Float) -> Void)?

    /// Agarra el plano de sección para arrastrarlo: devuelve su normal y la distancia
    /// al cruce del rayo, o `nil` si el clic no cae en el rectángulo visible.
    var alEmpezarAMoverPlano: ((SIMD3<Float>, SIMD3<Float>) -> (normal: SIMD3<Float>, distancia: Float)?)?
    /// Cada fotograma del arrastre del plano, en milímetros a lo largo de su normal.
    var alMoverPlano: ((Float) -> Void)?
    var alEmpezarAEsculpir: ((SIMD3<Float>, SIMD3<Float>) -> Bool)?
    var alContinuarEsculpiendo: ((SIMD3<Float>, SIMD3<Float>) -> Void)?
    var alTerminarDeEsculpir: (() -> Void)?

    /// Centro del gizmo en el mundo, o `nil` si no hay nada que mover.
    /// Lo manda SwiftUI en cada refresco: es la pieza seleccionada.
    var centroDelGizmo: SIMD3<Float>? {
        didSet {
            guard centroDelGizmo != oldValue else { return }
            capaDelGizmo.needsDisplay = true
        }
    }

    /// Marca el punto de deshacer del gesto entero, una sola vez al agarrar.
    var alEmpezarGestoDelGizmo: (() -> Void)?
    /// Un fotograma de arrastre por una flecha: dirección del mundo y milímetros.
    var alMoverConGizmo: ((SIMD3<Float>, Float) -> Void)?
    /// Un fotograma de arrastre por un anillo: eje del mundo y grados.
    var alGirarConGizmo: ((SIMD3<Float>, Float) -> Void)?

    /// Un fotograma de arrastre por el asa de escala: cuánto crece o encoge.
    var alEscalarConGizmo: ((Float) -> Void)?

    /// El asa agarrada, mientras dure el arrastre. Con una agarrada no se orbita.
    private var asaDelGizmo: Gizmo.Asa?

    /// El asa que hay debajo del cursor sin haber pulsado.
    ///
    /// Sin esto hay que adivinar dónde acaba una diana y empieza la siguiente, y el gizmo
    /// se siente resbaladizo aunque acierte siempre: lo que falla no es el clic, es no
    /// saber de antemano qué va a pasar al hacerlo.
    private(set) var asaResaltada: Gizmo.Asa? {
        didSet { if asaResaltada != oldValue { capaDelGizmo.needsDisplay = true } }
    }

    /// La cuenta del arrastre en curso, para poder ajustar por incrementos con ⇧.
    private var acumulador = AcumuladorDeGesto()

    /// Incrementos del ajuste con ⇧. Un milímetro y quince grados son los de cualquier CAD.
    private static let pasoDeAjusteEnMm: Float = 1
    private static let pasoDeAjusteEnGrados: Float = 15
    /// Última posición del cursor en coordenadas de proyección, para el giro: el ángulo
    /// se mide entre dos puntos, no a partir del recorrido del ratón.
    private var ultimoUv = SIMD2<Float>(0, 0)

    private lazy var capaDelGizmo: CapaDelGizmo = {
        let capa = CapaDelGizmo(vista: self)
        capa.autoresizingMask = [.width, .height]
        capa.frame = bounds
        addSubview(capa)
        return capa
    }()

    /// El gizmo tal y como está ahora mismo, o `nil` si no hay que dibujarlo.
    ///
    /// El radio se calcula para que **ocupe siempre lo mismo en pantalla**: atado al
    /// tamaño de la pieza, una arandela de 4 mm daría un gizmo imposible de agarrar y una
    /// carcasa de 300 mm uno que taparía la vista.
    func gizmoActual() -> Gizmo? {
        guard let centro = centroDelGizmo, let camara = renderizador?.camara else { return nil }
        let distancia = camara.ortografica
            ? camara.distancia
            : simd_length(camara.posicion - centro)
        let semialtura = tan(camara.campoDeVision * 0.5) * distancia
        return Gizmo(centro: centro, radio: max(semialtura * 0.22, 0.01))
    }

    func refrescarGizmo() { capaDelGizmo.needsDisplay = true }

    var asaAgarrada: Gizmo.Asa? { asaDelGizmo }

    /// Un arrastre orbita, un clic señala. Se distinguen por recorrido y no por
    /// tiempo: soltar el ratón un poco más tarde no debe cambiar lo que hace.
    private var recorridoDelArrastre: CGFloat = 0

    /// Cara que se está empujando, mientras dure el arrastre.
    private var caraEnArrastre: (normal: SIMD3<Float>, distancia: Float)?

    /// Plano de sección que se está arrastrando, mientras dure el gesto.
    private var planoEnArrastre: (normal: SIMD3<Float>, distancia: Float)?
    private var esculpiendo = false
    private var recorridoDesdeSello: CGFloat = 0

    override var acceptsFirstResponder: Bool { true }

    /// Se avisa con la ruta de un STL soltado sobre la vista.
    var alSoltarStl: ((String) -> Void)?

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        return rutaDeStl(en: sender) != nil ? .copy : []
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        guard let alSoltarStl, let ruta = rutaDeStl(en: sender) else { return false }
        alSoltarStl(ruta)
        return true
    }

    /// La ruta del primer `.stl` que trae el arrastre, o `nil` si no viene ninguno.
    ///
    /// Se mira la extensión del archivo y no el UTI que declara el portapapeles: la
    /// mayoría de los navegadores y gestores de archivos entregan un STL como
    /// `public.data` genérico, y filtrar por UTI dejaría fuera casi todos los casos
    /// reales. La comprobación anterior preguntaba por el tipo del portapapeles
    /// convirtiéndolo a `String`, y esa conversión nunca puede tener éxito —
    /// `PasteboardType` es una estructura, no un `NSString` puenteado—, así que la
    /// vista rechazaba en silencio todo lo que se soltara encima.
    private func rutaDeStl(en sender: NSDraggingInfo) -> String? {
        let opciones: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
        let urls = sender.draggingPasteboard.readObjects(
            forClasses: [NSURL.self], options: opciones
        ) as? [URL] ?? []
        return urls.first { $0.pathExtension.lowercased() == "stl" }?.path
    }

    override func mouseDown(with evento: NSEvent) {
        recorridoDelArrastre = 0
        caraEnArrastre = nil
        planoEnArrastre = nil
        esculpiendo = false
        recorridoDesdeSello = 0

        if let alEmpezarAEsculpir, let rayo = rayoDelCursor(evento),
           alEmpezarAEsculpir(rayo.origen, rayo.direccion) {
            esculpiendo = true
            return
        }

        // El gizmo va antes que el plano de sección: sus asas son dianas de catorce
        // puntos y el rectángulo del plano ocupa media pantalla. Al revés, con la
        // sección activa no habría manera de agarrar una flecha.
        if let gizmo = gizmoActual(), let camara = renderizador?.camara,
           let uv = uvDelCursor(evento), let aspecto = aspectoDeLaVista(),
           let asa = gizmo.agarrar(uv: uv, camara: camara, aspecto: aspecto) {
            asaDelGizmo = asa
            asaResaltada = asa
            ultimoUv = uv
            acumulador = AcumuladorDeGesto()
            // Un solo punto de deshacer para el gesto entero, como en el resto de arrastres.
            alEmpezarGestoDelGizmo?()
            capaDelGizmo.needsDisplay = true
            return
        }

        // El plano de sección se agarra sin modificador: es el gizmo del punto 1,
        // el mismo patrón de «grab the surface» con el que se empujan las caras.
        // Si el clic cae en el rectángulo visible del plano, se arrastra el plano.
        if let alEmpezarAMoverPlano,
           let rayo = rayoDelCursor(evento),
           let agarre = alEmpezarAMoverPlano(rayo.origen, rayo.direccion) {
            planoEnArrastre = agarre
            return
        }

        // ⌘ y arrastrar tira de la cara que hay debajo del cursor. No hay flechas que
        // dibujar: se agarra la propia superficie, que es como se siente en Plasticity y
        // lo único posible mientras el renderizador no tenga tubería de vértices.
        guard evento.modifierFlags.contains(.command),
              let alEmpezarAEmpujar,
              let rayo = rayoDelCursor(evento) else { return }
        caraEnArrastre = alEmpezarAEmpujar(rayo.origen, rayo.direccion)
    }

    override func mouseUp(with evento: NSEvent) {
        caraEnArrastre = nil
        planoEnArrastre = nil
        if asaDelGizmo != nil {
            // Soltar el gizmo no señala: un arrastre corto sobre una flecha volvería a
            // seleccionar lo que hay debajo y saltaría a otra pieza a media colocación.
            asaDelGizmo = nil
            capaDelGizmo.needsDisplay = true
            return
        }
        if esculpiendo {
            esculpiendo = false
            alTerminarDeEsculpir?()
            return
        }
        guard recorridoDelArrastre < 3 else { return }
        guard let alPinchar, let rayo = rayoDelCursor(evento) else { return }
        alPinchar(rayo.origen, rayo.direccion)
    }

    /// El cursor en las coordenadas de proyección: `[-1, 1]` con la Y hacia arriba.
    ///
    /// La vista no está volteada, así que su Y ya crece hacia arriba como la del espacio
    /// de recorte: no hay que invertirla.
    private func uvDelCursor(_ evento: NSEvent) -> SIMD2<Float>? {
        let punto = convert(evento.locationInWindow, from: nil)
        let tamano = bounds.size
        guard tamano.width > 0, tamano.height > 0 else { return nil }
        return SIMD2<Float>(
            Float(punto.x / tamano.width) * 2 - 1,
            Float(punto.y / tamano.height) * 2 - 1
        )
    }

    private func aspectoDeLaVista() -> Float? {
        guard bounds.width > 0, bounds.height > 0 else { return nil }
        return Float(bounds.width / bounds.height)
    }

    /// El rayo que sale del cursor, en coordenadas del mundo.
    private func rayoDelCursor(_ evento: NSEvent) -> (origen: SIMD3<Float>, direccion: SIMD3<Float>)? {
        guard let renderizador, let uv = uvDelCursor(evento), let aspecto = aspectoDeLaVista()
        else { return nil }
        return renderizador.camara.rayo(uv: uv, aspecto: aspecto)
    }

    override func mouseDragged(with evento: NSEvent) {
        recorridoDelArrastre += abs(evento.deltaX) + abs(evento.deltaY)
        guard let renderizador else { return }

        // Con un asa agarrada no se orbita: sería imposible colocar nada.
        if let asa = asaDelGizmo {
            arrastrarElGizmo(asa, evento)
            return
        }

        if esculpiendo {
            recorridoDesdeSello += abs(evento.deltaX) + abs(evento.deltaY)
            if recorridoDesdeSello >= 7, let rayo = rayoDelCursor(evento) {
                recorridoDesdeSello = 0
                alContinuarEsculpiendo?(rayo.origen, rayo.direccion)
            }
            return
        }

        // Arrastrando el plano de sección no se orbita: el plano avanza a lo largo
        // de su normal, igual que una cara se empuja a lo largo de la suya.
        if let plano = planoEnArrastre, let alMoverPlano {
            let avance = renderizador.camara.avanceDeArrastre(
                normal: plano.normal,
                deltaX: Float(evento.deltaX),
                deltaY: Float(evento.deltaY),
                distanciaAlImpacto: plano.distancia,
                alturaEnPuntos: Float(bounds.height)
            )
            alMoverPlano(avance)
            return
        }

        // Tirando de una cara no se orbita: sería imposible apuntar.
        if let cara = caraEnArrastre, let alEmpujar {
            let avance = renderizador.camara.avanceDeArrastre(
                normal: cara.normal,
                deltaX: Float(evento.deltaX),
                deltaY: Float(evento.deltaY),
                distanciaAlImpacto: cara.distancia,
                alturaEnPuntos: Float(bounds.height)
            )
            alEmpujar(avance)
            return
        }

        // Con Option se desplaza en lugar de orbitar: es la convención de casi
        // todas las herramientas 3D y quien venga de ellas la tendrá en los dedos.
        if evento.modifierFlags.contains(.option) {
            renderizador.camara.desplazar(deltaX: Float(evento.deltaX), deltaY: Float(evento.deltaY))
        } else {
            renderizador.camara.orbitar(
                deltaX: Float(evento.deltaX) * 0.008,
                deltaY: Float(evento.deltaY) * 0.008
            )
        }
        // El gizmo se dibuja proyectado, así que mover la cámara lo mueve a él.
        capaDelGizmo.needsDisplay = true
    }

    /// Un fotograma del arrastre de un asa.
    ///
    /// Mover se cuenta con el delta del dispositivo, que es lo que tiene la precisión;
    /// girar y escalar, con la posición del cursor, porque un ángulo se mide entre dos
    /// puntos y una razón entre dos distancias, y ninguno de los dos se puede acumular a
    /// partir de recorridos.
    private func arrastrarElGizmo(_ asa: Gizmo.Asa, _ evento: NSEvent) {
        guard let gizmo = gizmoActual(), let camara = renderizador?.camara,
              let uv = uvDelCursor(evento), let aspecto = aspectoDeLaVista() else { return }
        let ajustando = evento.modifierFlags.contains(.shift)

        switch asa {
        case .mover(let eje):
            let paso = gizmo.avance(
                eje: eje, camara: camara,
                deltaX: Float(evento.deltaX), deltaY: Float(evento.deltaY),
                alturaEnPuntos: Float(bounds.height)
            )
            let delta = acumulador.entregar(paso, incremento: ajustando ? Self.pasoDeAjusteEnMm : nil)
            if delta != 0 { alMoverConGizmo?(eje.direccion, delta) }

        case .girar(let eje):
            let paso = gizmo.grados(
                eje: eje, desde: ultimoUv, hasta: uv, camara: camara, aspecto: aspecto
            )
            let delta = acumulador.entregar(paso, incremento: ajustando ? Self.pasoDeAjusteEnGrados : nil)
            if delta != 0 { alGirarConGizmo?(eje.direccion, delta) }

        case .escalar:
            // La escala no se ajusta por incrementos: el gesto es una razón, y «de uno en
            // uno» no significa nada sobre un factor. Quien quiera una escala exacta la
            // escribe en el inspector, que para eso está la casilla.
            let factor = gizmo.factorDeEscala(
                desde: ultimoUv, hasta: uv, camara: camara, aspecto: aspecto
            )
            if factor != 1 { alEscalarConGizmo?(factor) }
        }
        ultimoUv = uv
        capaDelGizmo.needsDisplay = true
    }

    // MARK: - Resalte

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        for area in trackingAreas { removeTrackingArea(area) }
        addTrackingArea(NSTrackingArea(
            rect: .zero,
            options: [.mouseMoved, .mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect],
            owner: self
        ))
    }

    override func mouseMoved(with evento: NSEvent) {
        guard asaDelGizmo == nil, let gizmo = gizmoActual(), let camara = renderizador?.camara,
              let uv = uvDelCursor(evento), let aspecto = aspectoDeLaVista() else { return }
        asaResaltada = gizmo.agarrar(uv: uv, camara: camara, aspecto: aspecto)
    }

    override func mouseExited(with evento: NSEvent) {
        asaResaltada = nil
    }

    override func rightMouseDragged(with evento: NSEvent) {
        renderizador?.camara.desplazar(deltaX: Float(evento.deltaX), deltaY: Float(evento.deltaY))
        capaDelGizmo.needsDisplay = true
    }

    override func scrollWheel(with evento: NSEvent) {
        guard let renderizador else { return }
        let paso = Float(evento.scrollingDeltaY) * (evento.hasPreciseScrollingDeltas ? 0.002 : 0.05)
        renderizador.camara.acercar(factor: 1 - paso)
        capaDelGizmo.needsDisplay = true
    }

    override func magnify(with evento: NSEvent) {
        renderizador?.camara.acercar(factor: Float(1 - evento.magnification))
        capaDelGizmo.needsDisplay = true
    }
}

/// La capa 2D donde se dibuja el gizmo, encima del viewport de Metal.
///
/// Es una vista aparte y no un trozo del shader por lo mismo que el gizmo es aritmética de
/// cámara: el renderizador no tiene tubería de vértices, y meter las asas en el MSL
/// generado costaría una recompilación y pasos de trazado por cada flecha. Aquí son cuatro
/// trazos de `NSBezierPath`.
///
/// No se redibuja por fotograma: solo cuando cambia lo que dibuja —la cámara, la pieza o el
/// asa agarrada—, que es cuando alguien está tocando algo.
final class CapaDelGizmo: NSView {

    private weak var vista: VistaMetalInteractiva?

    init(vista: VistaMetalInteractiva) {
        self.vista = vista
        super.init(frame: vista.bounds)
        wantsLayer = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("solo por código") }

    /// La capa no atiende el ratón: los eventos son de la vista de abajo, que es quien
    /// sabe orbitar, señalar y agarrar. Sin esto el viewport se quedaría sordo.
    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override var isOpaque: Bool { false }

    override func draw(_ dirtyRect: NSRect) {
        guard let vista, let gizmo = vista.gizmoActual(), let camara = vista.renderizador?.camara,
              bounds.width > 0, bounds.height > 0 else { return }
        let aspecto = Float(bounds.width / bounds.height)
        // Agarrada manda sobre resaltada: mientras se arrastra, el cursor puede pasar por
        // encima de otra asa y lo que responde sigue siendo la de la mano.
        let viva = vista.asaAgarrada ?? vista.asaResaltada
        let arrastrando = vista.asaAgarrada != nil

        for anillo in gizmo.anillos(camara: camara, aspecto: aspecto) {
            let activo = viva == .girar(anillo.eje)
            color(anillo.eje, activo: activo, agarrada: arrastrando).setStroke()
            for tramo in anillo.tramos {
                let trazo = NSBezierPath()
                trazo.lineWidth = activo ? 2.5 : 1.5
                trazo.move(to: punto(tramo[0]))
                for uv in tramo.dropFirst() { trazo.line(to: punto(uv)) }
                trazo.stroke()
            }
        }

        for eje in gizmo.ejes(camara: camara, aspecto: aspecto) {
            let activo = viva == .mover(eje.eje)
            let tinta = color(eje.eje, activo: activo, agarrada: arrastrando)
            tinta.setStroke()
            tinta.setFill()

            let trazo = NSBezierPath()
            trazo.lineWidth = activo ? 3 : 2
            trazo.move(to: punto(eje.base))
            trazo.line(to: punto(eje.punta))
            trazo.stroke()

            // La punta es un disco y no una flecha: a este tamaño una punta triangular
            // se lee peor y, sobre todo, la diana de agarre es un círculo, así que
            // dibujar un círculo es dibujar dónde hay que pinchar.
            let radio: CGFloat = activo ? 6.5 : 5
            let centro = punto(eje.punta)
            NSBezierPath(ovalIn: NSRect(
                x: centro.x - radio, y: centro.y - radio, width: radio * 2, height: radio * 2
            )).fill()
        }

        // El asa de escala: un cuadrado, para que no se confunda con las tres puntas
        // redondas. Blanca y no de un color de eje, porque no estira por ninguno.
        if let escala = gizmo.asaDeEscala(camara: camara, aspecto: aspecto) {
            let activo = viva == .escalar
            let lado: CGFloat = activo ? 12 : 9
            let centro = punto(escala)
            let base = punto(gizmo.centroEnPantalla(camara: camara, aspecto: aspecto) ?? escala)

            let tallo = NSBezierPath()
            tallo.lineWidth = 1.5
            NSColor(white: 0.85, alpha: activo ? 0.9 : 0.45).setStroke()
            tallo.move(to: base)
            tallo.line(to: centro)
            tallo.stroke()

            NSColor(white: activo ? 1.0 : 0.86, alpha: 0.95).setFill()
            NSBezierPath(roundedRect: NSRect(
                x: centro.x - lado / 2, y: centro.y - lado / 2, width: lado, height: lado
            ), xRadius: 2, yRadius: 2).fill()
        }
    }

    /// De coordenadas de proyección a puntos de la vista. La Y no se voltea: la vista no
    /// está volteada y su origen ya está abajo a la izquierda, como el de la proyección.
    private func punto(_ uv: SIMD2<Float>) -> NSPoint {
        NSPoint(
            x: (CGFloat(uv.x) + 1) * 0.5 * bounds.width,
            y: (CGFloat(uv.y) + 1) * 0.5 * bounds.height
        )
    }

    /// Rojo, verde y azul: es la convención de todas las herramientas 3D y viene aprendida
    /// de fuera. Desaturados para que convivan con el grafito del visor.
    ///
    /// El asa viva va en blanco. Se distingue **pasar por encima** de tenerla agarrada por
    /// la opacidad, no por el color: al pasar el ratón el blanco es más tenue, y así el
    /// resalte anuncia lo que va a pasar sin gritar como si ya estuviera pasando.
    private func color(_ eje: Gizmo.Eje, activo: Bool, agarrada: Bool) -> NSColor {
        if activo {
            return NSColor(red: 0.98, green: 0.99, blue: 1.0, alpha: agarrada ? 1 : 0.82)
        }
        switch eje {
        case .x: return NSColor(red: 0.91, green: 0.36, blue: 0.38, alpha: 0.92)
        case .y: return NSColor(red: 0.45, green: 0.82, blue: 0.42, alpha: 0.92)
        case .z: return NSColor(red: 0.36, green: 0.62, blue: 0.95, alpha: 0.92)
        }
    }
}

struct VisorMetal: NSViewRepresentable {

    let editor: Editor
    let alCrear: (Renderizador) -> Void
    let alPinchar: (SIMD3<Float>, SIMD3<Float>) -> Void
    let alEmpezarAEmpujar: (SIMD3<Float>, SIMD3<Float>) -> (normal: SIMD3<Float>, distancia: Float)?
    let alEmpujar: (Float) -> Void
    let alEmpezarAMoverPlano: (SIMD3<Float>, SIMD3<Float>) -> (normal: SIMD3<Float>, distancia: Float)?
    let alMoverPlano: (Float) -> Void
    let alEmpezarAEsculpir: (SIMD3<Float>, SIMD3<Float>) -> Bool
    let alContinuarEsculpiendo: (SIMD3<Float>, SIMD3<Float>) -> Void
    let alTerminarDeEsculpir: () -> Void
    let alSoltarStl: (String) -> Void

    /// Centro del gizmo en el mundo. Cambia con la selección y con cada edición, así que
    /// llega por aquí y no por una llamada suelta: SwiftUI ya sabe cuándo ha cambiado.
    let centroDelGizmo: SIMD3<Float>?
    let alEmpezarGestoDelGizmo: () -> Void
    let alMoverConGizmo: (SIMD3<Float>, Float) -> Void
    let alGirarConGizmo: (SIMD3<Float>, Float) -> Void
    let alEscalarConGizmo: (Float) -> Void

    func makeNSView(context: Context) -> VistaMetalInteractiva {
        let vista = VistaMetalInteractiva()
        vista.alEmpezarGestoDelGizmo = alEmpezarGestoDelGizmo
        vista.alMoverConGizmo = alMoverConGizmo
        vista.alGirarConGizmo = alGirarConGizmo
        vista.alEscalarConGizmo = alEscalarConGizmo
        vista.alPinchar = alPinchar
        vista.alEmpezarAEmpujar = alEmpezarAEmpujar
        vista.alEmpujar = alEmpujar
        vista.alEmpezarAMoverPlano = alEmpezarAMoverPlano
        vista.alMoverPlano = alMoverPlano
        vista.alEmpezarAEsculpir = alEmpezarAEsculpir
        vista.alContinuarEsculpiendo = alContinuarEsculpiendo
        vista.alTerminarDeEsculpir = alTerminarDeEsculpir
        vista.alSoltarStl = alSoltarStl
        // Sin esto la vista no recibe ni un solo mensaje de arrastre, y las tres
        // funciones de arriba no llegan a ejecutarse nunca: registrarse por el tipo
        // que se acepta es lo que mete a la vista en la cadena del arrastre.
        vista.registerForDraggedTypes([.fileURL])
        // La cadencia objetivo la manda la pantalla, no una constante optimista:
        // pedir 120 en un monitor de 60 haría creer al gobernador que va tarde
        // cuando en realidad solo está esperando al vsync.
        vista.preferredFramesPerSecond = NSScreen.main?.maximumFramesPerSecond ?? 60
        vista.isPaused = false
        vista.enableSetNeedsDisplay = false

        guard let renderizador = Renderizador(vista: vista, editor: editor) else {
            NSLog("Yunkil: no hay Metal disponible en este equipo")
            return vista
        }
        vista.delegate = renderizador
        vista.renderizador = renderizador
        alCrear(renderizador)
        vista.centroDelGizmo = centroDelGizmo
        return vista
    }

    func updateNSView(_ vista: VistaMetalInteractiva, context: Context) {
        // Las clausuras se vuelven a poner: capturan el estado y SwiftUI las recrea.
        vista.alEmpezarGestoDelGizmo = alEmpezarGestoDelGizmo
        vista.alMoverConGizmo = alMoverConGizmo
        vista.alGirarConGizmo = alGirarConGizmo
        vista.alEscalarConGizmo = alEscalarConGizmo
        vista.centroDelGizmo = centroDelGizmo
        // Redibujar aunque el centro no haya cambiado: girar la pieza sobre su propio
        // centro no lo mueve, y el gizmo tiene que enterarse igual.
        vista.refrescarGizmo()
    }
}

// MARK: - Vista aplanada del árbol

/// Copia inmutable de una fila del documento, con identidad estable para SwiftUI.
struct Fila: Identifiable, Equatable {
    let id: String
    let nombre: String
    let tipo: String
    let etiquetaTipo: String
    let profundidad: Int
    let visible: Bool
    let esOperacion: Bool
    let numeroDeHijos: Int

    init(_ f: FilaArbol) {
        id = f.id
        nombre = f.nombre
        tipo = f.tipo
        etiquetaTipo = f.etiquetaTipo
        profundidad = Int(f.profundidad)
        visible = f.visible
        esOperacion = f.esOperacion
        numeroDeHijos = Int(f.numeroDeHijos)
    }
}

struct Parametro: Identifiable, Equatable {
    var id: String { clave }
    let clave: String
    let etiqueta: String
    let valor: Float
    let minimo: Float
    let maximo: Float
    let unidad: String

    init(_ p: ParametroVisible) {
        clave = p.clave
        etiqueta = p.etiqueta
        valor = p.valor
        minimo = p.minimo
        maximo = p.maximo
        unidad = p.unidad
    }
}

// MARK: - Estado de la aplicación

@MainActor
final class EstadoDeLaApp: ObservableObject {

    let editor = Editor(inicial: ModelosDemo.shared.esferaSuelta())
    let aiSettings = AISettings()

    @Published private(set) var filas: [Fila] = []
    @Published private(set) var parametros: [Parametro] = []

    /// Cómo se lee el encaje de la pieza seleccionada, o `nil` si su cota es libre.
    @Published private(set) var encajeDescrito: String? = nil

    /// Cotas que manda un encaje. El inspector las enseña, pero no las deja arrastrar:
    /// un deslizador que se rechaza al soltarlo es peor que un deslizador que no está.
    @Published private(set) var clavesGobernadas: Set<String> = []
    @Published var seleccion: String = ""
    @Published var estadoDelRender = "iniciando…"
    @Published var aviso: String?
    @Published private(set) var recompiloElUltimoCambio = false
    @Published var resolucionExportacion: Float = 0.5
    @Published private(set) var exportando = false
    @Published private(set) var progresoExportacion: Float = 0
    @Published var resultadoExportacion: String?
    @Published var peticionIA = ""
    @Published private(set) var iaTrabajando = false
    @Published var resultadoIA: String?
    @Published var motorDeIA: MotorDeIA = .parametrico
    @Published var propuestaOrganica: PropuestaOrganica?
    @Published var modoBrochaOrganica = "NINGUNA"
    @Published var radioBrochaOrganica: Float = 4
    @Published var simetriaBrochaOrganica = true
    @Published var fuerzaBrochaOrganica: Float = 0.5
    @Published var ejeDeSimetriaOrganica = "X"
    @Published var fusionOrganica: Float = 2

    /// Las brochas que deforman el campo en vez de sumar o restar bolas.
    static let brochasDeCampo: Set<String> = ["ALISAR", "PELLIZCAR", "MOVER", "PROTEGER"]

    /// La propuesta que está en pantalla esperando decisión. Nada se aplica sin esto.
    @Published var propuestaPendiente: PropuestaPendiente?

    /// Imagen que acompaña a la petición, y la cota real que la pone a escala.
    ///
    /// Van juntas porque por separado no sirven: la imagen da la forma y las
    /// proporciones, pero una foto no tiene escala —el mismo soporte puede ser de
    /// móvil o de camión— y sin una medida el modelo se inventaría los milímetros.
    @Published private(set) var imagenDeReferencia: ImagenDeReferencia?
    @Published private(set) var nombreDeLaImagen: String?
    @Published var medidaDeReferencia = ""

    /// La última propuesta aplicada, viva solo hasta que se sabe si se deshace.
    private var ultimaPropuestaAplicada: Propuesta?
    private var momentoDeAplicar: Date?

    // Analizador de fabricación.
    @Published var perfilDeFabricacion = UserDefaults.standard.string(forKey: "fab.perfil")
        ?? PerfilFabricacion.companion.PREDETERMINADO.nombre
    @Published var analizarAlCrear = UserDefaults.standard.object(forKey: "fab.auto") as? Bool ?? true
    @Published private(set) var informe: InformeDeFabricacion?
    @Published private(set) var analizando = false

    /// Hay una malla horneándose en otro hilo. Sirve para no lanzar dos a la vez y para
    /// decirlo en pantalla: sin señal, arrastrar un STL grande parece no hacer nada.
    @Published private(set) var importandoMalla = false
    @Published private(set) var orientaciones: [OrientacionEvaluada] = []

    // Transformación de la pieza seleccionada, en milímetros y grados.
    @Published var posX: Float = 0
    @Published var posY: Float = 0
    @Published var posZ: Float = 0
    @Published var giroX: Float = 0
    @Published var giroY: Float = 0
    @Published var giroZ: Float = 0
    @Published var escala: Float = 1
    @Published var eje: String = "X"
    @Published var cuenta: Int = 3
    @Published var nombre: String = ""

    private weak var renderizador: Renderizador?
    private var tareaIA: Task<Void, Never>?

    var puedeDeshacer: Bool { editor.puedeDeshacer }
    var puedeRehacer: Bool { editor.puedeRehacer }
    var tipoSeleccionado: String { filas.first { $0.id == seleccion }?.tipo ?? "" }
    var celdasDeExportacion: Int64 { editor.celdasEstimadas(resolucion: resolucionExportacion) }

    init() {
        resolucionExportacion = editor.resolucionSugerida()
        // Los perfiles calibrados del usuario, antes de nada: el que quedó elegido la última
        // vez puede ser uno de ellos, y sin cargarlos la aplicación arrancaría con la
        // impresora de fábrica sin decir nada.
        _ = editor.cargarPerfiles(ruta: Self.rutaDePerfiles)
        if !editor.perfilesDeFabricacion().contains(perfilDeFabricacion) {
            perfilDeFabricacion = PerfilFabricacion.companion.PREDETERMINADO.nombre
        }
        _ = editor.usarPerfil(nombre: perfilDeFabricacion)
        refrescar(recompilo: true)
    }

    func registrar(_ r: Renderizador) {
        renderizador = r
        r.alActualizarEstado = { [weak self] texto in
            Task { @MainActor in self?.estadoDelRender = texto }
        }
    }

    // MARK: Sincronización

    private func refrescar(recompilo: Bool) {
        filas = editor.filas().map(Fila.init)
        seleccion = editor.seleccionado ?? filas.first?.id ?? ""
        recompiloElUltimoCambio = recompilo
        aviso = editor.ultimoError
        refrescarInspector()
        renderizador?.sincronizar(recompilar: recompilo)
    }

    private func refrescarInspector() {
        guard !seleccion.isEmpty else {
            parametros = []
            encajeDescrito = nil
            clavesGobernadas = []
            return
        }
        parametros = editor.parametrosDe(id: seleccion).map(Parametro.init)
        encajeDescrito = editor.descripcionDeEncaje(piezaId: seleccion)
        clavesGobernadas = encajeDescrito == nil ? [] : Set(
            parametros.map(\.clave).filter { editor.gobernadaPorEncaje(id: seleccion, clave: $0) }
        )

        let t = editor.transformDe(id: seleccion).map { $0.floatValue }
        if t.count == 7 {
            // Un giro que vale −0,0000001 se enseña como «−0», que no significa nada y
            // desconcierta en una casilla que además se puede editar. Se limpia al leer,
            // no al escribir: el número que se enseña y el que se edita son el mismo.
            func casiCero(_ v: Float) -> Float { abs(v) < 5e-4 ? 0 : v }
            posX = casiCero(t[0]); posY = casiCero(t[1]); posZ = casiCero(t[2])
            giroX = casiCero(t[3]); giroY = casiCero(t[4]); giroZ = casiCero(t[5])
            escala = t[6]
        }
        eje = editor.ejeDe(id: seleccion)
        cuenta = Int(editor.cuentaDe(id: seleccion))
        nombre = editor.nombreDe(id: seleccion)
        if editor.esEscultura(id: seleccion) {
            fusionOrganica = editor.fusionDeEscultura(id: seleccion)
        }
    }

    // MARK: Acciones

    func seleccionar(_ id: String) {
        editor.seleccionar(id: id)
        seleccion = id
        refrescarInspector()
    }

    /// Pinchar en el viewport selecciona la pieza que hay debajo del cursor.
    ///
    /// Decide el núcleo, con el mismo campo que exporta y analiza, así que lo que se
    /// selecciona es exactamente lo que se ve. Si el rayo no da en nada la selección se
    /// queda como estaba: vaciarla dejaría el inspector en blanco y obligaría a volver
    /// al árbol para recuperar el trabajo, que es peor que no hacer nada.
    func senalar(origen: SIMD3<Float>, direccion: SIMD3<Float>) {
        guard let impacto = editor.senalar(
            ox: origen.x, oy: origen.y, oz: origen.z,
            dx: direccion.x, dy: direccion.y, dz: direccion.z
        ) else { return }
        ultimoPunto = (impacto.x, impacto.y, impacto.z)
        ultimaNormal = (impacto.nx, impacto.ny, impacto.nz)
        seleccionar(impacto.piezaId)
    }

    private func selloOrganico(origen: SIMD3<Float>, direccion: SIMD3<Float>) -> Bool {
        guard modoBrochaOrganica != "NINGUNA",
              let impacto = editor.senalar(
                ox: origen.x, oy: origen.y, oz: origen.z,
                dx: direccion.x, dy: direccion.y, dz: direccion.z
              ), editor.esEscultura(id: impacto.piezaId) else { return false }
        seleccionar(impacto.piezaId)
        let esInflado = modoBrochaOrganica == "INFLAR"
        let desplazamiento = esInflado ? radioBrochaOrganica * 0.45 : 0
        let cambiado = editor.aplicarBrochaOrganica(
            id: impacto.piezaId, modo: esInflado ? "AGREGAR" : modoBrochaOrganica,
            x: impacto.x + impacto.nx * desplazamiento,
            y: impacto.y + impacto.ny * desplazamiento,
            z: impacto.z + impacto.nz * desplazamiento,
            radio: radioBrochaOrganica, simetriaX: simetriaBrochaOrganica
        )
        if cambiado { refrescar(recompilo: true) } else { aviso = editor.ultimoError }
        return cambiado
    }

    /// Alisar, pellizcar y mover: las brochas que tocan el campo, no el conjunto.
    ///
    /// Alisar y pellizcar se sellan como las de volumen, muestra a muestra sobre la
    /// superficie. Mover no: es un gesto de agarrar y tirar, así que el sitio se fija al
    /// pulsar y lo que cambia con el ratón es el vector. Sin eso, arrastrar dejaría una
    /// hilera de tirones cortos en lugar de un solo desplazamiento.
    private func deformacionOrganica(
        origen: SIMD3<Float>, direccion: SIMD3<Float>, continuando: Bool
    ) -> Bool {
        if modoBrochaOrganica == "MOVER" {
            return arrastreOrganico(origen: origen, direccion: direccion, continuando: continuando)
        }
        guard let impacto = editor.senalar(
            ox: origen.x, oy: origen.y, oz: origen.z,
            dx: direccion.x, dy: direccion.y, dz: direccion.z
        ), editor.esEscultura(id: impacto.piezaId) else { return false }
        seleccionar(impacto.piezaId)
        // El pellizco aprieta contra la normal de la superficie; el alisado no la usa y
        // le llega igualmente, que es más barato que dos caminos casi idénticos.
        let aplicado = editor.aplicarDeformacionOrganica(
            id: impacto.piezaId, modo: modoBrochaOrganica,
            x: impacto.x, y: impacto.y, z: impacto.z,
            radio: radioBrochaOrganica, intensidad: fuerzaBrochaOrganica,
            dx: impacto.nx, dy: impacto.ny, dz: impacto.nz,
            simetriaX: simetriaBrochaOrganica, continuandoTrazo: continuando
        )
        if aplicado { refrescar(recompilo: true) } else { aviso = editor.ultimoError }
        return aplicado
    }

    private func arrastreOrganico(
        origen: SIMD3<Float>, direccion: SIMD3<Float>, continuando: Bool
    ) -> Bool {
        if !continuando || anclaDeArrastre == nil {
            guard let impacto = editor.senalar(
                ox: origen.x, oy: origen.y, oz: origen.z,
                dx: direccion.x, dy: direccion.y, dz: direccion.z
            ), editor.esEscultura(id: impacto.piezaId) else { return false }
            seleccionar(impacto.piezaId)
            // El plano de arrastre es el de la pantalla en el momento de agarrar: es el
            // único en el que el cursor y el material se mueven a la vez. Fijarlo aquí y
            // no recalcularlo evita que orbitar a medio gesto tuerza el tirón.
            anclaDeArrastre = (
                punto: SIMD3<Float>(impacto.x, impacto.y, impacto.z),
                normal: -direccion,
                pieza: impacto.piezaId
            )
            return true
        }

        guard let ancla = anclaDeArrastre else { return false }
        let denominador = simd_dot(direccion, ancla.normal)
        guard abs(denominador) > 1e-5 else { return false }
        let t = simd_dot(ancla.punto - origen, ancla.normal) / denominador
        guard t > 0 else { return false }
        let destino = origen + direccion * t
        let gesto = destino - ancla.punto

        let aplicado = editor.aplicarDeformacionOrganica(
            id: ancla.pieza, modo: "MOVER",
            x: ancla.punto.x, y: ancla.punto.y, z: ancla.punto.z,
            radio: radioBrochaOrganica, intensidad: 0,
            dx: gesto.x, dy: gesto.y, dz: gesto.z,
            simetriaX: simetriaBrochaOrganica, continuandoTrazo: true
        )
        if aplicado { refrescar(recompilo: true) } else { aviso = editor.ultimoError }
        return aplicado
    }

    private func brochazo(origen: SIMD3<Float>, direccion: SIMD3<Float>, continuando: Bool) -> Bool {
        if EstadoDeLaApp.brochasDeCampo.contains(modoBrochaOrganica) {
            return deformacionOrganica(origen: origen, direccion: direccion, continuando: continuando)
        }
        return selloOrganico(origen: origen, direccion: direccion)
    }

    func empezarAEsculpir(origen: SIMD3<Float>, direccion: SIMD3<Float>) -> Bool {
        guard modoBrochaOrganica != "NINGUNA" else { return false }
        anclaDeArrastre = nil
        let inicio = editor.abrirTransaccion()
        inicioDelTrazoOrganico = inicio
        if brochazo(origen: origen, direccion: direccion, continuando: false) { return true }
        editor.revertirTransaccion(punto: inicio)
        inicioDelTrazoOrganico = nil
        anclaDeArrastre = nil
        return false
    }

    func continuarEsculpiendo(origen: SIMD3<Float>, direccion: SIMD3<Float>) {
        _ = brochazo(origen: origen, direccion: direccion, continuando: true)
    }

    func terminarDeEsculpir() {
        editor.cerrarTransaccion()
        inicioDelTrazoOrganico = nil
        anclaDeArrastre = nil
        refrescar(recompilo: true)
    }

    /// El plano espejo de la escultura seleccionada, contado para el panel.
    var planoDeSimetria: (normal: SIMD3<Float>, punto: SIMD3<Float>)? {
        guard esEsculturaSeleccionada else { return nil }
        let v = editor.planoDeSimetriaDe(id: seleccion).map { $0.floatValue }
        guard v.count == 6 else { return nil }
        return (SIMD3<Float>(v[0], v[1], v[2]), SIMD3<Float>(v[3], v[4], v[5]))
    }

    /// La fuerza vive en rangos distintos según la brocha; al cambiar se recoloca.
    func ajustarFuerzaAlModo() {
        if modoBrochaOrganica != "PELLIZCAR", fuerzaBrochaOrganica < 0.05 {
            fuerzaBrochaOrganica = 0.5
        }
    }

    func aplicarEjeDeSimetria(_ eje: String) {
        guard esEsculturaSeleccionada else { return }
        // «Libre» no es un eje: es el plano que salga de la última cara señalada, que es
        // la única forma de elegir uno oblicuo sin teclear tres números.
        if eje == "LIBRE" { fijarSimetriaDesdeElPunto(); return }
        ejeDeSimetriaOrganica = eje
        if editor.fijarEjeDeSimetria(id: seleccion, eje: eje) {
            refrescar(recompilo: true)
        } else {
            aviso = editor.ultimoError
        }
    }

    /// El plano espejo desde la última cara señalada: normal y punto salen del picking.
    func fijarSimetriaDesdeElPunto() {
        guard esEsculturaSeleccionada, let p = ultimoPunto, let n = ultimaNormal else {
            aviso = "Señala antes una cara: de ahí salen la normal y el punto del plano."
            return
        }
        if editor.fijarPlanoDeSimetria(
            id: seleccion, nx: n.x, ny: n.y, nz: n.z, px: p.x, py: p.y, pz: p.z
        ) {
            ejeDeSimetriaOrganica = "LIBRE"
            refrescar(recompilo: true)
        } else {
            aviso = editor.ultimoError
        }
    }

    /// Cuántas zonas guarda la escultura de la brocha elegida.
    var zonasDeLaBrocha: Int {
        guard esEsculturaSeleccionada,
              EstadoDeLaApp.brochasDeCampo.contains(modoBrochaOrganica) else { return 0 }
        return Int(editor.zonasDeEscultura(id: seleccion, modo: modoBrochaOrganica))
    }

    func limpiarZonasDeLaBrocha() {
        guard esEsculturaSeleccionada,
              EstadoDeLaApp.brochasDeCampo.contains(modoBrochaOrganica) else { return }
        if editor.limpiarDeformacionOrganica(id: seleccion, modo: modoBrochaOrganica) {
            refrescar(recompilo: true)
        } else {
            aviso = editor.ultimoError
        }
    }

    /// Último punto señalado en el viewport, en milímetros del mundo.
    /// Es lo que convierte «este canto» en coordenadas sin teclear ninguna.
    private(set) var ultimoPunto: (x: Float, y: Float, z: Float)?
    private var inicioDelTrazoOrganico: Documento?
    private var anclaDeArrastre: (punto: SIMD3<Float>, normal: SIMD3<Float>, pieza: String)?

    var puedeFiletear: Bool { ultimoPunto != nil }

    func aplicarFusionOrganica(_ valor: Float) {
        guard esEsculturaSeleccionada else { return }
        _ = editor.fijarFusionDeEscultura(id: seleccion, fusionMm: valor)
        if editor.ultimoError == nil {
            fusionOrganica = valor
            refrescar(recompilo: false)
        } else {
            aviso = editor.ultimoError
        }
    }

    /**
     Pone un filete en el último punto señalado.

     El alcance sale del tamaño del filete: una esfera de influencia del doble del radio
     deja el canto redondeado y la booleana exacta a partir de ahí. Pedir las dos cosas
     por separado sería exponer un detalle del campo que a nadie le importa.
     */
    func filetearEnElPuntoSenalado(_ radio: Float) {
        guard let p = ultimoPunto else {
            aviso = "Pincha primero el canto que quieras redondear"
            return
        }
        guard !seleccion.isEmpty else { return }
        let recompilo = editor.filetear(
            id: seleccion,
            x: p.x, y: p.y, z: p.z,
            radio: max(radio * 2, 0.2),
            tamano: radio
        )
        refrescar(recompilo: recompilo)
    }

    /// Quita el filete del booleano seleccionado.
    func quitarFilete() {
        guard !seleccion.isEmpty else { return }
        refrescar(recompilo: editor.quitarFilete(id: seleccion))
    }

    var seleccionTieneFilete: Bool {
        !seleccion.isEmpty && editor.tieneFilete(id: seleccion)
    }

    var esBooleanaSeleccionada: Bool {
        ["UNION", "DIFERENCIA", "INTERSECCION"].contains(tipoSeleccionado)
    }

    var radioDelFilete: Float {
        parametros.first { $0.clave == "fusion" }?.valor ?? 0
    }

    /// Cara que se está empujando con ⌘ y arrastrar.
    private var asaEnArrastre: (piezaId: String, asa: String)?

    /// Agarra la cara que hay bajo el cursor.
    ///
    /// Decide el núcleo qué cara es: la interfaz solo tiene una normal del mundo, y pasar
    /// de ahí al mando que le corresponde es geometría —hay que llevar la normal al
    /// espacio local de la pieza— y por tanto no es asunto de la vista.
    func empezarAEmpujar(
        origen: SIMD3<Float>,
        direccion: SIMD3<Float>
    ) -> (normal: SIMD3<Float>, distancia: Float)? {
        guard let impacto = editor.senalar(
            ox: origen.x, oy: origen.y, oz: origen.z,
            dx: direccion.x, dy: direccion.y, dz: direccion.z
        ) else { return nil }

        guard let asa = editor.asaParaNormal(
            id: impacto.piezaId,
            nx: impacto.nx, ny: impacto.ny, nz: impacto.nz
        ) else {
            seleccionar(impacto.piezaId)
            aviso = "Esta pieza no tiene caras que empujar; cambia sus medidas en el inspector"
            return nil
        }

        seleccionar(impacto.piezaId)
        asaEnArrastre = (impacto.piezaId, asa)
        // El punto de deshacer se marca aquí, así que todo el arrastre es un solo ⌘Z.
        editor.confirmarEdicionContinua()
        return (SIMD3<Float>(impacto.nx, impacto.ny, impacto.nz), impacto.distancia)
    }

    /// Un fotograma del arrastre: reescribe la cota y no anota historial.
    func empujar(milimetros: Float) {
        guard let arrastre = asaEnArrastre, milimetros != 0 else { return }
        let recompilo = editor.empujar(
            id: arrastre.piezaId,
            asaNombre: arrastre.asa,
            milimetros: milimetros,
            registrarEnHistorial: false
        )
        recompiloElUltimoCambio = recompilo
        aviso = editor.ultimoError
        refrescarInspector()
        renderizador?.sincronizar(recompilar: recompilo)
    }

    // MARK: Gizmo

    /// Dónde va el gizmo, o `nil` si no toca dibujarlo.
    ///
    /// No sale con la raíz seleccionada —mover el documento entero no significa nada— ni
    /// con una brocha orgánica activa: ahí el clic es del pincel, y unas flechas encima de
    /// la pieza solo estorbarían el trazo.
    var centroDelGizmo: SIMD3<Float>? {
        guard !seleccion.isEmpty, seleccion != raizId, modoBrochaOrganica == "NINGUNA",
              let c = editor.centroEnElMundo(id: seleccion), c.count == 3 else { return nil }
        return SIMD3<Float>(c[0].floatValue, c[1].floatValue, c[2].floatValue)
    }

    /// El punto de deshacer del gesto entero, como en el resto de arrastres.
    func empezarGestoDelGizmo() { editor.confirmarEdicionContinua() }

    func moverConGizmo(eje: SIMD3<Float>, milimetros: Float) {
        guard !seleccion.isEmpty, milimetros != 0 else { return }
        aplicarGesto(editor.moverEnElMundo(
            id: seleccion, x: eje.x, y: eje.y, z: eje.z, milimetros: milimetros
        ))
    }

    func girarConGizmo(eje: SIMD3<Float>, grados: Float) {
        guard !seleccion.isEmpty, grados != 0 else { return }
        aplicarGesto(editor.girarEnElMundo(
            id: seleccion, x: eje.x, y: eje.y, z: eje.z, grados: grados
        ))
    }

    func escalarConGizmo(factor: Float) {
        guard !seleccion.isEmpty, factor != 1 else { return }
        aplicarGesto(editor.escalarEnElMundo(id: seleccion, factor: factor))
    }

    /// Un fotograma del arrastre del gizmo: el inspector se refresca porque sus casillas
    /// de posición y giro son las mismas que el gizmo está moviendo, y verlas quietas
    /// mientras la pieza se mueve haría dudar de cuál de las dos manda.
    private func aplicarGesto(_ recompilo: Bool) {
        recompiloElUltimoCambio = recompilo
        aviso = editor.ultimoError
        refrescarInspector()
        renderizador?.sincronizar(recompilar: recompilo)
    }

    func anadir(_ tipo: String) {
        let recompilo = editor.anadir(tipoNombre: tipo, padreId: seleccion.isEmpty ? nil : seleccion)
        refrescar(recompilo: recompilo)
        renderizador?.encuadrar()
    }

    func eliminar() {
        guard !seleccion.isEmpty else { return }
        refrescar(recompilo: editor.eliminar(id: seleccion))
    }

    func duplicar() {
        guard !seleccion.isEmpty else { return }
        refrescar(recompilo: editor.duplicar(id: seleccion))
    }

    // MARK: Interacciones de modelado

    var raizId: String { editor.raizId }

    /// Lo copiado, como texto. Va en el estado y no en el portapapeles del sistema para no
    /// pisar lo que el usuario tenga copiado fuera de la aplicación.
    private var portapapeles: String?
    var hayAlgoQuePegar: Bool { portapapeles != nil }

    func copiar(_ id: String) {
        portapapeles = editor.copiar(id: id)
        aviso = editor.ultimoError
        objectWillChange.send()
    }

    func pegar(en destino: String?) {
        guard let portapapeles else { return }
        refrescar(recompilo: editor.pegar(texto: portapapeles, destinoId: destino))
    }

    func aislar(_ id: String) {
        refrescar(recompilo: editor.aislar(id: id))
    }

    func mostrarTodo() {
        refrescar(recompilo: editor.mostrarTodo())
    }

    func extraer(_ id: String) {
        refrescar(recompilo: editor.extraer(id: id))
    }

    /// Apoya en el plato la cara que se pinchó por última vez.
    ///
    /// Es la orientación para imprimir hecha con el cursor en vez de con tres campos de
    /// grados: se señala la cara que va abajo y la pieza se gira y baja al plato.
    func apoyarLaCaraSenalada() {
        guard let n = ultimaNormal, !seleccion.isEmpty else {
            aviso = "Pincha primero la cara que quieras apoyar"
            return
        }
        refrescar(recompilo: editor.apoyarEnElPlato(id: seleccion, nx: n.x, ny: n.y, nz: n.z))
    }

    /// Última normal señalada en el viewport, en el mundo.
    private(set) var ultimaNormal: (x: Float, y: Float, z: Float)?

    func mirarDesde(_ vista: CamaraOrbital.Vista) {
        renderizador?.camara.mirarDesde(vista)
    }

    func alternarOrtografica() {
        renderizador?.camara.ortografica.toggle()
        objectWillChange.send()
    }

    var esOrtografica: Bool { renderizador?.camara.ortografica ?? false }

    // MARK: Sección en vivo

    /// El plano de sección, cuando está activo. Es estado de vista: no entra en el
    /// documento ni en el historial, igual que la cámara.
    @Published private(set) var planoDeSeccion: PlanoDeSeccion?
    var seccionActiva: Bool { planoDeSeccion != nil }

    /// Activa la sección con un plano que corta por el centro del modelo y mira
    /// hacia la cámara: es lo que enseña el corte de inmediato, sin teclear nada.
    func alternarSeccion() {
        if seccionActiva {
            planoDeSeccion = nil
            renderizador?.planoDeSeccion = nil
            objectWillChange.send()
            return
        }
        let mn = editor.cotaMinima.map { $0.floatValue }
        let mx = editor.cotaMaxima.map { $0.floatValue }
        guard mn.count == 3, mx.count == 3 else {
            aviso = "No hay modelo que cortar"
            return
        }
        let centro = SIMD3<Float>(
            (mn[0] + mx[0]) * 0.5, (mn[1] + mx[1]) * 0.5, (mn[2] + mx[2]) * 0.5
        )
        // La normal apunta a la cámara: el corte se ve de frente la primera vez.
        let normal = simd_normalize(renderizador?.camara.objetivo ?? SIMD3<Float>(0, 0, 1)
            - (renderizador?.camara.posicion ?? SIMD3<Float>(0, 100, 200)))
        planoDeSeccion = PlanoDeSeccion(
            punto: centro,
            normal: normal,
            grosorMinimoPared: editor.grosorMinimoDePared(nombrePerfil: perfilDeFabricacion)
        )
        renderizador?.planoDeSeccion = planoDeSeccion
        objectWillChange.send()
    }

    /// Coloca el plano de sección sobre el último punto pinchado y mirando a la
    /// cámara. Es «sección por este plano» del clic derecho: el corte pasa por
    /// donde el usuario señaló.
    func seccionPorElPuntoSenalado() {
        guard let p = ultimoPunto else {
            aviso = "Pincha primero el punto por donde quieras cortar"
            return
        }
        if !seccionActiva { alternarSeccion() }
        guard var plano = planoDeSeccion else { return }
        plano.punto = SIMD3<Float>(p.x, p.y, p.z)
        planoDeSeccion = plano
        renderizador?.planoDeSeccion = plano
        objectWillChange.send()
    }

    /// ¿Cae el rayo en el rectángulo visible del plano? Si sí, devuelve la normal
    /// y la distancia al cruce para arrastrarlo. Es la versión del plano de
    /// `empezarAEmpujar`: el mismo patrón de agarrar la superficie.
    func empezarAMoverPlano(
        origen: SIMD3<Float>,
        direccion: SIMD3<Float>
    ) -> (normal: SIMD3<Float>, distancia: Float)? {
        guard let plano = planoDeSeccion else { return nil }
        let denom = simd_dot(direccion, plano.normal)
        guard abs(denom) > 1e-5 else { return nil }
        let t = simd_dot(plano.punto - origen, plano.normal) / denom
        guard t > 0 else { return nil }
        return (plano.normal, t)
    }

    /// Un fotograma del arrastre del plano: se desplaza a lo largo de su normal.
    func moverPlano(milimetros: Float) {
        guard var plano = planoDeSeccion, milimetros != 0 else { return }
        plano.punto += plano.normal * milimetros
        planoDeSeccion = plano
        renderizador?.planoDeSeccion = plano
    }

    /// Encuadra solo la pieza seleccionada. Es la tecla F de cualquier herramienta 3D.
    func enfocarSeleccion() {
        guard !seleccion.isEmpty, let centro = editor.centroEnElMundo(id: seleccion) else { return }
        renderizador?.camara.objetivo = SIMD3<Float>(
            centro[0].floatValue, centro[1].floatValue, centro[2].floatValue
        )
    }

    /// Encuadra el modelo entero. Es ⇧F en cualquier herramienta 3D.
    func encuadrarTodo() { renderizador?.encuadrar() }

    func envolver(_ tipo: String) {
        guard !seleccion.isEmpty else { return }
        refrescar(recompilo: editor.envolver(id: seleccion, tipoNombre: tipo))
    }

    func desplazar(haciaArriba: Bool) {
        guard !seleccion.isEmpty else { return }
        refrescar(recompilo: editor.desplazar(id: seleccion, haciaArriba: haciaArriba))
    }

    func alternarVisibilidad(_ id: String) {
        let visible = editor.esVisible(id: id)
        refrescar(recompilo: editor.fijarVisible(id: id, visible: !visible))
    }

    /// Se llama en cada fotograma del arrastre: no debe recompilar ni anotar
    /// historial, solo reescribir uniforms.
    func fijarParametro(_ clave: String, _ valor: Float) {
        guard !seleccion.isEmpty else { return }
        let recompilo = editor.fijarParametro(id: seleccion, clave: clave, valor: valor)
        parametros = editor.parametrosDe(id: seleccion).map(Parametro.init)
        recompiloElUltimoCambio = recompilo
        renderizador?.sincronizar(recompilar: recompilo)
    }

    func empezarEdicionContinua() { editor.confirmarEdicionContinua() }

    /// Desata la pieza de su medida. No la mueve: deja de derivar su cota.
    func soltarEncaje() {
        guard !seleccion.isEmpty else { return }
        _ = editor.soltarEncaje(piezaId: seleccion)
        refrescar(recompilo: false)
    }

    func aplicarTransform() {
        guard !seleccion.isEmpty else { return }
        let recompilo = editor.fijarTransform(
            id: seleccion,
            x: posX, y: posY, z: posZ,
            giroX: giroX, giroY: giroY, giroZ: giroZ,
            escala: max(escala, 0.01)
        )
        recompiloElUltimoCambio = recompilo
        aviso = editor.ultimoError
        renderizador?.sincronizar(recompilar: recompilo)
    }

    func aplicarNombre() {
        guard !seleccion.isEmpty else { return }
        _ = editor.renombrar(id: seleccion, nombre: nombre)
        filas = editor.filas().map(Fila.init)
        aviso = editor.ultimoError
    }

    func aplicarEje(_ nuevo: String) {
        eje = nuevo
        refrescar(recompilo: editor.fijarEje(id: seleccion, ejeNombre: nuevo))
    }

    func aplicarCuenta(_ nueva: Int) {
        cuenta = nueva
        refrescar(recompilo: editor.fijarCuenta(id: seleccion, cuenta: Int32(nueva)))
    }

    func deshacer() {
        // Un deshacer inmediato sobre lo que acaba de proponer la IA es la señal más
        // limpia de que la propuesta no valía: el usuario la aceptó por el diálogo,
        // la vio y se arrepintió. Vale mucho más que un descarte, donde solo llegó a
        // leer el resumen, así que se corrige el asiento antes de deshacer.
        if let propuesta = ultimaPropuestaAplicada,
           let aplicada = momentoDeAplicar,
           Date().timeIntervalSince(aplicada) < Self.ventanaDeArrepentimiento {
            anotar(propuesta, desenlace: "DESHECHO")
        }
        ultimaPropuestaAplicada = nil
        momentoDeAplicar = nil
        if editor.deshacer() { refrescar(recompilo: true) }
    }
    func rehacer() { if editor.rehacer() { refrescar(recompilo: true) } }

    func cargarEjemplo(_ cual: String) {
        let doc: Documento
        switch cual {
        case "esfera": doc = ModelosDemo.shared.esferaSuelta()
        case "rejilla": doc = ModelosDemo.shared.rejilla()
        case "vacio": doc = Documento.companion.vacio()
        default: doc = ModelosDemo.shared.soporte()
        }
        editor.reemplazarDocumento(nuevo: doc)
        refrescar(recompilo: true)
        renderizador?.encuadrar()
    }

    func encuadrar() { renderizador?.encuadrar() }

    // MARK: Archivo

    func guardar() {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "modelo.yunkil"
        panel.allowedContentTypes = [UTType(filenameExtension: "yunkil") ?? .json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let backup = url.appendingPathExtension("bak")
            if FileManager.default.fileExists(atPath: url.path) {
                try? FileManager.default.removeItem(at: backup)
                try FileManager.default.copyItem(at: url, to: backup)
            }
            try editor.aJson().write(to: url, atomically: true, encoding: .utf8)
        } catch {
            aviso = "No se pudo guardar: \(error.localizedDescription)"
        }
    }

    func exportarPieza() {
        let panel = NSSavePanel()
        panel.title = "Exportar pieza imprimible"
        panel.prompt = "Exportar"
        // El 3MF primero, y por eso es el nombre que aparece escrito: un STL no
        // declara en qué unidades está, y de esa omisión sale el clásico modelo que
        // entra en el laminador a 1/25 de su tamaño. El formato lo decide la
        // extensión que quede en el nombre, que es donde el usuario ya está
        // eligiendo; preguntarlo otra vez en un menú aparte sobra.
        panel.nameFieldStringValue = "pieza.3mf"
        panel.allowedContentTypes = [
            UTType(filenameExtension: "3mf") ?? .data,
            UTType(filenameExtension: "stl") ?? .data,
        ]
        guard panel.runModal() == .OK, let url = panel.url else { return }

        exportando = true
        progresoExportacion = 0
        resultadoExportacion = nil
        aviso = nil

        let editor = editor
        let resolucion = resolucionExportacion
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let certificado = editor.exportarPieza(
                ruta: url.path,
                resolucion: resolucion,
                alAvanzar: { avance in
                    DispatchQueue.main.async {
                        self?.progresoExportacion = avance.floatValue
                    }
                }
            )
            DispatchQueue.main.async {
                guard let self else { return }
                self.exportando = false
                self.progresoExportacion = 1
                if let certificado {
                    if certificado.apto && certificado.bytes > 0 {
                        self.resultadoExportacion = certificado.resumen()
                    } else if certificado.apto {
                        self.aviso = "La malla es válida, pero no se pudo escribir el archivo."
                    } else {
                        self.aviso = certificado.resumen()
                    }
                } else {
                    self.aviso = editor.ultimoError ?? "No hay material que exportar."
                }
            }
        }
    }

    /// Máximo de vueltas para que el modelo corrija su propio plan.
    ///
    /// Tres es lo que ha resultado útil: la primera suele fallar por formato, la
    /// segunda lo arregla, y si la tercera tampoco sale es que el modelo no sabe
    /// hacerlo y seguir insistiendo solo gasta tiempo y cuota.
    private static let rondasDeCorreccion = 3

    func adjuntarImagen() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        panel.message = "Una foto, un boceto o una captura de la pieza que quieres"
        guard panel.runModal() == .OK, let url = panel.url else { return }

        guard let imagen = ImagenDeReferencia.desde(url: url) else {
            resultadoIA = "No se pudo leer esa imagen."
            return
        }
        imagenDeReferencia = imagen
        nombreDeLaImagen = url.lastPathComponent
    }

    func quitarImagen() {
        imagenDeReferencia = nil
        nombreDeLaImagen = nil
    }

    var tienePerfilSeleccionado: Bool {
        !seleccion.isEmpty && editor.tienePerfil(id: seleccion)
    }

    func puntosDelPerfilSeleccionado() -> [CGPoint] {
        guard tienePerfilSeleccionado else { return [] }
        let valores = editor.contornoDe(id: seleccion).map { CGFloat(truncating: $0) }
        return stride(from: 0, to: valores.count - 1, by: 2).map {
            CGPoint(x: valores[$0], y: valores[$0 + 1])
        }
    }

    func guardarPerfil(_ puntos: [CGPoint]) -> String? {
        guard tienePerfilSeleccionado else { return "Selecciona una extrusión, revolución o barrido." }
        let coordenadas = puntos.flatMap {
            [KotlinFloat(float: Float($0.x)), KotlinFloat(float: Float($0.y))]
        }
        _ = editor.fijarPuntosDelPerfil(id: seleccion, coordenadas: coordenadas)
        if let error = editor.ultimoError { return error }
        refrescar(recompilo: true)
        return nil
    }

    func construirConIA() {
        if motorDeIA == .organico {
            construirOrganicoConIA()
            return
        }
        let peticion = peticionIA.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peticion.isEmpty, !iaTrabajando else { return }
        iaTrabajando = true
        resultadoIA = "Pensando…"
        tareaIA?.cancel()

        // Copiloto vs generador: con una pieza señalada y una petición que no pide
        // crear, el modelo edita lo que hay —con un tope de 3 operaciones, para que
        // la respuesta sea corta y rápida—. Sin selección, genera como siempre.
        let esOrdenDeEdicion = !seleccion.isEmpty && seleccion != raizId &&
            !esPeticionDeCrear(peticion)

        // El sistema y el contexto los redacta el núcleo: el catálogo de piezas y de
        // parámetros sale de la misma fuente que valida el plan, así que el modelo
        // nunca ve una opción que luego se le vaya a rechazar.
        let imagen = imagenDeReferencia
        let medida = medidaDeReferencia.trimmingCharacters(in: .whitespacesAndNewlines)
        // La petición viaja al constructor del prompt para que pueda elegir los
        // ejemplares que vienen a cuento: son planes verificados, y un modelo pequeño
        // usa una operación cuando la ha visto aplicada, no cuando la ha visto listada.
        let sistema = imagen == nil
            ? editor.instruccionesParaModelo(nombrePerfil: perfilDeFabricacion, peticion: peticion)
            : editor.instruccionesParaModeloConImagen(
                nombrePerfil: perfilDeFabricacion,
                medidaConocida: medida.isEmpty ? nil : medida,
                peticion: peticion
            )
        // El documento **y** el hilo. Antes solo iba el documento, así que cada petición
        // nacía sin memoria de la anterior: para decir «más grueso» había que
        // redescribir la pieza entera, porque «más» no se refería a nada.
        //
        // Se lee aquí, antes de anotar nada, porque `contextoConHilo` es también quien
        // declara si la persona ha tocado el modelo a mano desde el último turno, y
        // anotar primero pondría ese contador a cero.
        let contexto = editor.contextoConHilo(presupuestoDelHilo: 1200)
        let peticionCompleta = "Documento actual:\n\(contexto)\n\nPetición:\n\(peticion)"
        let versionBase = editor.versionDocumento
        let documentoBase = editor.aJson()
        let seleccionInicial = aiSettings.selection
        // El presupuesto se midió, no se estimó. Con una imagen y 1.500 tokens, el
        // modelo local con visión razonó el plan entero **y se quedó sin sitio antes
        // de escribir el JSON**: la respuesta se corta a mitad de un pensamiento y
        // desde fuera parece que el modelo no sabe hacerlo, cuando lo sabía.
        //
        // Los modelos de razonamiento gastan el grueso del presupuesto antes de
        // empezar a responder, así que hace falta sitio de sobra, y con imagen más
        // —describir lo que ve se lleva otra tanda—.
        //
        // En local el presupuesto es además un tope de tiempo: el 9B genera a 46 tokens
        // por segundo medidos, así que 8.000 tokens son casi tres minutos de reloj
        // cuando se desboca repitiéndose y no para hasta agotarlos.
        //
        // Se probó a bajarlo a 4.000 para acortar esas desbocadas y **se midió que
        // costaba caro**: dos casos del banco se cayeron por quedarse sin sitio, y no
        // eran desbocadas sino planes largos y buenos —un contorno entero ocupa—.
        // Acortar la correa a todos castiga justo a los que la necesitan, así que el
        // freno del desbocamiento tiene que reconocer el bucle, no recortar a ciegas.
        let presupuesto: Int
        if seleccionInicial.provider == .openCodeGo {
            presupuesto = imagen == nil ? 12_000 : 16_000
        } else {
            presupuesto = imagen == nil ? 8_000 : 12_000
        }

        tareaIA = Task {
            do {
                let seleccion = try await aiSettings.selectionForRequest(withImage: imagen != nil)
                var mensaje = peticionCompleta
                var aceptado: PlanInterpretado?
                var ultimoMotivo = "el modelo no llegó a responder"

                // Lo que el modelo no ve al escribir: qué sale de ejecutar su plan.
                // Se le devuelven las medidas del resultado hasta que la pieza se
                // sostenga, y solo entonces se le enseña la propuesta al usuario.
                var reparos: [String] = []
                var cosido: PlanDeModelado?
                var respuestaAceptada = ""
                var rondaAceptada = 0
                var mirada: Mirada?

                for ronda in 1...Self.rondasDeCorreccion {
                    try Task.checkCancellation()
                    if ronda > 1 { resultadoIA = "Corrigiendo el plan (intento \(ronda))…" }
                    // La imagen viaja en todas las rondas, no solo en la primera: si
                    // se le quitara al corregir, el modelo estaría arreglando de
                    // memoria una geometría que ya no ve, y la ronda 2 saldría peor
                    // que la 1 sin que nada lo explicara.
                    let texto = try await AsistenteLocal.pedirPlan(
                        system: sistema, user: mensaje, selection: seleccion,
                        maxTokens: presupuesto, imagen: imagen
                    )
                    try Task.checkCancellation()
                    guard editor.versionDocumento == versionBase else {
                        throw LocalAssistantError.documentChanged
                    }
                    let leido = editor.interpretarPlan(respuesta: texto, edicion: esOrdenDeEdicion)
                    guard leido.aceptado else {
                        ultimoMotivo = leido.motivoDelRechazo ?? "formato no reconocido"
                        mensaje = peticionCompleta + "\n\n" + editor.correccionParaModelo(
                            motivo: ultimoMotivo, respuestaAnterior: texto
                        )
                        continue
                    }

                    if leido.requiereDatos {
                        let preguntas = leido.preguntas.filter { !$0.isEmpty }
                        let textoDeAclaracion = preguntas.joined(separator: "\n")
                        editor.anotarPeticion(texto: peticion)
                        editor.anotarAclaracion(texto: textoDeAclaracion)
                        resultadoIA = textoDeAclaracion
                        iaTrabajando = false
                        return
                    }

                    // Dice «y mirándola» porque desde que existe el crítico visual la
                    // espera puede pasar de un segundo a diez: se dibujan cuatro vistas
                    // y las juzga un segundo modelo. Un mensaje que no cuenta lo que
                    // tarda convierte una comprobación en una aplicación colgada.
                    resultadoIA = "Comprobando la pieza y mirándola…"
                    let revision = await Self.revisar(
                        respuesta: texto,
                        documento: documentoBase,
                        perfil: perfilDeFabricacion,
                        edicion: esOrdenDeEdicion,
                        peticion: peticion,
                        referencia: imagen,
                        seleccion: seleccion
                    )
                    try Task.checkCancellation()
                    guard editor.versionDocumento == versionBase else {
                        throw LocalAssistantError.documentChanged
                    }
                    reparos = revision.motivos
                    cosido = revision.cosido
                    mirada = revision.mirada

                    aceptado = leido
                    respuestaAceptada = texto
                    rondaAceptada = ronda
                    if reparos.isEmpty { break }

                    ultimoMotivo = reparos.joined(separator: " · ")
                    mensaje = peticionCompleta + "\n\n" + editor.revisionParaModelo(
                        motivos: reparos, respuestaAnterior: texto
                    )
                }

                guard let leido = aceptado, let plan = leido.plan else {
                    throw LocalAssistantError.invalidPlan(ultimoMotivo)
                }

                // Aquí acaba el trabajo de la IA y empieza el del usuario. Antes esto
                // era un `NSAlert` de dos botones con el resumen que escribía el propio
                // modelo; ahora la propuesta se queda en pantalla, contada por el núcleo
                // operación a operación, y no se toca el documento hasta que alguien lo
                // diga. Ver `PanelDePropuesta.swift`.
                let aplicable = cosido ?? plan
                let lineas = editor.explicarPlan(plan: aplicable)
                propuestaPendiente = PropuestaPendiente(
                    registro: Propuesta(
                        id: UUID().uuidString,
                        peticion: peticion,
                        plan: respuestaAceptada,
                        rondas: rondaAceptada,
                        reparos: reparos
                    ),
                    plan: aplicable,
                    versionDocumento: versionBase,
                    lineas: lineas,
                    reemplaza: leido.reemplaza,
                    resumen: leido.resumen.isEmpty ? peticion : leido.resumen,
                    reparos: reparos,
                    avisos: leido.avisos,
                    cosidas: max(0, aplicable.operaciones.count - plan.operaciones.count),
                    mirada: mirada,
                    aceptadas: Set(lineas.map { Int($0.indice) })
                )
                previsualizar(propuestaPendiente)
                resultadoIA = nil
            } catch is CancellationError {
                resultadoIA = "Creación cancelada; no se aplicaron cambios incompletos."
            } catch {
                resultadoIA = nil
                aviso = "\(aiSettings.provider.name): \(error.localizedDescription)"
            }
            iaTrabajando = false
        }
    }

    func cancelarIA() { tareaIA?.cancel() }

    private func construirOrganicoConIA() {
        let peticion = peticionIA.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peticion.isEmpty, !iaTrabajando else { return }
        iaTrabajando = true
        resultadoIA = "Diseñando la anatomía de la figura…"
        propuestaOrganica = nil
        tareaIA?.cancel()
        let imagen = imagenDeReferencia
        let medida = medidaDeReferencia.trimmingCharacters(in: .whitespacesAndNewlines)
        let objetivoId = esEsculturaSeleccionada ? seleccion : nil
        let esculturaActual = objetivoId.flatMap { editor.contratoDeEscultura(id: $0) }

        tareaIA = Task {
            do {
                let seleccion = try await aiSettings.selectionForRequest(withImage: imagen != nil)
                let escala = medida.isEmpty ? "" : "\nMedida real conocida: \(medida)"
                let contexto = esculturaActual.map {
                    "\nEscultura actual editable. Devuelve el contrato COMPLETO modificado, conservando ids que no cambien:\n\($0)"
                } ?? ""
                let respuesta = try await AsistenteLocal.pedirPlan(
                    system: MotorOrganico.shared.instrucciones(),
                    user: peticion + escala + contexto,
                    selection: seleccion,
                    maxTokens: imagen == nil ? 8_000 : 12_000,
                    imagen: imagen
                )
                try Task.checkCancellation()
                let leido = MotorOrganico.shared.interpretar(respuesta: respuesta)
                guard leido.aceptado, let contrato = leido.contratoCanonico else {
                    throw LocalAssistantError.invalidPlan(leido.motivo ?? "contrato orgánico inválido")
                }
                propuestaOrganica = PropuestaOrganica(
                    nombre: leido.nombre.isEmpty ? "Figura orgánica" : leido.nombre,
                    contrato: contrato,
                    objetivoId: objetivoId
                )
                resultadoIA = "Anatomía lista: revisa y genera la malla."
            } catch is CancellationError {
                resultadoIA = "Generación orgánica cancelada."
            } catch {
                resultadoIA = nil
                aviso = error.localizedDescription
            }
            iaTrabajando = false
        }
    }

    func descartarPropuestaOrganica() {
        propuestaOrganica = nil
        resultadoIA = nil
    }

    func aceptarPropuestaOrganica() {
        guard let propuesta = propuestaOrganica, !iaTrabajando else { return }
        let aplicado = propuesta.objetivoId.map {
            editor.reemplazarEscultura(id: $0, contratoCanonico: propuesta.contrato)
        } ?? editor.anadirEscultura(contratoCanonico: propuesta.contrato, padreId: nil)
        if aplicado {
            propuestaOrganica = nil
            resultadoIA = "Escultura nativa añadida; puedes retocarla con las brochas."
            refrescar(recompilo: true)
            renderizador?.encuadrar()
            if analizarAlCrear { analizarFabricacion() }
        } else {
            aviso = editor.ultimoError ?? "No se pudo añadir la escultura."
        }
    }

    var esEsculturaSeleccionada: Bool {
        !seleccion.isEmpty && editor.esEscultura(id: seleccion)
    }

    // MARK: El hilo

    /// Lo último que se pidió, para enseñarlo encima del campo de texto.
    var hiloEnCurso: String? {
        guard let ultima = editor.conversacion.ultimaPeticion, !ultima.isEmpty else { return nil }
        return ultima
    }

    /// Corta el hilo sin tocar la pieza.
    ///
    /// Hace falta un botón porque no hay forma de deducirlo: seguir con la misma pieza
    /// para otra cosa completamente distinta es normal, y el hilo entonces estorba
    /// —el modelo arrastra una intención que ya no existe— sin que nada lo delate.
    func olvidarElHilo() {
        editor.olvidarConversacion()
        objectWillChange.send()
        resultadoIA = "Hilo olvidado; la pieza no cambió."
    }

    // MARK: Decidir sobre la propuesta

    /// Pone —o quita— el fantasma de la propuesta en el viewport.
    ///
    /// Hasta hoy la propuesta se leía en una lista y se aceptaba a ciegas: la única
    /// forma de saber qué iba a pasar era leer «Crea un cilindro de radio 8» y
    /// figurárselo. Ahora se ve sobre la pieza, en verde lo que se añade y en rojo lo
    /// que se quita, con el mismo `evaluar` que se exportaría.
    ///
    /// Se manda lo **marcado**, no la propuesta entera: las casillas cambian lo que se
    /// aplicaría, así que tienen que cambiar lo que se ve.
    private func previsualizar(_ propuesta: PropuestaPendiente?) {
        let recompilo: Bool
        if let p = propuesta {
            recompilo = editor.previsualizar(
                plan: p.plan,
                aceptadas: p.todasMarcadas
                    ? nil
                    : p.aceptadas.sorted().map { KotlinInt(int: Int32($0)) },
                nombrePerfil: perfilDeFabricacion
            )
        } else {
            recompilo = editor.previsualizar(
                plan: nil, aceptadas: nil, nombrePerfil: perfilDeFabricacion
            )
        }
        renderizador?.sincronizar(recompilar: recompilo)
    }

    /// Marca o desmarca una operación, arrastrando lo que dependa de ella.
    ///
    /// Las dos direcciones las resuelve el núcleo y no esta función, porque el grafo
    /// de dependencias sale de leer el plan: al **marcar** se arrastra hacia arriba lo
    /// que hace falta —pedir el taladro de una pieza que aún no se crea es pedir
    /// también su creación—, y al **desmarcar** se arrastra hacia abajo lo que se
    /// quedaría hablando de una pieza que no va a existir. Sin lo segundo el aplicador
    /// no fallaría: omitiría esas operaciones en silencio, que es peor.
    func alternarOperacion(_ indice: Int) {
        guard var p = propuestaPendiente else { return }
        if p.aceptadas.contains(indice) {
            p.aceptadas = Set(
                editor.podarSeleccion(
                    plan: p.plan,
                    marcadas: (p.aceptadas.subtracting([indice])).map { KotlinInt(int: Int32($0)) }
                ).map { Int(truncating: $0) }
            )
        } else {
            p.aceptadas = Set(
                editor.completarSeleccion(
                    plan: p.plan,
                    marcadas: (p.aceptadas.union([indice])).map { KotlinInt(int: Int32($0)) }
                ).map { Int(truncating: $0) }
            )
        }
        propuestaPendiente = p
        previsualizar(p)
    }

    func marcarTodasLasOperaciones(_ todas: Bool) {
        guard var p = propuestaPendiente else { return }
        p.aceptadas = todas ? Set(p.lineas.map { Int($0.indice) }) : []
        propuestaPendiente = p
        previsualizar(p)
    }

    func descartarPropuesta() {
        guard let p = propuestaPendiente else { return }
        propuestaPendiente = nil
        previsualizar(nil)
        anotar(p.registro, desenlace: "DESCARTADO", rechazadas: p.lineas.map { $0.texto })
        // Un descarte también es hilo, y del más informativo: «pediste esto, te propuse
        // aquello y lo tiraste entero». Callarlo dejaría al modelo repitiendo en el
        // turno siguiente lo mismo que se acaba de rechazar.
        editor.anotarPeticion(texto: p.registro.peticion)
        editor.anotarRespuesta(
            desenlaceNombre: "DESCARTADO",
            aplicadas: [],
            rechazadas: p.lineas.map { $0.texto }
        )
        resultadoIA = "Propuesta descartada; el documento no cambió."
    }

    /// Aplica lo marcado, como una sola transacción deshacible.
    func aceptarPropuesta() {
        guard let p = propuestaPendiente, !p.ningunaMarcada else { return }
        propuestaPendiente = nil
        // Antes de aplicar, no después: el shader se regenera dentro de `aplicarParte`,
        // y con el fantasma todavía puesto la pieza recién aceptada saldría pintada de
        // verde sobre sí misma hasta el siguiente cambio.
        previsualizar(nil)

        let resultado = editor.aplicarParteEnVersion(
            plan: p.plan,
            aceptadas: p.aceptadas.sorted().map { KotlinInt(int: Int32($0)) },
            versionEsperada: p.versionDocumento,
            nombrePerfil: perfilDeFabricacion
        )
        guard resultado.exito else {
            if resultado.error?.contains("documento cambió") == true {
                resultadoIA = resultado.error
            } else {
                aviso = resultado.error ?? "no se pudo aplicar la propuesta"
                anotar(p.registro, desenlace: "DESCARTADO")
            }
            return
        }

        // La bitácora distingue aceptar entero de aceptar parte, porque son dos juicios
        // distintos sobre el modelo: «acertó» y «acertó a medias». Mezclarlos borraría
        // justo la señal que hace falta para saber qué se le da mal.
        let aplicadas = p.lineas.filter { p.aceptadas.contains(Int($0.indice)) }.map { $0.texto }
        let rechazadas = p.lineas.filter { !p.aceptadas.contains(Int($0.indice)) }.map { $0.texto }
        let desenlace = p.todasMarcadas ? "APLICADO" : "PARCIAL"
        anotar(p.registro, desenlace: desenlace, rechazadas: rechazadas)
        // Los dos turnos entran juntos y aquí, no al mandar la petición: si entraran al
        // mandarla, un fallo de red dejaría en el hilo una pregunta sin respuesta y el
        // modelo la leería como algo que sí se hizo.
        editor.anotarPeticion(texto: p.registro.peticion)
        editor.anotarRespuesta(
            desenlaceNombre: desenlace,
            aplicadas: aplicadas,
            rechazadas: rechazadas
        )
        // Queda pendiente de arrepentimiento: si el siguiente deshacer llega enseguida,
        // el desenlace se corrige a DESHECHO.
        ultimaPropuestaAplicada = p.registro
        momentoDeAplicar = Date()

        peticionIA = ""
        var texto = p.todasMarcadas
            ? resultado.resumen
            : "\(p.aceptadas.count) de \(p.lineas.count) operaciones aplicadas"
        if !resultado.omitidas.isEmpty {
            texto += " · \(resultado.omitidas.count) omitidas: "
                + resultado.omitidas.prefix(2).joined(separator: "; ")
        }
        resultadoIA = texto
        refrescar(recompilo: true)
        renderizador?.encuadrar()
        if analizarAlCrear { analizarFabricacion() }
    }

    /// ¿Empieza la petición por una orden de creación, no de edición?
    ///
    /// «hazme una carcasa» y «crea un soporte» generan; «achaflana estas aristas» y
    /// «ponle un M4 aquí» editan. Se mira la primera palabra porque es donde la
    /// intención se dice primero, y porque un copiloto que malinterpreta una orden
    /// de edición como generación tiraría la pieza entera.
    private func esPeticionDeCrear(_ peticion: String) -> Bool {
        let normalizada = peticion.lowercased()
            .trimmingCharacters(in: .whitespacesAndNewlines.union(.punctuationCharacters))
        let primera = normalizada
            .split(separator: " ").first.map(String.init) ?? ""
        if ["crea", "crear", "haz", "hacer", "hazme", "haceme", "nuevo", "nueva", "diseña", "diseñar"].contains(primera) {
            return true
        }
        return [
            "quiero crear", "quiero diseñar", "quiero hacer",
            "necesito crear", "necesito diseñar",
            "me gustaría crear", "me gustaria crear",
            "me gustaría diseñar", "me gustaria diseñar"
        ].contains { normalizada.hasPrefix($0) }
    }

    /// Una propuesta de la IA a la espera de saber en qué acabó.
    struct Propuesta {
        let id: String
        let peticion: String
        let plan: String
        let rondas: Int
        let reparos: [String]
    }

    /// Cuánto se le concede al arrepentimiento para contar como rechazo de la pieza.
    ///
    /// Pasado ese rato, un deshacer ya no habla de la propuesta sino del trabajo que
    /// vino después, y apuntarlo contra la IA emborronaría el registro.
    private static let ventanaDeArrepentimiento: TimeInterval = 45

    /// Dónde vive la bitácora. Junto a los ajustes, no en el documento del usuario:
    /// es telemetría local de la herramienta, no parte de la pieza que está haciendo.
    private static var rutaDeLaBitacora: String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Yunkil", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("propuestas.jsonl").path
    }

    /// Apunta el desenlace de una propuesta. Que falle no puede romper nada.
    ///
    /// `rechazadas` son las operaciones que el usuario desmarcó, en español. Es el
    /// dato que no existía cuando el desenlace era binario: el modelo dejaba de ser
    /// «bueno» o «malo» y pasa a saberse **qué** de lo que propuso sobraba.
    private func anotar(_ propuesta: Propuesta, desenlace: String, rechazadas: [String] = []) {
        _ = editor.registrarDesenlace(
            ruta: Self.rutaDeLaBitacora,
            id: propuesta.id,
            momento: Int64(Date().timeIntervalSince1970),
            peticion: propuesta.peticion,
            plan: propuesta.plan,
            nombrePerfil: perfilDeFabricacion,
            rondas: Int32(propuesta.rondas),
            reparos: propuesta.reparos,
            desenlaceNombre: desenlace,
            rechazadas: rechazadas
        )
    }

    /// Aplica el plan en un núcleo aislado y devuelve lo que le falla a la pieza.
    ///
    /// Va fuera del hilo principal por lo mismo que el analizador: muestrear el campo
    /// para saber si las piezas se tocan cuesta décimas de segundo, y la interfaz no
    /// puede quedarse congelada mientras tanto. Se le pasa el documento serializado
    /// en vez del editor vivo porque así el banco de pruebas no comparte ni un objeto
    /// con el que está editando el usuario.
    private static func revisar(
        respuesta: String,
        documento: String,
        perfil: String,
        edicion: Bool,
        peticion: String,
        referencia: ImagenDeReferencia?,
        seleccion: AISelection
    ) async -> Revision {
        await Task.detached(priority: .userInitiated) {
            let aislado = Editor(inicial: Documento.companion.vacio())
            _ = aislado.desdeJson(texto: documento)
            guard let plan = aislado.interpretarPlan(respuesta: respuesta, edicion: edicion).plan else {
                return Revision(motivos: [], cosido: nil, mirada: nil)
            }

            // La post-condición de cotas va **antes** de mirar los defectos, y aparte:
            // una pieza del tamaño equivocado no tiene ningún defecto que el revisor
            // pueda ver. El plan es válido, la geometría está limpia y la pieza mide
            // otra cosa; si esto esperara a que hubiera reparos, el caso no se
            // arreglaría nunca porque no los hay.
            let acotado = aislado.acotarPlan(plan: plan, peticion: peticion, nombrePerfil: perfil)
            let base = acotado ?? plan

            let revision = aislado.revisarPlan(plan: base, nombrePerfil: perfil)
            if revision.motivos.isEmpty {
                // Los números están limpios. Falta lo único que los números no dicen:
                // si la pieza **es** la que se pidió. Va aquí y no antes porque dibujar
                // y preguntar cuesta una carga de modelo y una inferencia, y gastarlas
                // en un plan que ya se sabe roto es tirarlas.
                let aLaVista = await CriticaVisual.revisar(
                    editor: aislado, plan: base, peticion: peticion,
                    perfil: perfil, referencia: referencia, seleccion: seleccion
                )
                return Revision(motivos: aLaVista.reparos, cosido: acotado, mirada: aLaVista.mirada)
            }

            // El revisor no solo mide: cuando lo que falla es que dos piezas no se
            // tocan, sabe qué operación las une. Aplicarla aquí ahorra una ronda de
            // inferencia entera y, sobre todo, no depende de que el modelo copie
            // bien una línea de JSON. Si el cosido no arregla nada, `coserPlan`
            // devuelve nil y el fallo sigue su camino hacia la ronda de corrección.
            // Con reparos exactos no se llega a dibujar nada: el crítico visual va después
            // a propósito, porque cuesta una carga de modelo y una inferencia.
            guard let cosido = aislado.coserPlan(plan: base, nombrePerfil: perfil) else {
                return Revision(motivos: revision.motivos, cosido: acotado, mirada: nil)
            }
            return Revision(
                motivos: aislado.revisarPlan(plan: cosido, nombrePerfil: perfil).motivos,
                cosido: cosido,
                mirada: nil
            )
        }.value
    }

    /// Lo que le falla a la pieza y, si Yunkil supo arreglarlo, el plan ya cosido.
    struct Revision {
        let motivos: [String]
        let cosido: PlanDeModelado?
        /// Qué pasó al mirarla, o `nil` si no se llegó a esa fase porque los números ya
        /// habían fallado. `nil` y «no se pudo mirar» no son lo mismo y no se confunden.
        let mirada: Mirada?
    }

    // MARK: Analizador de fabricación

    var perfilesDisponibles: [String] { editor.perfilesDeFabricacion() }

    /// Los que ha calibrado quien usa esto, que son los únicos que se pueden borrar.
    var perfilesPropios: [String] { editor.perfilesPropios() }

    var perfilActivoEsPropio: Bool { perfilesPropios.contains(perfilDeFabricacion) }

    /// De dónde salen los umbrales del perfil activo. Un aviso sin procedencia es una
    /// opinión; con ella es un dato, y eso incluye saber sobre qué máquina se calibró.
    var procedenciaDelPerfil: String {
        let base = editor.baseDelPerfil()
        return base.isEmpty ? editor.origenDelPerfil() : "\(editor.origenDelPerfil()) · sobre \(base)"
    }

    /// Dónde se guardan los perfiles calibrados.
    ///
    /// En Application Support y no junto al documento: la calibración es de la **máquina**,
    /// no de la pieza. El mismo perfil vale para todo lo que se imprima en ella, y viajar
    /// dentro de un `.yunkil` haría que abrir el archivo de otro te cambiara la impresora.
    /// Es `var` por una sola razón: el arnés de `tools/estado` la reapunta a una carpeta
    /// temporal. Un arnés que escribiera aquí borraría la calibración de quien lo ejecute.
    static var rutaDePerfiles: String = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        let carpeta = (base ?? URL(fileURLWithPath: NSHomeDirectory())).appendingPathComponent("Yunkil", isDirectory: true)
        try? FileManager.default.createDirectory(at: carpeta, withIntermediateDirectories: true)
        return carpeta.appendingPathComponent("perfiles.json").path
    }()

    /// Cambia el perfil de trabajo **de verdad**.
    ///
    /// El desplegable escribía una cadena que solo usaba el analizador, así que elegir otra
    /// impresora no movía ni una cota: las que gobierna un encaje seguían derivadas con la
    /// holgura de la anterior. Pasar por el editor es lo que hace visible que el encaje está
    /// vivo —de una boquilla de 0,4 a una de 0,6 las piezas que encajan se mueven solas—, y
    /// es media tesis del producto.
    func fijarPerfil(_ nombre: String) {
        guard perfilesDisponibles.contains(nombre) else { return }
        perfilDeFabricacion = nombre
        UserDefaults.standard.set(nombre, forKey: "fab.perfil")
        _ = editor.usarPerfil(nombre: nombre)
        aviso = editor.ultimoError
        refrescar(recompilo: false)
        if informe != nil { analizarFabricacion() }
    }

    // MARK: Calibrar la máquina

    /// Lo que hay que mirar en el cupón impreso: qué estación se traga el pasador.
    var estacionesDelCupon: [(indice: Int, diametro: Float, holgura: Float)] {
        let holguras = editor.holgurasDelCupon(nombrePerfil: perfilDeFabricacion).map { $0.floatValue }
        let diametros = editor.diametrosDelCupon(nombrePerfil: perfilDeFabricacion).map { $0.floatValue }
        guard holguras.count == diametros.count else { return [] }
        return holguras.indices.map { (indice: $0 + 1, diametro: diametros[$0], holgura: holguras[$0]) }
    }

    /// Pone la probeta en el documento, lista para exportar e imprimir.
    func generarCuponDeCalibracion() {
        guard editor.cargarCuponDeCalibracion(nombrePerfil: perfilDeFabricacion) else {
            aviso = editor.ultimoError ?? "No se pudo preparar el cupón."
            return
        }
        refrescar(recompilo: true)
        renderizador?.encuadrar()
        aviso = "Exporta el cupón e imprímelo. Luego prueba qué estación se traga el pasador."
    }

    /// Guarda lo que ha dado la máquina real y lo deja activo.
    func guardarCalibracion(estacion: Int, nombre: String) {
        let holguras = editor.holgurasDelCupon(nombrePerfil: perfilDeFabricacion)
        guard estacion >= 0, estacion < holguras.count else {
            aviso = "Elige la estación que entró en el cupón impreso."
            return
        }
        let limpio = nombre.trimmingCharacters(in: .whitespacesAndNewlines)
        if let motivo = editor.guardarPerfilCalibrado(
            nombreBase: perfilDeFabricacion,
            nombreNuevo: limpio,
            holgura: holguras[estacion].floatValue,
            ruta: Self.rutaDePerfiles
        ) {
            aviso = motivo
            return
        }
        perfilDeFabricacion = limpio
        UserDefaults.standard.set(limpio, forKey: "fab.perfil")
        refrescar(recompilo: false)
        if informe != nil { analizarFabricacion() }
        aviso = "«\(limpio)» guardado y activo: la holgura es ya la que mide tu máquina."
    }

    func olvidarPerfil(_ nombre: String) {
        if let motivo = editor.olvidarPerfil(nombre: nombre, ruta: Self.rutaDePerfiles) {
            aviso = motivo
            return
        }
        perfilDeFabricacion = editor.perfilDeTrabajo()
        UserDefaults.standard.set(perfilDeFabricacion, forKey: "fab.perfil")
        refrescar(recompilo: false)
    }

    /// Examina la pieza contra el perfil de impresora elegido.
    ///
    /// Corre fuera del hilo principal porque malla el modelo a resolución de
    /// boquilla; bloquear la interfaz varios segundos convertiría una función útil
    /// en una que nadie pulsa.
    func analizarFabricacion() {
        guard !analizando, !editor.estaVacio else { return }
        analizando = true
        informe = nil
        let perfil = perfilDeFabricacion
        UserDefaults.standard.set(perfil, forKey: "fab.perfil")
        let copia = editor.aJson()

        Task.detached(priority: .userInitiated) {
            let aislado = Editor(inicial: Documento.companion.vacio())
            _ = aislado.desdeJson(texto: copia)
            let resultado = aislado.analizarFabricacion(nombrePerfil: perfil, alAvanzar: nil)
            let candidatas = aislado.orientacionesDeImpresion(nombrePerfil: perfil)
            await MainActor.run {
                self.analizando = false
                guard self.elInformeSigueValiendo(documento: copia, perfil: perfil) else {
                    self.informe = nil
                    self.orientaciones = []
                    self.aviso = "La pieza cambió mientras se examinaba. Vuelve a analizarla."
                    return
                }
                self.informe = resultado
                self.orientaciones = candidatas
                if resultado == nil { self.aviso = aislado.ultimoError }
            }
        }
    }

    /// ¿Lo que acaba de medirse sigue siendo lo que hay delante?
    ///
    /// El examen tarda segundos y mide una **copia** del documento. Si mientras tanto se ha
    /// movido una cota o se ha cambiado de impresora, lo que vuelve es el informe de otra
    /// pieza. Y un informe que dice «apta» sobre algo que ya no existe es peor que no tener
    /// informe: es el único sitio del producto donde el usuario confía sin volver a mirar.
    ///
    /// Está separado de la tarea para poder comprobarlo sin esperar un examen entero: lo que
    /// puede romperse aquí es la decisión, no la aritmética que la precede.
    func elInformeSigueValiendo(documento: String, perfil: String) -> Bool {
        documento == editor.aJson() && perfil == perfilDeFabricacion
    }

    /// Ejecuta el arreglo que propone un aviso. Entra en el historial como todo.
    func aplicarCorreccion(_ correccion: Correccion) {
        let cambio = editor.aplicarCorreccion(correccion: correccion)
        if let error = editor.ultimoError {
            aviso = error
            return
        }
        refrescar(recompilo: cambio)
        renderizador?.encuadrar()
        analizarFabricacion()
    }

    func aplicarOrientacion(_ orientacion: OrientacionEvaluada) {
        _ = editor.girarModelo(gradosX: orientacion.gradosX, gradosZ: orientacion.gradosZ)
        refrescar(recompilo: true)
        renderizador?.encuadrar()
        analizarFabricacion()
    }

    func asentarEnPlato() {
        _ = editor.asentarEnPlato(registrarEnHistorial: true)
        refrescar(recompilo: true)
    }

    /// Trae un STL de fuera. Es la puerta por la que entra la geometría que no se
    /// puede escribir: a partir de aquí la malla es una pieza más y se le puede
    /// restar, ahuecar, taladrar, acotar y exportar verificada.
    func importarMalla() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [UTType(filenameExtension: "stl") ?? .data]
        panel.message = "Un STL: descargado, escaneado o exportado por otra herramienta"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        importarMallaDesde(ruta: url.path)
    }

    /// El flujo de una acción del STL de fuera: importar → analizar → arreglos.
    ///
    /// Es lo que convierte la capacidad suelta («importo un STL») en el producto
    /// que se puede enseñar: arrastras el archivo, y en la misma pasada la pieza
    /// queda examinada contra la impresora con cada aviso nombrando la pieza y
    /// trayendo su arreglo ejecutable. Lo único que faltaba era el cable entre lo
    /// que ya existía —`importarMalla` y `analizarFabricacion`—.
    /// Trae un STL de fuera sin congelar la ventana.
    ///
    /// Hornear es rasterizar cada triángulo contra una rejilla: con una pieza descargada
    /// de internet son segundos, y hasta ahora se pagaban con la interfaz parada, que es
    /// indistinguible de una aplicación colgada. El núcleo tiene la importación partida en
    /// dos justo para esto —`hornearMallaDesde` no toca el documento—, así que lo caro se
    /// hace en un editor aislado y en otro hilo, como el análisis, y lo único que vuelve al
    /// hilo principal es el campo ya horneado.
    func importarMallaDesde(ruta: String) {
        guard !importandoMalla else { return }
        importandoMalla = true
        aviso = "Preparando la malla…"

        Task.detached(priority: .userInitiated) {
            let aislado = Editor(inicial: Documento.companion.vacio())
            let horneada = aislado.hornearMallaDesde(ruta: ruta, resolucion: 0)
            await MainActor.run { self.colocar(horneada) }
        }
    }

    /// Vuelve a hornear las mallas de un proyecto recién abierto, sin congelar la ventana.
    ///
    /// Los campos no viajan dentro del `.yunkil` —son megas—, así que al abrir llegan vacíos
    /// y hay que rasterizar otra vez cada STL contra su rejilla. Con dos piezas importadas
    /// eso eran varios segundos con la aplicación muerta. Ahora el documento se enseña
    /// primero, aunque sus mallas todavía no se vean, y los campos van cayendo: se ve
    /// aparecer la pieza, que es lo contrario de parecer colgada.
    ///
    /// Si alguna no está donde estaba se dice cuál, en vez de dejar un hueco callado.
    private func rehornearLoQueFalte() {
        let pendientes = editor.mallasSinHornear()
        guard !pendientes.isEmpty else { return }
        importandoMalla = true

        // Cada una en su vuelta: colocar un campo en cuanto está listo enseña la primera
        // pieza mientras se hornea la segunda.
        Task.detached(priority: .userInitiated) {
            let aislado = Editor(inicial: Documento.companion.vacio())
            for id in pendientes {
                let ruta = await MainActor.run { self.editor.rutaDeMalla(id: id) }
                let horneada = ruta.map { aislado.hornearMallaDesde(ruta: $0, resolucion: 0) }
                await MainActor.run { self.ponerCampo(id: id, horneada) }
            }
            await MainActor.run { self.importandoMalla = false }
        }
    }

    private func ponerCampo(id: String, _ horneada: MallaImportada?) {
        guard let lista = horneada as? MallaImportadaLista else {
            let nombre = editor.nombreDe(id: id)
            aviso = "No se encontró el archivo de «\(nombre)»: esa pieza queda vacía."
            return
        }
        _ = editor.ponerCampoHorneado(id: id, horneada: lista)
        refrescar(recompilo: true)
    }

    private func colocar(_ horneada: MallaImportada) {
        importandoMalla = false
        guard let lista = horneada as? MallaImportadaLista else {
            aviso = (horneada as? MallaImportadaFallo)?.motivo ?? "No se pudo importar la malla."
            return
        }
        guard editor.colocarMalla(horneada: lista, padreId: nil) else {
            aviso = editor.ultimoError ?? "No se pudo colocar la malla."
            return
        }
        // `ultimoError` trae aquí un aviso, no un fallo: la pieza ya está puesta.
        aviso = editor.ultimoError
        refrescar(recompilo: true)
        renderizador?.encuadrar()
        // El análisis corre en segundo plano y el informe sale con la pieza ya en la
        // escena: es la mitad del producto «STL devuelto imprimible».
        if analizarAlCrear { analizarFabricacion() }
    }

    func abrir() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [UTType(filenameExtension: "yunkil") ?? .json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        abrirDesde(url)
    }

    /// Abrir, separado del diálogo que elige el archivo.
    ///
    /// Están separados para poder conducirlo sin ventana: un `NSOpenPanel` no se puede
    /// contestar desde un arnés, y abrir un proyecto es justo donde vive el rehorneado.
    func abrirDesde(_ url: URL) {
        do {
            let texto = try String(contentsOf: url, encoding: .utf8)
            guard editor.desdeJson(texto: texto) else {
                aviso = editor.ultimoError
                return
            }
            // Se enseña ya, aunque las mallas todavía no estén: ver el documento y que sus
            // piezas importadas vayan apareciendo es lo contrario de una ventana muerta.
            refrescar(recompilo: true)
            renderizador?.encuadrar()
            rehornearLoQueFalte()
        } catch {
            aviso = "No se pudo abrir: \(error.localizedDescription)"
        }
    }
}

// MARK: - Interfaz

private let primitivas: [(String, String, String)] = [
    ("ESFERA", "Esfera", "circle"),
    ("CAJA", "Caja", "cube"),
    ("CILINDRO", "Cilindro", "cylinder"),
    ("CONO", "Cono", "cone"),
    ("TORO", "Toro", "torus"),
    ("CAPSULA", "Cápsula", "capsule"),
]

// Las tres piezas que se dibujan con un contorno en vez de acotarse con números.
// Estaban solo al alcance de la IA, que es una forma rara de esconder las piezas
// con las que se hace cualquier cosa que no sea un bloque.
private let contornos: [(String, String, String)] = [
    ("EXTRUSION", "Extrusión", "square.stack.3d.up"),
    ("REVOLUCION", "Revolución", "arrow.triangle.2.circlepath"),
    ("BARRIDO", "Barrido", "point.topleft.down.curvedto.point.bottomright.up"),
]

private let operaciones: [(String, String, String)] = [
    ("UNION", "Unión", "plus.circle"),
    ("DIFERENCIA", "Diferencia", "minus.circle"),
    ("INTERSECCION", "Intersección", "circle.circle"),
    ("VACIADO", "Vaciado", "square.on.square.dashed"),
    ("DESFASE", "Desfase", "arrow.up.left.and.arrow.down.right"),
    ("SIMETRIA", "Simetría", "arrow.left.and.right"),
    ("REPETICION", "Repetición", "square.grid.3x1.below.line.grid.1x2"),
    ("REPETICION_CIRCULAR", "Patrón circular", "circle.grid.3x3"),
]

struct VistaPrincipal: View {

    @ObservedObject var estado: EstadoDeLaApp
    @State private var mostrarGuia = false
    @State private var queCrear = ""
    @State private var paraQueSirve = ""
    @State private var medidaClave = ""
    @State private var modoAvanzado = false
    @State private var editandoPerfil = false
    /// La sección de calibrar, plegada: se usa una vez por máquina, no cada día.
    @State private var calibrando = false
    @State private var estacionElegida = 0
    @State private var nombreCalibrado = ""
    /// Radio del próximo filete. 1,5 mm es lo que aguanta una pared impresa normal sin
    /// comerse el canto.
    @State private var radioDeFilete: Float = 1.5
    /// Foco del campo de nombre, para que «Renombrar…» del clic derecho lleve el cursor
    /// donde se escribe en lugar de solo seleccionar la pieza.
    @FocusState private var nombreEnfocado: Bool

    var body: some View {
        HSplitView {
            if modoAvanzado {
                panelIzquierdo.frame(minWidth: 240, idealWidth: 265, maxWidth: 340)
            }

            ZStack {
                VisorMetal(
                    editor: estado.editor,
                    alCrear: { estado.registrar($0) },
                    alPinchar: { origen, direccion in estado.senalar(origen: origen, direccion: direccion) },
                    alEmpezarAEmpujar: { origen, direccion in
                        estado.empezarAEmpujar(origen: origen, direccion: direccion)
                    },
                    alEmpujar: { estado.empujar(milimetros: $0) },
                    alEmpezarAMoverPlano: { origen, direccion in
                        estado.empezarAMoverPlano(origen: origen, direccion: direccion)
                    },
                    alMoverPlano: { estado.moverPlano(milimetros: $0) },
                    alEmpezarAEsculpir: { origen, direccion in
                        estado.empezarAEsculpir(origen: origen, direccion: direccion)
                    },
                    alContinuarEsculpiendo: { origen, direccion in
                        estado.continuarEsculpiendo(origen: origen, direccion: direccion)
                    },
                    alTerminarDeEsculpir: { estado.terminarDeEsculpir() },
                    alSoltarStl: { estado.importarMallaDesde(ruta: $0) },
                    centroDelGizmo: estado.centroDelGizmo,
                    alEmpezarGestoDelGizmo: { estado.empezarGestoDelGizmo() },
                    alMoverConGizmo: { estado.moverConGizmo(eje: $0, milimetros: $1) },
                    alGirarConGizmo: { estado.girarConGizmo(eje: $0, grados: $1) },
                    alEscalarConGizmo: { estado.escalarConGizmo(factor: $0) }
                )
                .contextMenu { menuDelViewport }
                VStack(spacing: 0) {
                    creadorVibe
                    // La propuesta se queda debajo del campo donde se pidió, no en una
                    // ventana modal aparte: el usuario tiene que poder orbitar la pieza
                    // y mirar lo que hay mientras decide, y un `NSAlert` se lo impide.
                    if let propuesta = estado.propuestaPendiente {
                        PanelDePropuesta(estado: estado, propuesta: propuesta)
                            .padding(.top, 8)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                    Spacer()
                    HStack { barraDeEstado; Spacer() }
                }
                .animation(.easeOut(duration: 0.16), value: estado.propuestaPendiente != nil)
            }
            .frame(minWidth: 420, minHeight: 420)

            inspector.frame(minWidth: 250, idealWidth: 280, maxWidth: 360)
        }
        .frame(minWidth: 1080, minHeight: 640)
        .toolbar { barraDeHerramientas }
        .sheet(isPresented: $mostrarGuia) { guiaDeCreacion }
        .sheet(isPresented: $editandoPerfil) {
            EditorVisualDePerfil(
                puntosIniciales: estado.puntosDelPerfilSeleccionado(),
                alGuardar: { estado.guardarPerfil($0) },
                alCerrar: { editandoPerfil = false }
            )
        }
    }

    // MARK: Barra de herramientas

    @ToolbarContentBuilder
    private var barraDeHerramientas: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            Picker("Modo", selection: $modoAvanzado) {
                Text("Crear").tag(false)
                Text("Taller").tag(true)
            }
            .pickerStyle(.segmented).frame(width: 170)
            .help("Crear simplifica la interfaz; Taller muestra el árbol y operaciones")
        }

        ToolbarItemGroup(placement: .navigation) {
            Button { estado.deshacer() } label: { Image(systemName: "arrow.uturn.backward") }
                .disabled(!estado.puedeDeshacer)
                .keyboardShortcut("z", modifiers: .command)
                .help("Deshacer")

            Button { estado.rehacer() } label: { Image(systemName: "arrow.uturn.forward") }
                .disabled(!estado.puedeRehacer)
                .keyboardShortcut("z", modifiers: [.command, .shift])
                .help("Rehacer")
        }

        ToolbarItemGroup {
            Button { mostrarGuia = true } label: { Image(systemName: "wand.and.sparkles") }
                .help("Diseñar mediante preguntas")

            Button { estado.encuadrar() } label: { Image(systemName: "viewfinder") }
                .help("Encuadrar el modelo")

            Button { estado.alternarSeccion() } label: {
                Image(systemName: estado.seccionActiva ? "cube.transparent.fill" : "cube.transparent")
            }
            .help("Sección en vivo: corta el modelo y tiñe las paredes finas")

            Button { estado.duplicar() } label: { Image(systemName: "plus.square.on.square") }
                .keyboardShortcut("d", modifiers: .command)
                .disabled(estado.seleccion.isEmpty)
                .help("Duplicar pieza")

            Menu {
                Button("Soporte") { estado.cargarEjemplo("soporte") }
                Button("Esfera") { estado.cargarEjemplo("esfera") }
                Button("Rejilla") { estado.cargarEjemplo("rejilla") }
                Divider()
                Button("Documento vacío") { estado.cargarEjemplo("vacio") }
            } label: {
                Image(systemName: "square.stack.3d.up")
            }
            .help("Ejemplos")

            Button { estado.abrir() } label: { Image(systemName: "folder") }
                .keyboardShortcut("o", modifiers: .command)
                .help("Abrir")

            Button { estado.guardar() } label: { Image(systemName: "square.and.arrow.down") }
                .keyboardShortcut("s", modifiers: .command)
                .help("Guardar")

            Button { estado.exportarPieza() } label: { Image(systemName: "printer.filled.and.paper") }
                .keyboardShortcut("e", modifiers: [.command, .shift])
                .disabled(estado.exportando || estado.editor.estaVacio)
                .help("Exportar la pieza para imprimir: 3MF o STL")
        }
    }

    private var creadorVibe: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Label(estado.hiloEnCurso == nil ? "Crea describiendo" : "Seguimos con la pieza",
                      systemImage: "sparkles")
                    .font(.system(size: 13, weight: .semibold))
                Spacer()
                AIModelSelector(settings: estado.aiSettings)
            }
            Picker("Motor", selection: $estado.motorDeIA) {
                ForEach(MotorDeIA.allCases) { motor in Text(motor.rawValue).tag(motor) }
            }
            .pickerStyle(.segmented)
            // Que el hilo se vea. Una memoria invisible es peor que ninguna: el usuario
            // escribe «más grueso» sin saber si eso significa algo, y si no funcionara
            // no tendría forma de saber por qué.
            if let hilo = estado.hiloEnCurso {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.turn.down.right")
                        .font(Tipo.pie)
                        .foregroundStyle(Tinta.apagado)
                    Text(hilo)
                        .font(Tipo.menor)
                        .foregroundStyle(Tinta.cota)
                        .lineLimit(1).truncationMode(.tail)
                    Spacer(minLength: 0)
                    Button("Empezar de cero") { estado.olvidarElHilo() }
                        .buttonStyle(.plain)
                        .font(Tipo.menor)
                        .foregroundStyle(Tinta.apagado)
                        .help("El modelo deja de tener en cuenta lo hablado hasta ahora")
                }
            }
            HStack(spacing: 8) {
                TextField(estado.hiloEnCurso == nil ? "Quiero una pieza que…" : "…y ahora, ¿qué le cambiamos?",
                          text: $estado.peticionIA)
                    .textFieldStyle(.plain)
                    .font(.system(size: 14))
                    .onSubmit { estado.construirConIA() }
                Button { estado.construirConIA() } label: {
                    if estado.iaTrabajando { ProgressView().controlSize(.small) }
                    else { Image(systemName: "arrow.up") }
                }
                .buttonStyle(.borderedProminent).tint(.mint)
                .disabled(estado.peticionIA.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || estado.iaTrabajando)
                if estado.iaTrabajando {
                    Button("Cancelar") { estado.cancelarIA() }.controlSize(.small)
                }
            }
            // La fila de la imagen. La medida no es un extra: es lo único que pone a
            // escala lo que el modelo lee en la foto, así que va al lado y no en un
            // panel aparte donde nadie la encontraría.
            HStack(spacing: 8) {
                if let nombre = estado.nombreDeLaImagen {
                    Label(nombre, systemImage: "photo")
                        .font(Tipo.menor)
                        .lineLimit(1).truncationMode(.middle)
                        .frame(maxWidth: 150, alignment: .leading)
                    Button { estado.quitarImagen() } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.plain).foregroundStyle(Tinta.cota)
                        .help("Quitar la imagen")

                    TextField("Medida real, p. ej. «el ancho son 80 mm»", text: $estado.medidaDeReferencia)
                        .textFieldStyle(.roundedBorder)
                        .font(Tipo.cuerpo)
                        .help("Una foto no tiene escala. Da una cota que conozcas y Yunkil ajusta el resto en proporción.")
                } else {
                    Button {
                        estado.adjuntarImagen()
                    } label: {
                        Label("Partir de una imagen…", systemImage: "photo.badge.plus")
                    }
                    .buttonStyle(.bordered).controlSize(.mini)
                    .disabled(estado.iaTrabajando)
                }
                Spacer(minLength: 0)
            }
            if estado.nombreDeLaImagen != nil, let aviso = estado.aiSettings.avisoDeImagen {
                Label(aviso, systemImage: "exclamationmark.triangle")
                    .font(Tipo.pie).foregroundStyle(Tinta.riesgo).lineLimit(2)
            }
            if estado.nombreDeLaImagen != nil,
               estado.medidaDeReferencia.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text("Sin una medida real, la pieza saldrá con las proporciones correctas pero a un tamaño cualquiera; Yunkil dirá qué cota necesita.")
                    .font(Tipo.pie).foregroundStyle(Tinta.cota).lineLimit(2)
            }
            HStack(spacing: 6) {
                if estado.motorDeIA == .parametrico {
                    Button("Diseñar conmigo…") { mostrarGuia = true }
                    Button("Caja a medida") { estado.peticionIA = "Crea una caja hueca imprimible con tapa, pregúntame las medidas que falten" }
                    Button("Soporte de móvil") { estado.peticionIA = "Crea un soporte estable para mi móvil, pregúntame sus medidas" }
                    Button("Adaptador") { estado.peticionIA = "Crea un adaptador entre dos medidas, pregúntame los diámetros" }
                } else {
                    Button("Personaje estilizado") { estado.peticionIA = "Crea una figura estilizada de pie, expresiva y fácil de imprimir" }
                    Button("Criatura desde imagen") { estado.peticionIA = "Reconstruye el personaje de la imagen como figura 3D estilizada" }
                }
            }
            .buttonStyle(.bordered).controlSize(.mini)
            if let propuesta = estado.propuestaOrganica {
                HStack(spacing: 10) {
                    Label(propuesta.nombre, systemImage: "figure.stand")
                        .font(Tipo.cuerpo.weight(.semibold))
                    Spacer()
                    Button("Descartar") { estado.descartarPropuestaOrganica() }
                    Button("Añadir escultura") { estado.aceptarPropuestaOrganica() }
                        .buttonStyle(.borderedProminent).tint(.mint)
                }
                .padding(9)
                .background(.black.opacity(0.14), in: RoundedRectangle(cornerRadius: 8))
            }
            if let resultado = estado.resultadoIA {
                Text(resultado).font(Tipo.menor).foregroundStyle(Tinta.cota).lineLimit(2)
            }
            Text(estado.aiSettings.status).font(Tipo.pie).foregroundStyle(Tinta.apagado)
        }
        .padding(13)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 13))
        .overlay(RoundedRectangle(cornerRadius: 13).stroke(.white.opacity(0.12)))
        .frame(maxWidth: 680)
        .padding(14)
    }

    private var guiaDeCreacion: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Diseñemos tu pieza").font(.system(size: 25, weight: .bold, design: .rounded))
                    Text("No necesitas saber modelado 3D. Cuéntame el problema.").foregroundStyle(Tinta.cota)
                }
                Spacer()
                Image(systemName: "wand.and.stars").font(.system(size: 34)).foregroundStyle(.mint)
            }

            pregunta("1", "¿Qué quieres crear?", "Un soporte para dejar el mando bajo la mesa…", $queCrear)
            pregunta("2", "¿Para qué debe servir o qué debe sujetar?", "Debe aguantar 500 g y atornillarse con dos tornillos…", $paraQueSirve)
            pregunta("3", "¿Qué medida no puede fallar?", "El mando mide 152 × 105 × 62 mm…", $medidaClave)

            HStack {
                Text("Yunkil propondrá geometría fabricable y podrás deshacerla con ⌘Z.")
                    .font(Tipo.menor).foregroundStyle(Tinta.cota)
                Spacer()
                Button("Cancelar") { mostrarGuia = false }
                Button("Crear primera propuesta", systemImage: "sparkles") {
                    estado.peticionIA = "Quiero crear: \(queCrear). Uso y requisitos: \(paraQueSirve). Medida crítica: \(medidaClave). Si falta un dato imprescindible, haz una propuesta conservadora y explica qué medida debo revisar."
                    mostrarGuia = false
                    estado.construirConIA()
                }
                .buttonStyle(.borderedProminent).tint(.mint)
                .disabled(queCrear.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(28).frame(width: 620)
    }

    private func pregunta(_ numero: String, _ titulo: String, _ ejemplo: String, _ texto: Binding<String>) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(numero).font(.system(size: 13, weight: .bold)).foregroundStyle(.black)
                .frame(width: 26, height: 26).background(.mint, in: Circle())
            VStack(alignment: .leading, spacing: 7) {
                Text(titulo).font(.system(size: 13, weight: .semibold))
                TextField(ejemplo, text: texto, axis: .vertical)
                    .textFieldStyle(.roundedBorder).lineLimit(2...4)
            }
        }
    }

    // MARK: Panel izquierdo — árbol y paleta

    private var panelIzquierdo: some View {
        VStack(spacing: 0) {
            encabezado

            List(selection: Binding(
                get: { estado.seleccion },
                set: { if let id = $0 { estado.seleccionar(id) } }
            )) {
                ForEach(estado.filas) { fila in
                    filaDelArbol(fila).tag(fila.id)
                }
            }
            .listStyle(.sidebar)

            Divider()
            paleta
        }
    }

    private var encabezado: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 24, height: 24)
            Text("Yunkil").font(.system(size: 19, weight: .semibold, design: .rounded))
            Text("sólidos por campo de distancia")
                .font(Tipo.pie).foregroundStyle(Tinta.apagado)
            Spacer()
        }
        .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 8)
    }

    private func filaDelArbol(_ fila: Fila) -> some View {
        HStack(spacing: 6) {
            // La sangría comunica la jerarquía sin gastar un control desplegable,
            // que en árboles poco profundos estorba más de lo que ayuda.
            if fila.profundidad > 0 {
                Rectangle().fill(.clear).frame(width: CGFloat(fila.profundidad) * 12, height: 1)
            }

            Image(systemName: fila.esOperacion ? "square.on.square" : "cube.fill")
                .font(Tipo.menor)
                .foregroundStyle(fila.esOperacion ? Color.orange : Color.teal)

            Text(fila.nombre)
                .font(.system(size: 13))
                .foregroundStyle(fila.visible ? .primary : .tertiary)
                .strikethrough(!fila.visible, color: .secondary)

            Spacer(minLength: 4)

            if fila.esOperacion && fila.numeroDeHijos == 0 {
                // Una operación sin hijos no aporta material: avisar aquí ahorra
                // buscar por qué no se ve nada.
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(Tipo.pie).foregroundStyle(.yellow)
                    .help("Operación sin piezas dentro")
            }

            Button { estado.alternarVisibilidad(fila.id) } label: {
                Image(systemName: fila.visible ? "eye" : "eye.slash")
                    .font(Tipo.menor)
                    .foregroundStyle(fila.visible ? .secondary : .tertiary)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 1)
        .contentShape(Rectangle())
        .contextMenu { menuDeLaPieza(fila) }
    }

    /// Clic derecho sobre una pieza del árbol.
    ///
    /// Todo lo de aquí ya existía en el núcleo y no se podía alcanzar. Es la diferencia
    /// entre una demo y una herramienta: nadie va a buscar «extraer del grupo» en una barra
    /// de botones, se busca donde está la pieza.
    @ViewBuilder
    private func menuDeLaPieza(_ fila: Fila) -> some View {
        Button("Renombrar…") { estado.seleccionar(fila.id); nombreEnfocado = true }
        Button("Duplicar") { estado.seleccionar(fila.id); estado.duplicar() }
        Divider()
        Button("Copiar") { estado.copiar(fila.id) }
        Button("Pegar dentro") { estado.pegar(en: fila.id) }
            .disabled(!estado.hayAlgoQuePegar)
        Divider()
        Button("Aislar") { estado.aislar(fila.id) }
        Button(fila.visible ? "Ocultar" : "Mostrar") { estado.alternarVisibilidad(fila.id) }
        Button("Mostrar todo") { estado.mostrarTodo() }
        Divider()
        Button("Sacar del grupo") { estado.extraer(fila.id) }
            .disabled(fila.profundidad < 2)
        Menu("Envolver en") {
            Button("Unión") { estado.seleccionar(fila.id); estado.envolver("UNION") }
            Button("Diferencia") { estado.seleccionar(fila.id); estado.envolver("DIFERENCIA") }
            Button("Intersección") { estado.seleccionar(fila.id); estado.envolver("INTERSECCION") }
            Button("Vaciado") { estado.seleccionar(fila.id); estado.envolver("VACIADO") }
            Button("Desfase") { estado.seleccionar(fila.id); estado.envolver("DESFASE") }
            Button("Simetría") { estado.seleccionar(fila.id); estado.envolver("SIMETRIA") }
            Button("Repetición") { estado.seleccionar(fila.id); estado.envolver("REPETICION") }
            Button("Patrón circular") { estado.seleccionar(fila.id); estado.envolver("REPETICION_CIRCULAR") }
        }
        .disabled(fila.id == estado.raizId)
        Divider()
        Button("Subir") { estado.seleccionar(fila.id); estado.desplazar(haciaArriba: true) }
        Button("Bajar") { estado.seleccionar(fila.id); estado.desplazar(haciaArriba: false) }
        Divider()
        Button("Eliminar") { estado.seleccionar(fila.id); estado.eliminar() }
            .disabled(fila.id == estado.raizId)
    }

    private var paleta: some View {
        VStack(alignment: .leading, spacing: 10) {
            grupoDeBotones("Añadir primitiva", primitivas) { estado.anadir($0) }
            grupoDeBotones("Añadir contorno", contornos) { estado.anadir($0) }
            VStack(alignment: .leading, spacing: 3) {
                Text("Traer de fuera").font(Tipo.menor.weight(.semibold)).foregroundStyle(Tinta.cota)
                Button { estado.importarMalla() } label: {
                    Label("Importar STL…", systemImage: "square.and.arrow.down")
                }
                .buttonStyle(.bordered).controlSize(.small)
                .help("Un STL descargado o escaneado. El viewport enseña su envolvente hasta que llegue el enlace de textura; exportar, restar y analizar ya usan la forma real.")
            }
            grupoDeBotones("Añadir operación", operaciones) { estado.anadir($0) }

            HStack(spacing: 6) {
                Button { estado.desplazar(haciaArriba: true) } label: {
                    Image(systemName: "arrow.up")
                }
                Button { estado.desplazar(haciaArriba: false) } label: {
                    Image(systemName: "arrow.down")
                }
                Spacer()
                Button(role: .destructive) { estado.eliminar() } label: {
                    Image(systemName: "trash")
                }
                .keyboardShortcut(.delete, modifiers: [])
                Button { estado.duplicar() } label: {
                    Image(systemName: "plus.square.on.square")
                }
                .keyboardShortcut("d", modifiers: .command)
                .help("Duplicar")
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
        }
        .padding(12)
    }

    private func grupoDeBotones(
        _ titulo: String,
        _ elementos: [(String, String, String)],
        accion: @escaping (String) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(titulo.uppercased())
                .font(Tipo.rotulo).foregroundStyle(Tinta.apagado).tracking(0.6)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 3), spacing: 4) {
                ForEach(elementos, id: \.0) { tipo, etiqueta, _ in
                    Button(etiqueta) { accion(tipo) }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .font(Tipo.menor)
                }
            }
        }
    }

    // MARK: Inspector

    /// Clic derecho en la vista 3D.
    ///
    /// Actúa sobre lo último que se pinchó, que es lo que hace que las órdenes puedan
    /// hablar de «este canto» y «esta cara» sin teclear una coordenada. Con el botón
    /// derecho macOS no cambia la selección, así que hay que pinchar antes con el
    /// izquierdo: es la misma secuencia que en el Finder.
    @ViewBuilder
    private var menuDelViewport: some View {
        if estado.seleccion.isEmpty {
            Text("Pincha una pieza primero")
        } else {
            Text(estado.nombre)
            Divider()
            Button("Filete de \(Cifra.texto(radioDeFilete, decimales: 1)) mm aquí") {
                estado.filetearEnElPuntoSenalado(radioDeFilete)
            }
            .disabled(!estado.puedeFiletear)
            Button("Apoyar esta cara en el plato") { estado.apoyarLaCaraSenalada() }
                .disabled(!estado.puedeFiletear)
            Divider()
            Button("Enfocar la pieza") { estado.enfocarSeleccion() }
            Button("Aislar") { estado.aislar(estado.seleccion) }
            Button("Mostrar todo") { estado.mostrarTodo() }
            Divider()
            Menu("Vista") {
                Button("Planta") { estado.mirarDesde(.planta) }
                Button("Alzado") { estado.mirarDesde(.alzado) }
                Button("Perfil") { estado.mirarDesde(.perfil) }
                Button("Isométrica") { estado.mirarDesde(.isometrica) }
                Divider()
                Button(estado.esOrtografica ? "Con perspectiva" : "Sin perspectiva") {
                    estado.alternarOrtografica()
                }
                Divider()
                Button(estado.seccionActiva ? "Quitar sección en vivo" : "Sección en vivo") {
                    estado.alternarSeccion()
                }
                Button("Sección por este plano") { estado.seccionPorElPuntoSenalado() }
                    .disabled(!estado.puedeFiletear)
            }
            Divider()
            Button("Duplicar") { estado.duplicar() }
            Button("Copiar") { estado.copiar(estado.seleccion) }
            Button("Eliminar") { estado.eliminar() }
        }
    }

    /// Filete de un canto concreto.
    ///
    /// Las coordenadas del filete no se teclean: salen del último punto pinchado en el
    /// viewport, que es la forma en la que una persona señala «este canto». Los cuatro
    /// parámetros del acuerdo (alcance y centro) existen en el documento y se pueden ver,
    /// pero se quitan de «Medidas» porque nadie quiere tres deslizadores de coordenadas
    /// donde espera cotas.
    @ViewBuilder
    private var seccionDeFilete: some View {
        seccion("Filete") {
            if estado.seleccionTieneFilete {
                Text("Canto redondeado \(Cifra.texto(estado.radioDelFilete, decimales: 1)) mm")
                    .font(Tipo.cuerpo)
                Button("Quitar filete") { estado.quitarFilete() }
                    .font(Tipo.cuerpo)
            } else if estado.puedeFiletear {
                HStack(spacing: 6) {
                    Text("Radio").font(Tipo.menor).foregroundStyle(Tinta.cota)
                    TextField("mm", value: $radioDeFilete, format: .number)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 52)
                        .font(Tipo.cuerpo)
                    Button("Aquí") { estado.filetearEnElPuntoSenalado(radioDeFilete) }
                        .font(Tipo.cuerpo)
                }
                Text("Se aplica en el último punto que hayas pinchado en la vista.")
                    .font(Tipo.menor).foregroundStyle(Tinta.apagado)
            } else {
                Text("Pincha el canto que quieras redondear y vuelve aquí.")
                    .font(Tipo.menor).foregroundStyle(Tinta.apagado)
            }
        }
    }

    private var inspector: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if !modoAvanzado && estado.filas.count <= 2 {
                    seccion("Cómo va esto") {
                        Label("Describe arriba lo que necesitas", systemImage: "1.circle.fill")
                        Label("Ajusta aquí sus medidas", systemImage: "2.circle.fill")
                        Label("Exporta la pieza verificada", systemImage: "3.circle.fill")
                        Text("Yunkil mantiene el modelo editable aunque lo haya creado la IA.")
                            .font(Tipo.menor).foregroundStyle(Tinta.cota)
                    }
                }

                if estado.seleccion.isEmpty {
                    Text("Sin selección").font(.caption).foregroundStyle(Tinta.cota)
                } else {
                    seccionCon("Pieza", apunte: estado.tipoSeleccionado.capitalized) {
                        TextField("Nombre", text: Binding(
                            get: { estado.nombre },
                            set: { estado.nombre = $0 }
                        ))
                        .textFieldStyle(.roundedBorder)
                        .font(.system(size: 13))
                        .focused($nombreEnfocado)
                        .onSubmit { estado.aplicarNombre(); nombreEnfocado = false }

                    }

                    if let encaje = estado.encajeDescrito {
                        seccion("Encaje") { panelDeEncaje(encaje) }
                    }

                    if !estado.parametros.isEmpty {
                        seccion("Medidas") {
                            ForEach(estado.parametros.filter { !$0.clave.hasPrefix("acuerdo") }) { p in
                                deslizador(p)
                            }
                        }
                    }

                    if estado.esEsculturaSeleccionada {
                        seccion("Escultura") {
                            Picker("Brocha", selection: $estado.modoBrochaOrganica) {
                                Text("Orbitar").tag("NINGUNA")
                                Text("Añadir").tag("AGREGAR")
                                Text("Inflar").tag("INFLAR")
                                Text("Quitar").tag("QUITAR")
                            }
                            .pickerStyle(.segmented)
                            // Las tres de abajo no suman ni restan bolas: deforman el
                            // campo. Van en su propio selector porque también se usan de
                            // otra manera —mover se arrastra, no se sella— y mezclarlas
                            // con las de volumen escondería esa diferencia.
                            Picker("Modelar", selection: $estado.modoBrochaOrganica) {
                                Text("Alisar").tag("ALISAR")
                                Text("Pellizcar").tag("PELLIZCAR")
                                Text("Mover").tag("MOVER")
                                Text("Proteger").tag("PROTEGER")
                            }
                            .pickerStyle(.segmented)
                            .onChange(of: estado.modoBrochaOrganica) { _, _ in
                                estado.ajustarFuerzaAlModo()
                            }
                            HStack {
                                Text("Radio")
                                Slider(value: $estado.radioBrochaOrganica, in: 0.5...20)
                                Text("\(Cifra.texto(estado.radioBrochaOrganica, decimales: 1)) mm")
                                    .monospacedDigit().frame(width: 58, alignment: .trailing)
                            }
                            if estado.modoBrochaOrganica == "ALISAR"
                                || estado.modoBrochaOrganica == "PELLIZCAR"
                                || estado.modoBrochaOrganica == "PROTEGER" {
                                HStack {
                                    Text("Fuerza")
                                    // El pellizco es la única con signo: hacia un lado
                                    // afila y hacia el otro ensancha, y las dos cosas son
                                    // la misma operación con `k` cambiado de signo.
                                    Slider(
                                        value: $estado.fuerzaBrochaOrganica,
                                        in: estado.modoBrochaOrganica == "PELLIZCAR" ? -1...1 : 0.05...1
                                    )
                                    Text(Cifra.texto(estado.fuerzaBrochaOrganica, decimales: 2))
                                        .monospacedDigit().frame(width: 58, alignment: .trailing)
                                }
                                if estado.modoBrochaOrganica == "PELLIZCAR" {
                                    Text("Positiva afila la cresta; negativa la ensancha.")
                                        .font(Tipo.menor).foregroundStyle(Tinta.cota)
                                }
                            }
                            if estado.zonasDeLaBrocha > 0 {
                                HStack {
                                    Text("\(estado.zonasDeLaBrocha) zonas guardadas")
                                        .font(Tipo.menor).foregroundStyle(Tinta.cota)
                                    Spacer()
                                    Button("Quitarlas") { estado.limpiarZonasDeLaBrocha() }
                                        .controlSize(.small)
                                }
                            }
                            Toggle("Simetría al esculpir", isOn: $estado.simetriaBrochaOrganica)
                            if estado.simetriaBrochaOrganica {
                                Picker("Plano", selection: Binding(
                                    get: { estado.ejeDeSimetriaOrganica },
                                    set: { estado.aplicarEjeDeSimetria($0) }
                                )) {
                                    Text("Izq/der").tag("X")
                                    Text("Arriba/abajo").tag("Y")
                                    Text("Frente/fondo").tag("Z")
                                    Text("Libre").tag("LIBRE")
                                }
                                .pickerStyle(.segmented)
                                Button("Plano desde la cara señalada") {
                                    estado.fijarSimetriaDesdeElPunto()
                                }
                                .controlSize(.small)
                                if let plano = estado.planoDeSimetria {
                                    Text(String(
                                        format: "Normal %.2f, %.2f, %.2f · pasa por %.1f, %.1f, %.1f mm",
                                        plano.normal.x, plano.normal.y, plano.normal.z,
                                        plano.punto.x, plano.punto.y, plano.punto.z
                                    ))
                                    .font(Tipo.cifra)
                                    .foregroundStyle(Tinta.cota)
                                }
                            }
                            HStack {
                                Text("Suavidad")
                                Slider(
                                    value: Binding(
                                        get: { estado.fusionOrganica },
                                        set: { estado.aplicarFusionOrganica($0) }
                                    ),
                                    in: 0.2...8,
                                    onEditingChanged: { editando in
                                        if editando { estado.empezarEdicionContinua() }
                                    }
                                )
                                Text(Cifra.texto(estado.fusionOrganica, decimales: 1))
                                    .monospacedDigit().frame(width: 30)
                            }
                            ayudaDeBrocha(estado.modoBrochaOrganica)
                            .font(Tipo.menor).foregroundStyle(Tinta.cota)
                        }
                    }

                    if estado.tienePerfilSeleccionado {
                        seccion("Boceto") {
                            Button {
                                editandoPerfil = true
                            } label: {
                                Label("Editar perfil visualmente", systemImage: "pencil.and.outline")
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(.mint)
                            Text("Rejilla, ajuste ortogonal y vértices en milímetros.")
                                .font(Tipo.menor).foregroundStyle(Tinta.cota)
                        }
                    }

                    if estado.esBooleanaSeleccionada {
                        seccionDeFilete
                    }

                    if ["SIMETRIA", "REPETICION", "REPETICION_CIRCULAR"].contains(estado.tipoSeleccionado) {
                        seccion("Disposición") {
                            Picker("Eje", selection: Binding(
                                get: { estado.eje },
                                set: { estado.aplicarEje($0) }
                            )) {
                                Text("X").tag("X"); Text("Y").tag("Y"); Text("Z").tag("Z")
                            }
                            .pickerStyle(.segmented)

                            if estado.tipoSeleccionado == "REPETICION" || estado.tipoSeleccionado == "REPETICION_CIRCULAR" {
                                Stepper(
                                    "Copias: \(estado.cuenta)",
                                    value: Binding(get: { estado.cuenta }, set: { estado.aplicarCuenta($0) }),
                                    in: 1...64
                                )
                                .font(Tipo.cuerpo)
                            }
                        }
                    }

                    seccion("Posición y giro") {
                        campoNumerico("X", $estado.posX)
                        campoNumerico("Y", $estado.posY)
                        campoNumerico("Z", $estado.posZ)
                        Divider().padding(.vertical, 2)
                        campoNumerico("Giro X", $estado.giroX, unidad: "°")
                        campoNumerico("Giro Y", $estado.giroY, unidad: "°")
                        campoNumerico("Giro Z", $estado.giroZ, unidad: "°")
                        Divider().padding(.vertical, 2)
                        campoNumerico("Escala", $estado.escala, unidad: "×")
                    }

                    if modoAvanzado {
                        seccion("Envolver en") {
                            HStack(spacing: 5) {
                                ForEach(["VACIADO", "DESFASE", "SIMETRIA", "REPETICION", "REPETICION_CIRCULAR"], id: \.self) { t in
                                    Button(t.capitalized) { estado.envolver(t) }
                                        .buttonStyle(.bordered).controlSize(.small)
                                        .font(Tipo.menor)
                                }
                            }
                        }
                    }
                }

                if modoAvanzado {
                    seccion("Diagnóstico") {
                        fila("Uniforms", "\(estado.editor.numeroDeUniforms)")
                        fila("Último cambio", estado.recompiloElUltimoCambio ? "recompiló" : "solo uniforms")
                        if let aviso = estado.aviso {
                            Text(aviso)
                                .font(Tipo.menor).foregroundStyle(Tinta.riesgo)
                            .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                } else if let aviso = estado.aviso {
                    Text(aviso).font(Tipo.menor).foregroundStyle(Tinta.riesgo)
                }

                seccion("Exportar") {
                    HStack {
                        Text("Detalle").font(Tipo.cuerpo)
                        Slider(value: $estado.resolucionExportacion, in: 0.1...1.5, step: 0.05)
                        Text("\(Cifra.texto(estado.resolucionExportacion, decimales: 2)) mm")
                            .font(Tipo.cifra)
                            .frame(width: 62, alignment: .trailing)
                    }
                    Text("Cuanto más fino, más tarda en generarse y verificarse la malla.")
                        .pieDeAyuda()

                    if estado.exportando {
                        ProgressView(value: estado.progresoExportacion) {
                            Text("Generando y verificando la malla…")
                                .font(Tipo.menor)
                        }
                    } else {
                        Button("Exportar pieza…") { estado.exportarPieza() }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.small)
                            .disabled(estado.editor.estaVacio)
                    }

                    if let resultado = estado.resultadoExportacion {
                        Text(resultado)
                            .font(Tipo.cifra)
                            .foregroundStyle(Tinta.cota)
                            .textSelection(.enabled)
                    }
                }

                seccion("Fabricación") { panelDeFabricacion }

                if modoAvanzado { seccion("Crear con IA local") {
                    TextField("Ej. soporte de móvil de 80 mm…", text: $estado.peticionIA, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(2...5)
                        .onSubmit { estado.construirConIA() }
                    Button(estado.iaTrabajando ? "Construyendo…" : "Construir propuesta", systemImage: "sparkles") {
                        estado.construirConIA()
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                    .disabled(estado.peticionIA.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || estado.iaTrabajando)
                    if let resultado = estado.resultadoIA {
                        Text(resultado).font(Tipo.menor).foregroundStyle(Tinta.cota)
                    }
                    Text("Privado · Hearthia en este Mac · siempre deshacer con ⌘Z")
                        .font(Tipo.pie).foregroundStyle(Tinta.apagado)
                } }

                Spacer(minLength: 0)
            }
            .padding(14)
        }
    }

    // MARK: Analizador de fabricación

    /// El examen de la pieza contra una impresora concreta.
    ///
    /// Cada aviso enseña lo medido, el umbral con el que se compara y de qué perfil
    /// sale ese umbral. Un aviso que no dice contra qué compara no se puede discutir,
    /// y lo que no se puede discutir se acaba ignorando.
    /// Calibrar la máquina: imprimir una probeta y decirle a Yunkil qué salió.
    ///
    /// Es el único sitio del producto donde un número deja de ser una tabla y pasa a ser una
    /// medida de **esta** impresora con **este** material. Va aquí, debajo del perfil, y no
    /// en un asistente aparte: se calibra mirando el mismo perfil que se está usando.
    @ViewBuilder
    private var calibracion: some View {
        DisclosureGroup(isExpanded: $calibrando) {
            VStack(alignment: .leading, spacing: 7) {
                Text("Imprime la probeta y prueba qué estación se traga el pasador sin bailar. "
                     + "Esa es la holgura de tu máquina.")
                    .pieDeAyuda()

                Button("Poner el cupón en el documento", systemImage: "ruler") {
                    estado.generarCuponDeCalibracion()
                }
                .controlSize(.small)

                let estaciones = estado.estacionesDelCupon
                if !estaciones.isEmpty {
                    Picker("Estación", selection: $estacionElegida) {
                        ForEach(Array(estaciones.enumerated()), id: \.offset) { i, e in
                            Text("\(e.indice) · ⌀\(Cifra.texto(e.diametro, decimales: 2)) mm"
                                 + " · holgura \(Cifra.texto(e.holgura, decimales: 2))")
                                .tag(i)
                        }
                    }
                    .labelsHidden().controlSize(.small)

                    TextField("Nombre del perfil calibrado", text: $nombreCalibrado)
                        .textFieldStyle(.roundedBorder).controlSize(.small)
                        .font(Tipo.menor)

                    HStack(spacing: 6) {
                        Button("Guardar calibración") {
                            let propuesto = nombreCalibrado.trimmingCharacters(in: .whitespaces)
                            estado.guardarCalibracion(
                                estacion: estacionElegida,
                                nombre: propuesto.isEmpty ? "\(estado.perfilDeFabricacion) · mi máquina" : propuesto
                            )
                        }
                        .buttonStyle(.borderedProminent).controlSize(.small)

                        if estado.perfilActivoEsPropio {
                            Button("Olvidar") { estado.olvidarPerfil(estado.perfilDeFabricacion) }
                                .controlSize(.small)
                                .help("Borra este perfil calibrado y vuelve al de fábrica")
                        }
                    }

                    Text("Se guarda aparte del documento: la calibración es de la máquina, "
                         + "no de la pieza.")
                        .pieDeAyuda()
                }
            }
            .padding(.top, 4)
        } label: {
            Text("Calibrar la máquina").font(Tipo.menor.weight(.semibold))
        }
        .font(Tipo.menor)
    }

    @ViewBuilder
    private var panelDeFabricacion: some View {
        Picker("Perfil", selection: Binding(
            get: { estado.perfilDeFabricacion },
            // Por `fijarPerfil` y no escribiendo la cadena: cambiar de impresora tiene que
            // volver a derivar las cotas que gobierna un encaje, no solo el siguiente examen.
            set: { estado.fijarPerfil($0) }
        )) {
            ForEach(estado.perfilesDisponibles, id: \.self) { Text($0).tag($0) }
        }
        .labelsHidden().controlSize(.small)

        Text(estado.procedenciaDelPerfil)
            .font(Tipo.pie)
            .foregroundStyle(estado.perfilActivoEsPropio ? Tinta.calibre : Tinta.apagado)

        HStack(spacing: 6) {
            Button(estado.analizando ? "Analizando…" : "Analizar pieza", systemImage: "checkmark.seal") {
                estado.analizarFabricacion()
            }
            .buttonStyle(.borderedProminent).controlSize(.small)
            .disabled(estado.analizando || estado.editor.estaVacio)

            Button("Asentar") { estado.asentarEnPlato() }
                .controlSize(.small)
                .help("Baja la pieza hasta apoyarla en el plato")
        }

        Toggle(" Analizar al crear con IA", isOn: $estado.analizarAlCrear)
            .font(Tipo.menor).controlSize(.mini)
            .onChange(of: estado.analizarAlCrear) { _, nuevo in
                UserDefaults.standard.set(nuevo, forKey: "fab.auto")
            }

        calibracion

        if let informe = estado.informe {
            HStack(spacing: 6) {
                Image(systemName: informe.aptoParaImprimir ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(informe.aptoParaImprimir ? .green : .orange)
                Text("Imprimibilidad \(informe.puntuacion)/100")
                    .font(Tipo.cuerpo.weight(.semibold))
                Spacer()
            }
            Text(informe.resumen).font(Tipo.menor).foregroundStyle(Tinta.cota)

            fila("Volumen", String(format: "%.1f cm³", informe.metricas.volumen / 1000))
            fila("Base", String(format: "%.0f mm²", informe.metricas.areaDeContacto))
            fila("En voladizo", String(format: "%.0f %%", informe.metricas.fraccionEnVoladizo * 100))
            fila("Pared mínima", "\(Cifra.texto(informe.metricas.espesorMinimo, decimales: 2)) mm")

            ForEach(Array(informe.hallazgos.enumerated()), id: \.offset) { _, hallazgo in
                tarjetaDeHallazgo(hallazgo)
            }

            if let mejor = estado.orientaciones.first, !mejor.esLaActual {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Mejor orientación").font(Tipo.menor.weight(.semibold))
                    Text("\(mejor.descripcion): \(Int(mejor.fraccionEnVoladizo * 100)) % en voladizo, "
                         + "base de \(Int(mejor.areaDeContacto)) mm².")
                        .font(Tipo.menor).foregroundStyle(Tinta.cota)
                    Button("Orientar así") { estado.aplicarOrientacion(mejor) }
                        .controlSize(.small)
                }
                .padding(7)
                .background(.blue.opacity(0.09), in: RoundedRectangle(cornerRadius: 6))
            }
        } else if !estado.analizando {
            Text("Sin analizar. El examen mide pared, voladizo, apoyo y encaje contra la impresora elegida.")
                .font(Tipo.pie).foregroundStyle(Tinta.apagado)
        }
    }

    private func tarjetaDeHallazgo(_ hallazgo: Hallazgo) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 5) {
                Circle().fill(colorDe(hallazgo.severidad)).frame(width: 7, height: 7)
                Text(hallazgo.titulo).font(Tipo.cuerpo.weight(.semibold))
                Spacer()
                Text(hallazgo.severidad.etiqueta)
                    .font(Tipo.pie).foregroundStyle(colorDe(hallazgo.severidad))
            }
            if let pieza = hallazgo.piezaNombre {
                Text("en «\(pieza)»").font(Tipo.menor).foregroundStyle(Tinta.cota)
            }
            Text(hallazgo.detalle).font(Tipo.menor).foregroundStyle(Tinta.cota)

            if !hallazgo.correcciones.isEmpty {
                HStack(spacing: 5) {
                    ForEach(Array(hallazgo.correcciones.enumerated()), id: \.offset) { _, correccion in
                        Button(correccion.etiqueta) { estado.aplicarCorreccion(correccion) }
                            .controlSize(.mini)
                    }
                }
            }
        }
        .padding(7)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colorDe(hallazgo.severidad).opacity(0.09), in: RoundedRectangle(cornerRadius: 6))
    }

    private func colorDe(_ severidad: Severidad) -> Color {
        switch severidad {
        case .fallara: Tinta.fallo
        case .probable: Tinta.riesgo
        default: Tinta.calibre
        }
    }

    /// El encaje de la pieza, con la salida explícita para dejar de derivarla.
    private func panelDeEncaje(_ texto: String) -> some View {
        VStack(alignment: .leading, spacing: Hueco.corto) {
            Text(texto)
                .font(Tipo.cifra)
                .foregroundStyle(Tinta.calibre)
                .fixedSize(horizontal: false, vertical: true)
            Text("La cota sale de esta medida. Para cambiar la pieza, cambia la medida.")
                .pieDeAyuda()
            Button("Soltar encaje") { estado.soltarEncaje() }
                .font(Tipo.menor)
        }
    }

    private func deslizador(_ p: Parametro) -> some View {
        let gobernada = estado.clavesGobernadas.contains(p.clave)
        return VStack(alignment: .leading, spacing: Hueco.corto) {
            Cota(
                etiqueta: p.etiqueta,
                valor: Cifra.texto(p.valor, decimales: 2),
                unidad: p.unidad,
                procedencia: gobernada ? "la deriva el encaje" : nil,
                gobernada: gobernada
            )
            if !gobernada {
            Slider(
                value: Binding(
                    get: { p.valor },
                    set: { estado.fijarParametro(p.clave, $0) }
                ),
                in: p.minimo...p.maximo
            ) { empezando in
                // El punto de deshacer se marca al empezar el arrastre, no en cada
                // fotograma: si no, el historial quedaría inservible.
                if empezando { estado.empezarEdicionContinua() }
            }
            }
        }
    }

    private func campoNumerico(_ etiqueta: String, _ valor: Binding<Float>, unidad: String = "mm") -> some View {
        HStack {
            Text(etiqueta).font(Tipo.cuerpo).foregroundStyle(Tinta.cota)
                .frame(width: 52, alignment: .leading)
            TextField("", value: valor, format: .number.precision(.fractionLength(0...2)))
                .textFieldStyle(.roundedBorder)
                .font(Tipo.cifraFuerte)
                .multilineTextAlignment(.trailing)
                .onSubmit { estado.aplicarTransform() }
            Text(unidad).font(Tipo.pie).foregroundStyle(Tinta.apagado)
                .fixedSize()
                .frame(width: 22, alignment: .leading)
        }
    }

    private var barraDeEstado: some View {
        HStack(spacing: 8) {
            Text(estado.estadoDelRender)
            // Hornear una malla descargada son segundos. Se dice encima del visor y no
            // en el inspector: es donde está mirando quien acaba de soltar el archivo.
            if estado.importandoMalla {
                Divider().frame(height: 11)
                ProgressView().controlSize(.small).scaleEffect(0.7).frame(width: 12, height: 12)
                Text("horneando la malla…")
            }
        }
        .font(Tipo.cifraFuerte)
        .foregroundStyle(.white.opacity(0.85))
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 7))
        .padding(12)
    }

    /// Qué hace cada brocha, dicho donde se elige y no en un manual aparte.
    private func ayudaDeBrocha(_ modo: String) -> Text {
        switch modo {
        case "NINGUNA": return Text("Elige una brocha y pincha la superficie.")
        case "ALISAR":
            return Text("Arrastra sobre un bulto para limarlo o sobre un surco para rellenarlo.")
        case "PELLIZCAR":
            return Text("Aprieta el material contra la normal y afila la cresta que señales.")
        case "MOVER":
            return Text("Agarra la superficie y tira: el material se estira sin partirse.")
        case "PROTEGER":
            return Text("Pinta las zonas que ninguna brocha posterior debe tocar. Se guardan con la figura.")
        default:
            return Text("Cada clic modifica volumen y se puede deshacer con ⌘Z.")
        }
    }

    @ViewBuilder
    private func seccion<C: View>(_ titulo: String, @ViewBuilder contenido: () -> C) -> some View {
        seccionCon(titulo, apunte: nil, contenido: contenido)
    }

    /// Sección con un dato corto a la derecha del rótulo: el tipo de la pieza, el perfil.
    private func seccionCon<C: View>(
        _ titulo: String,
        apunte: String?,
        @ViewBuilder contenido: () -> C
    ) -> some View {
        VStack(alignment: .leading, spacing: Hueco.medio) {
            Rotulo(texto: titulo, apunte: apunte)
            contenido()
        }
    }

    private func fila(_ etiqueta: String, _ valor: String) -> some View {
        Cota(etiqueta: etiqueta, valor: valor, unidad: "")
    }
}

private extension Collection {
    subscript(safe index: Index) -> Element? { indices.contains(index) ? self[index] : nil }
}

// El arnés de `tools/estado` compila estos mismos archivos con su propio `main.swift`, y
// dos puntos de entrada no se pueden enlazar juntos. Es la única concesión que el código de
// la aplicación le hace a sus pruebas, y sale barata: una bandera de compilación.
#if !PRUEBAS
@main
#endif
struct YunkilApp: App {

    /// El estado vive en la escena para que los menús puedan invocarlo.
    ///
    /// Sin esto los atajos no llegan: un `keyboardShortcut` declarado dentro de la vista se
    /// anuncia pero no se registra en la barra de menús, y el usuario pulsa ⌘D y no pasa
    /// nada. Es el mismo fallo que ya se cazó en Editorcito.
    @StateObject private var estado = EstadoDeLaApp()

    var body: some Scene {
        WindowGroup("Yunkil") { VistaPrincipal(estado: estado) }
            .commands { MenusDeYunkil(estado: estado) }
    }
}

/// La barra de menús, con los atajos que tiene en los dedos quien viene de un CAD.
struct MenusDeYunkil: Commands {
    @ObservedObject var estado: EstadoDeLaApp

    var body: some Commands {
        CommandGroup(after: .pasteboard) {
            Divider()
            Button("Duplicar") { estado.duplicar() }
                .keyboardShortcut("d", modifiers: .command)
            Button("Copiar pieza") { estado.copiar(estado.seleccion) }
                .keyboardShortcut("c", modifiers: [.command, .shift])
            Button("Pegar pieza") { estado.pegar(en: estado.seleccion) }
                .keyboardShortcut("v", modifiers: [.command, .shift])
                .disabled(!estado.hayAlgoQuePegar)
        }

        CommandMenu("Pieza") {
            Button("Aislar") { estado.aislar(estado.seleccion) }
                .keyboardShortcut("i", modifiers: [.command, .shift])
            Button("Mostrar todo") { estado.mostrarTodo() }
                .keyboardShortcut("i", modifiers: [.command, .option])
            Button("Sacar del grupo") { estado.extraer(estado.seleccion) }
                .keyboardShortcut(.upArrow, modifiers: [.command, .shift])
            Divider()
            Button("Apoyar la cara señalada en el plato") { estado.apoyarLaCaraSenalada() }
                .keyboardShortcut("p", modifiers: [.command, .shift])
            Divider()
            Button("Eliminar") { estado.eliminar() }
                .keyboardShortcut(.delete, modifiers: [])
        }

        CommandMenu("Vista") {
            // Los números son los de cualquier CAD. Reinventarlos solo obligaría a
            // reaprender lo único que alguien ya sabe hacer sin mirar.
            Button("Planta") { estado.mirarDesde(.planta) }
                .keyboardShortcut("1", modifiers: [])
            Button("Alzado") { estado.mirarDesde(.alzado) }
                .keyboardShortcut("3", modifiers: [])
            Button("Perfil") { estado.mirarDesde(.perfil) }
                .keyboardShortcut("7", modifiers: [])
            Button("Isométrica") { estado.mirarDesde(.isometrica) }
                .keyboardShortcut("9", modifiers: [])
            Divider()
            Button(estado.esOrtografica ? "Con perspectiva" : "Sin perspectiva") {
                estado.alternarOrtografica()
            }
            .keyboardShortcut("5", modifiers: [])
            Divider()
            Button(estado.seccionActiva ? "Quitar sección en vivo" : "Sección en vivo") {
                estado.alternarSeccion()
            }
            .keyboardShortcut("6", modifiers: [])
            Divider()
            Button("Enfocar la pieza") { estado.enfocarSeleccion() }
                .keyboardShortcut("f", modifiers: [])
            Button("Encuadrar todo") { estado.encuadrarTodo() }
                .keyboardShortcut("f", modifiers: .shift)
        }
    }
}
