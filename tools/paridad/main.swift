// Arnés de paridad CPU ↔ GPU.
//
// Compila el MSL que generó el núcleo Kotlin, lo evalúa en la GPU sobre los mismos
// puntos que evaluó `SdfNode.evaluar` y compara. Si estos dos números divergen, el
// viewport enseña una geometría y el analizador de fabricación razona sobre otra.
//
// Uso: paridad <directorio-de-casos> [tolerancia]

import Foundation
import Metal

let TOLERANCIA_POR_DEFECTO: Float = 1e-4

struct Caso {
    let nombre: String
    let fuente: String
    let uniforms: [Float]
    let puntos: [SIMD4<Float>]
    let esperado: [Float]
}

func fallar(_ mensaje: String) -> Never {
    FileHandle.standardError.write("error: \(mensaje)\n".data(using: .utf8)!)
    exit(2)
}

func leerCaso(directorio: URL) throws -> Caso {
    let fuente = try String(contentsOf: directorio.appendingPathComponent("shader.metal"), encoding: .utf8)
    let datos = try String(contentsOf: directorio.appendingPathComponent("datos.txt"), encoding: .utf8)

    var lineas = datos.split(separator: "\n", omittingEmptySubsequences: true).makeIterator()

    guard let cabeceraU = lineas.next(), cabeceraU.hasPrefix("uniforms ") else {
        fallar("\(directorio.lastPathComponent): falta la cabecera de uniforms")
    }
    let numeroUniforms = Int(cabeceraU.dropFirst("uniforms ".count))!
    var uniforms: [Float] = []
    if numeroUniforms > 0 {
        guard let fila = lineas.next() else { fallar("faltan los valores de uniforms") }
        uniforms = fila.split(separator: " ").map { Float($0)! }
    } else {
        _ = lineas.next()
    }
    guard uniforms.count == numeroUniforms else {
        fallar("\(directorio.lastPathComponent): se anunciaron \(numeroUniforms) uniforms y llegaron \(uniforms.count)")
    }

    guard let cabeceraP = lineas.next(), cabeceraP.hasPrefix("puntos ") else {
        fallar("\(directorio.lastPathComponent): falta la cabecera de puntos")
    }
    let numeroPuntos = Int(cabeceraP.dropFirst("puntos ".count))!

    var puntos: [SIMD4<Float>] = []
    var esperado: [Float] = []
    puntos.reserveCapacity(numeroPuntos)
    esperado.reserveCapacity(numeroPuntos)

    for _ in 0..<numeroPuntos {
        guard let fila = lineas.next() else { fallar("se acabaron los puntos antes de tiempo") }
        let c = fila.split(separator: " ").map { Float($0)! }
        puntos.append(SIMD4<Float>(c[0], c[1], c[2], 0))
        esperado.append(c[3])
    }

    return Caso(
        nombre: directorio.lastPathComponent,
        fuente: fuente,
        uniforms: uniforms,
        puntos: puntos,
        esperado: esperado
    )
}

/// Núcleo de cómputo que llama al `yk_map` generado. Se añade al vuelo para no
/// contaminar el shader que consume la aplicación real.
let NUCLEO_DE_COMPROBACION = """

kernel void yk_paridad(constant float *u          [[buffer(0)]],
                       device const float4 *pts   [[buffer(1)]],
                       device float *salida       [[buffer(2)]],
                       uint id [[thread_position_in_grid]]) {
    salida[id] = yk_map(pts[id].xyz, u);
}

kernel void yk_poda(constant float *u          [[buffer(0)]],
                    device const float4 *pts   [[buffer(1)]],
                    device float *salida       [[buffer(2)]],
                    uint id [[thread_position_in_grid]]) {
    // La marcha podada nunca puede exagerar: si devolviera más que el campo real,
    // el trazado se pasaría de largo. Se comprueba contra `yk_map` en la GPU para
    // que el desvío de punto flotante sea el mismo en las dos llamadas.
    salida[id] = yk_marcha(pts[id].xyz, u);
}
"""

guard CommandLine.arguments.count >= 2 else {
    fallar("uso: paridad <directorio-de-casos> [tolerancia]")
}
let raiz = URL(fileURLWithPath: CommandLine.arguments[1])
let tolerancia = CommandLine.arguments.count >= 3
    ? Float(CommandLine.arguments[2]) ?? TOLERANCIA_POR_DEFECTO
    : TOLERANCIA_POR_DEFECTO

guard let dispositivo = MTLCreateSystemDefaultDevice() else {
    fallar("no hay dispositivo Metal disponible")
}
guard let cola = dispositivo.makeCommandQueue() else {
    fallar("no se pudo crear la cola de comandos")
}

print("Dispositivo: \(dispositivo.name)")
print("Tolerancia:  \(tolerancia)\n")

let directorios = (try? FileManager.default.contentsOfDirectory(
    at: raiz, includingPropertiesForKeys: nil
).filter { $0.hasDirectoryPath }.sorted { $0.lastPathComponent < $1.lastPathComponent }) ?? []

guard !directorios.isEmpty else {
    fallar("no hay casos en \(raiz.path) — ejecuta antes ./gradlew :core:volcarParidad")
}

var fallos = 0
var peorGlobal: Float = 0

for dir in directorios {
    let caso = try leerCaso(directorio: dir)

    let biblioteca: MTLLibrary
    do {
        biblioteca = try dispositivo.makeLibrary(
            source: caso.fuente + NUCLEO_DE_COMPROBACION,
            options: nil
        )
    } catch {
        print("✗ \(caso.nombre.padding(toLength: 22, withPad: " ", startingAt: 0)) el MSL no compila")
        print("  \(error)")
        fallos += 1
        continue
    }

    guard let funcion = biblioteca.makeFunction(name: "yk_paridad") else {
        fallar("\(caso.nombre): no se encontró yk_paridad")
    }
    guard let funcionPoda = biblioteca.makeFunction(name: "yk_poda") else {
        fallar("\(caso.nombre): no se encontró yk_poda")
    }
    let pipeline = try dispositivo.makeComputePipelineState(function: funcion)
    let pipelinePoda = try dispositivo.makeComputePipelineState(function: funcionPoda)

    let n = caso.puntos.count
    let bytesU = max(caso.uniforms.count, 1) * MemoryLayout<Float>.stride
    let bufU = dispositivo.makeBuffer(length: bytesU, options: .storageModeShared)!
    if !caso.uniforms.isEmpty {
        caso.uniforms.withUnsafeBytes { bufU.contents().copyMemory(from: $0.baseAddress!, byteCount: bytesU) }
    }
    let bufP = dispositivo.makeBuffer(
        bytes: caso.puntos, length: n * MemoryLayout<SIMD4<Float>>.stride, options: .storageModeShared)!
    let bufS = dispositivo.makeBuffer(
        length: n * MemoryLayout<Float>.stride, options: .storageModeShared)!
    let bufS2 = dispositivo.makeBuffer(
        length: n * MemoryLayout<Float>.stride, options: .storageModeShared)!

    // Devuelve el error del command buffer en vez de dejarlo dentro: cuando esto se
    // extrajo a una función para poder lanzar también el shader podado, el `cmd` que
    // se consultaba después se quedó fuera de alcance y el arnés dejó de compilar.
    // Un arnés que no compila es una comprobación que no se está haciendo.
    func lanzar(_ pipeline: MTLComputePipelineState, _ salida: MTLBuffer) -> Error? {
        let cmd = cola.makeCommandBuffer()!
        let enc = cmd.makeComputeCommandEncoder()!
        enc.setComputePipelineState(pipeline)
        enc.setBuffer(bufU, offset: 0, index: 0)
        enc.setBuffer(bufP, offset: 0, index: 1)
        enc.setBuffer(salida, offset: 0, index: 2)
        let ancho = min(pipeline.maxTotalThreadsPerThreadgroup, 256)
        enc.dispatchThreads(MTLSize(width: n, height: 1, depth: 1),
                            threadsPerThreadgroup: MTLSize(width: ancho, height: 1, depth: 1))
        enc.endEncoding()
        cmd.commit()
        cmd.waitUntilCompleted()
        return cmd.error
    }

    if let err = lanzar(pipeline, bufS) {
        print("✗ \(caso.nombre): la GPU falló — \(err)")
        fallos += 1
        continue
    }
    if let err = lanzar(pipelinePoda, bufS2) {
        print("✗ \(caso.nombre): la GPU falló en la poda — \(err)")
        fallos += 1
        continue
    }

    let obtenido = UnsafeBufferPointer(
        start: bufS.contents().bindMemory(to: Float.self, capacity: n), count: n)
    let podado = UnsafeBufferPointer(
        start: bufS2.contents().bindMemory(to: Float.self, capacity: n), count: n)

    var peor: Float = 0
    var indicePeor = 0
    var desviados = 0
    var podasIncorrectas = 0
    var peorPoda: Float = 0
    for i in 0..<n {
        let diff = abs(obtenido[i] - caso.esperado[i])
        if diff > peor { peor = diff; indicePeor = i }
        if diff > tolerancia { desviados += 1 }
        // La poda devuelve una cota inferior: nunca más lejos que el campo real.
        // El margen es el de la tolerancia, para el desvío de punto flotante.
        // La poda es **exacta**: se salta ramas que no pueden ganar y el valor del
        // campo no cambia. Así que aquí se compara la igualdad y no solo que la
        // marcha no se pase.
        //
        // Comprobar solo «no se pasa» dejaba pasar la mitad del fallo: cuando el
        // cuerpo podado leía los uniforms equivocados y salía un número *menor*, la
        // comprobación lo daba por bueno. Era seguro para el trazado y era otra
        // pieza en la pantalla.
        let exceso = abs(podado[i] - obtenido[i])
        if exceso > tolerancia { podasIncorrectas += 1 }
        if exceso > peorPoda { peorPoda = exceso }
    }
    peorGlobal = max(peorGlobal, peor)

    let nombre = caso.nombre.padding(toLength: 22, withPad: " ", startingAt: 0)
    if desviados == 0 && podasIncorrectas == 0 {
        print("✓ \(nombre) \(n) puntos · peor desvío \(String(format: "%.2e", peor))")
    } else {
        fallos += 1
        let p = caso.puntos[indicePeor]
        print("✗ \(nombre) \(desviados)/\(n) fuera de tolerancia · peor \(String(format: "%.4e", peor))")
        if podasIncorrectas > 0 {
            print("    \(podasIncorrectas) puntos donde la marcha no coincide con el campo, " +
                  "hasta \(String(format: "%.4e", peorPoda)) mm de diferencia")
        }
        print("    en (\(p.x), \(p.y), \(p.z)): Kotlin \(caso.esperado[indicePeor]) vs Metal \(obtenido[indicePeor])")
    }
}

print("")
if fallos == 0 {
    print("PARIDAD CORRECTA — \(directorios.count) casos, peor desvío global \(String(format: "%.2e", peorGlobal))")
    exit(0)
} else {
    print("PARIDAD ROTA — \(fallos) de \(directorios.count) casos divergen")
    exit(1)
}
