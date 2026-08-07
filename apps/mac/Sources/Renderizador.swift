import Foundation
import Metal
import MetalKit
import YunkilCore
import simd

/// Plano de sección en vivo: un punto y una normal en milímetros del mundo.
/// Es estado de vista —como la cámara— y vive fuera del documento.
struct PlanoDeSeccion {
    var punto: SIMD3<Float>
    var normal: SIMD3<Float>
    /// Grosor mínimo de pared del perfil activo; el tinte de la cara de corte
    /// compara contra esto. Lo manda el núcleo: es un umbral de fabricación.
    var grosorMinimoPared: Float
}

/// Dibuja el documento raymarcheando el shader que generó el núcleo Kotlin.
///
/// El reparto de responsabilidades es deliberado: aquí no hay geometría ni reglas,
/// solo el oficio de poner píxeles. Todo lo que sabe de sólidos vive en Kotlin.
final class Renderizador: NSObject, MTKViewDelegate {

    private struct EscenaGPU {
        var plato: SIMD4<Float> // semiancho, semifondo, altura, paso de rejilla
        var plano: SIMD4<Float> // xyz = punto del plano de sección, w = 1 activo
        var planoN: SIMD4<Float> // xyz = normal del plano, w = grosor mínimo de pared
        var planoR: SIMD4<Float> // x = semilado del rectángulo visible del plano
    }

    private let dispositivo: MTLDevice
    private let cola: MTLCommandQueue
    private var pipeline: MTLRenderPipelineState?

    private let editor: Editor
    private var bufferDeUniforms: MTLBuffer?
    private var huellaCompilada: String = ""
    private var alturaPlato: Float = 0

    var camara = CamaraOrbital()
    private let gobernador: GobernadorDeRecursos

    /// Plano de sección en vivo, cuando la sección está activa.
    ///
    /// Es estado de vista, como la cámara: no entra en el documento ni en el historial.
    /// El shader lo usa para recortar el campo (`max(d, plano)`) y para teñir la cara de
    /// corte según el grosor de pared.
    var planoDeSeccion: PlanoDeSeccion?

    private(set) var estado: String = ""
    var alActualizarEstado: ((String) -> Void)?

    /// Último error de compilación. Cuando lo hay se conserva el pipeline anterior
    /// y se sigue dibujando: el usuario no debe encontrarse una pantalla negra.
    private(set) var ultimoError: String?

    init?(vista: MTKView, editor: Editor) {
        guard let dispositivo = MTLCreateSystemDefaultDevice(),
              let cola = dispositivo.makeCommandQueue() else { return nil }

        self.dispositivo = dispositivo
        self.cola = cola
        self.editor = editor

        let hz = Double(vista.preferredFramesPerSecond > 0 ? vista.preferredFramesPerSecond : 60)
        self.gobernador = GobernadorDeRecursos(objetivoDeFotograma: 1.0 / hz)

        super.init()

        vista.device = dispositivo
        vista.colorPixelFormat = .bgra8Unorm
        // Sin buffer de profundidad: el raymarcher resuelve la visibilidad por sí
        // mismo, así que pedirlo solo gastaría ancho de banda.
        vista.depthStencilPixelFormat = .invalid
        vista.clearColor = MTLClearColor(red: 0.04, green: 0.045, blue: 0.055, alpha: 1)

        encuadrar()
        actualizarAlturaPlato()
        compilar()
        actualizarUniforms()
    }

    // MARK: - Sincronización con el documento

    /// Recoge los cambios del editor.
    ///
    /// - Parameter recompilar: lo dice el propio editor comparando huellas
    ///   topológicas. Mover un parámetro llega aquí como `false` y cuesta un
    ///   `memcpy`; añadir una pieza llega como `true` y cuesta una compilación.
    func sincronizar(recompilar: Bool) {
        if recompilar || pipeline == nil { compilar() }
        actualizarUniforms()
        actualizarAlturaPlato()
    }

    func encuadrar() {
        let mn = editor.cotaMinima.map { $0.floatValue }
        let mx = editor.cotaMaxima.map { $0.floatValue }
        guard mn.count == 3, mx.count == 3 else { return }
        camara.encuadrar(
            minimo: SIMD3<Float>(mn[0], mn[1], mn[2]),
            maximo: SIMD3<Float>(mx[0], mx[1], mx[2])
        )
    }

    private func compilar() {
        guard editor.huellaTopologica != huellaCompilada || pipeline == nil else { return }

        do {
            let biblioteca = try dispositivo.makeLibrary(source: editor.fuenteMsl, options: nil)
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = biblioteca.makeFunction(name: "yk_vertex")
            descriptor.fragmentFunction = biblioteca.makeFunction(name: "yk_fragment")
            descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm

            pipeline = try dispositivo.makeRenderPipelineState(descriptor: descriptor)
            huellaCompilada = editor.huellaTopologica
            ultimoError = nil
        } catch {
            // Se conserva el pipeline anterior a propósito.
            ultimoError = "\(error)"
            NSLog("Yunkil: el shader no compiló — %@", "\(error)")
        }
    }

    private func actualizarUniforms() {
        let valores = editor.uniforms().map { $0.floatValue }
        let bytes = max(valores.count, 1) * MemoryLayout<Float>.stride

        if bufferDeUniforms == nil || bufferDeUniforms!.length < bytes {
            bufferDeUniforms = dispositivo.makeBuffer(length: bytes, options: .storageModeShared)
        }
        guard let buffer = bufferDeUniforms, !valores.isEmpty else { return }
        valores.withUnsafeBytes {
            buffer.contents().copyMemory(from: $0.baseAddress!, byteCount: bytes)
        }
    }

    private func actualizarAlturaPlato() {
        // Índice 1: el plato está en la cota mínima en **Y**, que es la vertical.
        // Con `.first` se leía la X, y en cualquier pieza más ancha que alta el
        // plato aparecía flotando o cortándola por la mitad.
        let cotas = editor.cotaMinima
        alturaPlato = (cotas.count > 1 ? cotas[1].floatValue : 0) - 0.5
    }

    /// Semilado del rectángulo visible del plano de sección: cubre el modelo
    /// con un margen, para que la hoja se vea y se pueda agarrar.
    private func semiladoDelPlano() -> Float {
        let mn = editor.cotaMinima.map { $0.floatValue }
        let mx = editor.cotaMaxima.map { $0.floatValue }
        guard mn.count == 3, mx.count == 3 else { return 80 }
        let lados = SIMD3<Float>(mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2])
        return max(simd_reduce_max(lados) * 0.6, 40)
    }

    // MARK: - MTKViewDelegate

    func mtkView(_ vista: MTKView, drawableSizeWillChange tamano: CGSize) {}

    func draw(in vista: MTKView) {
        guard let pipeline,
              let descriptor = vista.currentRenderPassDescriptor,
              let drawable = vista.currentDrawable,
              let buffer = bufferDeUniforms,
              let comando = cola.makeCommandBuffer(),
              let codificador = comando.makeRenderCommandEncoder(descriptor: descriptor)
        else { return }

        var camaraGPU = camara.empaquetar(
            resolucion: SIMD2<Float>(Float(vista.drawableSize.width), Float(vista.drawableSize.height)),
            // El paso lo deciden dos cosas a la vez: lo que el gobernador cree que cabe en
            // el fotograma, y lo que el campo permite. Un filete local mezcla con una
            // anchura que cambia con la posición, y ahí avanzar la distancia entera se
            // salta la superficie justo en el canto, que es donde se está mirando. El
            // núcleo publica ese límite porque es él quien sabe qué hay en el árbol.
            escalaPasos: gobernador.escalaDePasos * editor.pasoSeguroDelShader,
            // El umbral de impacto sigue a la distancia de la cámara: fijo en
            // milímetros daría bordes sucios de lejos y gastaría pasos de cerca.
            epsilonRelativo: 0.0006
        )

        codificador.setRenderPipelineState(pipeline)
        codificador.setFragmentBytes(&camaraGPU, length: MemoryLayout<CamaraGPU>.stride, index: 0)
        codificador.setFragmentBuffer(buffer, offset: 0, index: 1)
        var escena = EscenaGPU(
            plato: SIMD4<Float>(128, 128, alturaPlato, 10),
            plano: SIMD4<Float>(0, 0, 0, 0),
            planoN: SIMD4<Float>(0, 1, 0, 0.8),
            planoR: SIMD4<Float>(80, 0, 0, 0)
        )
        if let plano = planoDeSeccion {
            escena.plano = SIMD4<Float>(plano.punto.x, plano.punto.y, plano.punto.z, 1)
            escena.planoN = SIMD4<Float>(plano.normal.x, plano.normal.y, plano.normal.z, plano.grosorMinimoPared)
            escena.planoR.x = semiladoDelPlano()
        }
        codificador.setFragmentBytes(&escena, length: MemoryLayout<EscenaGPU>.stride, index: 2)
        codificador.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        codificador.endEncoding()

        comando.present(drawable)
        comando.commit()

        gobernador.registrarFotograma()
        aplicarEscalaDeRender(vista)

        let texto = gobernador.descripcionDelEstado + (ultimoError == nil ? "" : " · shader con error")
        if texto != estado {
            estado = texto
            alActualizarEstado?(texto)
        }
    }

    /// Ajusta la resolución interna al presupuesto. La capa estira el resultado, que
    /// es mucho más barato que recortar calidad de sombreado.
    private func aplicarEscalaDeRender(_ vista: MTKView) {
        let factorPantalla = vista.window?.backingScaleFactor ?? 2.0
        let nativo = CGSize(
            width: vista.bounds.width * factorPantalla,
            height: vista.bounds.height * factorPantalla
        )
        let deseado = CGSize(
            width: max(round(nativo.width * CGFloat(gobernador.escalaDeRender)), 64),
            height: max(round(nativo.height * CGFloat(gobernador.escalaDeRender)), 64)
        )
        if abs(deseado.width - vista.drawableSize.width) > 1
            || abs(deseado.height - vista.drawableSize.height) > 1 {
            vista.drawableSize = deseado
        }
    }
}
