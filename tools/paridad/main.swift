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

/// Un campo horneado del caso: dimensiones de la rejilla y sus muestras en crudo.
struct CampoDelCaso {
    let nx: Int
    let ny: Int
    let nz: Int
    let muestras: [Float]
}

struct Caso {
    let nombre: String
    let fuente: String
    let uniforms: [Float]
    let campos: [CampoDelCaso]
    let puntos: [SIMD4<Float>]
    let esperado: [Float]
    /// Lo que vale el árbol del fantasma en esos mismos puntos, cuando el caso lleva
    /// una previsualización. Los dos árboles comparten el buffer de uniforms, así que
    /// esta columna es la única forma de saber que el segundo lee sus propios huecos.
    let esperadoFantasma: [Float]?
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

    // Los campos horneados. Es el único nodo cuyo shader lee una textura en vez de
    // hacer aritmética, así que sin esto la paridad no cubriría la malla importada —y
    // se estaría afirmando que el viewport y el exportador coinciden sobre una
    // geometría que nadie ha comparado—.
    guard let cabeceraC = lineas.next(), cabeceraC.hasPrefix("campos ") else {
        fallar("\(directorio.lastPathComponent): falta la cabecera de campos")
    }
    let numeroCampos = Int(cabeceraC.dropFirst("campos ".count))!
    var campos: [CampoDelCaso] = []
    for i in 0..<numeroCampos {
        guard let fila = lineas.next() else { fallar("faltan las cotas del campo \(i)") }
        let d = fila.split(separator: " ").map { Int($0)! }
        let crudo = try Data(contentsOf: directorio.appendingPathComponent("campo\(i).bin"))
        let esperadas = d[0] * d[1] * d[2]
        guard crudo.count == esperadas * 4 else {
            fallar("\(directorio.lastPathComponent): campo\(i).bin tiene \(crudo.count / 4) muestras y la rejilla pide \(esperadas)")
        }
        let muestras = crudo.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        campos.append(CampoDelCaso(nx: d[0], ny: d[1], nz: d[2], muestras: muestras))
    }

    guard let cabeceraF = lineas.next(), cabeceraF.hasPrefix("fantasma ") else {
        fallar("\(directorio.lastPathComponent): falta la cabecera de fantasma")
    }
    let hayFantasma = cabeceraF.dropFirst("fantasma ".count) == "1"

    guard let cabeceraP = lineas.next(), cabeceraP.hasPrefix("puntos ") else {
        fallar("\(directorio.lastPathComponent): falta la cabecera de puntos")
    }
    let numeroPuntos = Int(cabeceraP.dropFirst("puntos ".count))!

    var puntos: [SIMD4<Float>] = []
    var esperado: [Float] = []
    var esperadoFantasma: [Float] = []
    puntos.reserveCapacity(numeroPuntos)
    esperado.reserveCapacity(numeroPuntos)

    for _ in 0..<numeroPuntos {
        guard let fila = lineas.next() else { fallar("se acabaron los puntos antes de tiempo") }
        let c = fila.split(separator: " ").map { Float($0)! }
        puntos.append(SIMD4<Float>(c[0], c[1], c[2], 0))
        esperado.append(c[3])
        if hayFantasma {
            guard c.count >= 5 else {
                fallar("\(directorio.lastPathComponent): el caso declara fantasma y a los puntos les falta su columna")
            }
            esperadoFantasma.append(c[4])
        }
    }

    return Caso(
        nombre: directorio.lastPathComponent,
        fuente: fuente,
        uniforms: uniforms,
        campos: campos,
        puntos: puntos,
        esperado: esperado,
        esperadoFantasma: hayFantasma ? esperadoFantasma : nil
    )
}

/// Núcleo de cómputo que llama al `yk_map` generado. Se añade al vuelo para no
/// contaminar el shader que consume la aplicación real.
func nucleoDeComprobacion(campos: Int, fantasma: Bool) -> String {
    // Con campos, el `yk_map` generado lleva el array de texturas en la firma, así que
    // el núcleo tiene que declararlo y pasárselo. Sin campos se emite exactamente el
    // texto de siempre: los 24 casos que ya pasaban no cambian ni un carácter.
    let param = campos > 0
        ? ",\n                       array<texture3d<float>, \(campos)> yk_campos [[texture(0)]]"
        : ""
    let arg = campos > 0 ? ", yk_campos" : ""

    // El fantasma lee del mismo buffer que el documento, desplazado por el tamaño
    // entero de este. Que ese desplazamiento sea el correcto no se puede comprobar
    // leyendo el código: se comprueba aquí, contra lo que evaluó Kotlin.
    var nucleoFantasma = ""
    if fantasma {
        nucleoFantasma = """

        kernel void yk_paridadFantasma(constant float *u        [[buffer(0)]],
                                       device const float4 *pts [[buffer(1)]],
                                       device float *salida     [[buffer(2)]],
                                       uint id [[thread_position_in_grid]]\(param)) {
            salida[id] = yk_fantasma(pts[id].xyz, u\(arg));
        }
        """
    }

    return """

kernel void yk_paridad(constant float *u          [[buffer(0)]],
                       device const float4 *pts   [[buffer(1)]],
                       device float *salida       [[buffer(2)]],
                       uint id [[thread_position_in_grid]]\(param)) {
    salida[id] = yk_map(pts[id].xyz, u\(arg));
}

kernel void yk_poda(constant float *u          [[buffer(0)]],
                    device const float4 *pts   [[buffer(1)]],
                    device float *salida       [[buffer(2)]],
                    uint id [[thread_position_in_grid]]\(param)) {
    // La marcha podada nunca puede exagerar: si devolviera más que el campo real,
    // el trazado se pasaría de largo. Se comprueba contra `yk_map` en la GPU para
    // que el desvío de punto flotante sea el mismo en las dos llamadas.
    salida[id] = yk_marcha(pts[id].xyz, u\(arg));
}
\(nucleoFantasma)
"""
}

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
            source: caso.fuente + nucleoDeComprobacion(
                campos: caso.campos.count, fantasma: caso.esperadoFantasma != nil
            ),
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
    var pipelineFantasma: MTLComputePipelineState?
    if caso.esperadoFantasma != nil {
        guard let f = biblioteca.makeFunction(name: "yk_paridadFantasma") else {
            fallar("\(caso.nombre): no se encontró yk_paridadFantasma")
        }
        pipelineFantasma = try dispositivo.makeComputePipelineState(function: f)
    }

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
    let bufS3 = dispositivo.makeBuffer(
        length: n * MemoryLayout<Float>.stride, options: .storageModeShared)!

    // Las texturas del caso, con el mismo formato y el mismo filtro que usa la
    // aplicación: si el arnés muestreara distinto, estaría comprobando un shader que
    // nadie ejecuta.
    var texturas: [MTLTexture] = []
    for campo in caso.campos {
        let d = MTLTextureDescriptor()
        d.textureType = .type3D
        d.pixelFormat = .r32Float
        d.width = campo.nx
        d.height = campo.ny
        d.depth = campo.nz
        d.usage = .shaderRead
        d.storageMode = .shared
        guard let t = dispositivo.makeTexture(descriptor: d) else {
            fallar("\(caso.nombre): no se pudo crear la textura \(campo.nx)x\(campo.ny)x\(campo.nz)")
        }
        campo.muestras.withUnsafeBytes { bytes in
            t.replace(
                region: MTLRegionMake3D(0, 0, 0, campo.nx, campo.ny, campo.nz),
                mipmapLevel: 0,
                slice: 0,
                withBytes: bytes.baseAddress!,
                bytesPerRow: campo.nx * MemoryLayout<Float>.size,
                bytesPerImage: campo.nx * campo.ny * MemoryLayout<Float>.size
            )
        }
        texturas.append(t)
    }

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
        if !texturas.isEmpty { enc.setTextures(texturas, range: 0..<texturas.count) }
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
    if let pf = pipelineFantasma, let err = lanzar(pf, bufS3) {
        print("✗ \(caso.nombre): la GPU falló en el fantasma — \(err)")
        fallos += 1
        continue
    }

    let obtenido = UnsafeBufferPointer(
        start: bufS.contents().bindMemory(to: Float.self, capacity: n), count: n)
    let podado = UnsafeBufferPointer(
        start: bufS2.contents().bindMemory(to: Float.self, capacity: n), count: n)

    let deFantasma = UnsafeBufferPointer(
        start: bufS3.contents().bindMemory(to: Float.self, capacity: n), count: n)

    var peor: Float = 0
    var indicePeor = 0
    var desviados = 0
    var podasIncorrectas = 0
    var peorPoda: Float = 0
    var fantasmasDesviados = 0
    var peorFantasma: Float = 0
    var indicePeorFantasma = 0
    for i in 0..<n {
        if let esperadoF = caso.esperadoFantasma {
            let diffF = abs(deFantasma[i] - esperadoF[i])
            if diffF > peorFantasma { peorFantasma = diffF; indicePeorFantasma = i }
            if diffF > tolerancia { fantasmasDesviados += 1 }
        }
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
    peorGlobal = max(peorGlobal, max(peor, peorFantasma))

    let nombre = caso.nombre.padding(toLength: 22, withPad: " ", startingAt: 0)
    if desviados == 0 && podasIncorrectas == 0 && fantasmasDesviados == 0 {
        let cola = caso.esperadoFantasma == nil
            ? ""
            : " · fantasma \(String(format: "%.2e", peorFantasma))"
        print("✓ \(nombre) \(n) puntos · peor desvío \(String(format: "%.2e", peor))\(cola)")
    } else {
        fallos += 1
        let p = caso.puntos[indicePeor]
        print("✗ \(nombre) \(desviados)/\(n) fuera de tolerancia · peor \(String(format: "%.4e", peor))")
        if podasIncorrectas > 0 {
            print("    \(podasIncorrectas) puntos donde la marcha no coincide con el campo, " +
                  "hasta \(String(format: "%.4e", peorPoda)) mm de diferencia")
        }
        if fantasmasDesviados > 0, let esperadoF = caso.esperadoFantasma {
            let q = caso.puntos[indicePeorFantasma]
            print("    \(fantasmasDesviados)/\(n) puntos donde el fantasma diverge, " +
                  "hasta \(String(format: "%.4e", peorFantasma)) mm")
            print("    en (\(q.x), \(q.y), \(q.z)): Kotlin \(esperadoF[indicePeorFantasma]) " +
                  "vs Metal \(deFantasma[indicePeorFantasma])")
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
