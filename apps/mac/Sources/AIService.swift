import AppKit
import Foundation
import SwiftUI

enum AIProviderChoice: String, CaseIterable, Identifiable, Sendable {
    case local
    case openCodeGo

    var id: String { rawValue }
    var name: String { self == .local ? "Local privado" : "OpenCode Go" }
}

struct AIModelOption: Identifiable, Hashable {
    let id: String
    let name: String
    let api: String
}

struct AISelection: Sendable {
    let provider: AIProviderChoice
    let model: String
    let api: String
}

@MainActor
final class AISettings: ObservableObject {
    @Published var provider: AIProviderChoice {
        didSet { UserDefaults.standard.set(provider.rawValue, forKey: "ai.provider"); selectDefault(); refreshStatus() }
    }
    @Published var model: String {
        didSet { UserDefaults.standard.set(model, forKey: "ai.model.\(provider.rawValue)") }
    }
    @Published private(set) var localModels = [AIModelOption(id: "qwen3.5-9b", name: "Qwen3.5 9B · rápido", api: "chat")]
    @Published private(set) var status = ""
    @Published private(set) var refreshing = false

    init() {
        provider = AIProviderChoice(rawValue: UserDefaults.standard.string(forKey: "ai.provider") ?? "") ?? .local
        model = UserDefaults.standard.string(forKey: "ai.model.local") ?? "qwen3.5-9b"
        selectDefault()
        refreshStatus()
        Task { await refreshLocalModels() }
    }

    var models: [AIModelOption] { provider == .local ? localModels : Self.goModels }
    var selection: AISelection {
        let option = models.first(where: { $0.id == model }) ?? models[0]
        return AISelection(provider: provider, model: option.id, api: option.api)
    }

    func refreshLocalModels() async {
        refreshing = true
        defer { refreshing = false }
        do {
            let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:9292/v1/models")!)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let entries = root["data"] as? [[String: Any]] else { throw AIServiceError.unavailable }
            let discovered = entries.compactMap { entry -> AIModelOption? in
                guard let id = entry["id"] as? String,
                      !id.contains("embedding"), !id.contains("autocomplete") else { return nil }
                return AIModelOption(id: id, name: entry["name"] as? String ?? id, api: "chat")
            }
            if !discovered.isEmpty { localModels = discovered }
            if !localModels.contains(where: { $0.id == model }) { selectDefault() }
            status = "Hearthia disponible · datos en este Mac"
        } catch {
            status = "Hearthia no responde · se intentará al crear"
        }
    }

    private func selectDefault() {
        let key = "ai.model.\(provider.rawValue)"
        let fallback = provider == .local ? "qwen3.5-9b" : "deepseek-v4-pro"
        let saved = UserDefaults.standard.string(forKey: key) ?? fallback
        let available = provider == .local ? localModels : Self.goModels
        model = available.contains(where: { $0.id == saved }) ? saved : fallback
    }

    /// Aviso cuando se va a mandar una imagen a un modelo que quizá no la ve.
    ///
    /// Es un aviso y no un candado a propósito: del endpoint local solo se conoce el
    /// identificador, no si lleva proyector de visión cargado, y bloquear por una
    /// lista escrita a mano envejecería mal en cuanto añadas un modelo. Del stack
    /// instalado, el único con `mmproj` es el ternario Bonsai.
    var avisoDeImagen: String? {
        guard provider == .local else { return nil }
        let ve = Self.modelosLocalesConVision.contains { model.localizedCaseInsensitiveContains($0) }
        return ve ? nil : "«\(model)» probablemente no ve imágenes. El único local con visión es bonsai-ternary-27b."
    }

    private static let modelosLocalesConVision = ["bonsai", "-vl", "vision"]

    private func refreshStatus() {
        if provider == .local {
            status = "Privado · sin salir de este Mac"
        } else {
            status = OpenCodeCredential.apiKey() == nil
                ? "Falta conectar OpenCode Go con /connect"
                : "OpenCode Go conectado · puede consumir tu cuota"
        }
    }

    private static let localFallback = [AIModelOption(id: "qwen3.5-9b", name: "Qwen3.5 9B · rápido", api: "chat")]
    private static let goModels = [
        AIModelOption(id: "deepseek-v4-flash", name: "DeepSeek V4 Flash · rápido y económico", api: "chat"),
        AIModelOption(id: "deepseek-v4-pro", name: "DeepSeek V4 Pro · equilibrado", api: "chat"),
        AIModelOption(id: "glm-5.2", name: "GLM-5.2 · razonamiento", api: "chat"),
        AIModelOption(id: "glm-5.1", name: "GLM-5.1", api: "chat"),
        AIModelOption(id: "kimi-k3", name: "Kimi K3 · máxima capacidad", api: "chat"),
        AIModelOption(id: "kimi-k2.7-code", name: "Kimi K2.7 Code", api: "chat"),
        AIModelOption(id: "kimi-k2.6", name: "Kimi K2.6", api: "chat"),
        AIModelOption(id: "mimo-v2.5", name: "MiMo V2.5 · muy económico", api: "chat"),
        AIModelOption(id: "mimo-v2.5-pro", name: "MiMo V2.5 Pro", api: "chat"),
        AIModelOption(id: "hy3", name: "Hy3", api: "chat"),
        AIModelOption(id: "grok-4.5", name: "Grok 4.5", api: "chat"),
        AIModelOption(id: "minimax-m3", name: "MiniMax M3", api: "messages"),
        AIModelOption(id: "minimax-m2.7", name: "MiniMax M2.7", api: "messages"),
        AIModelOption(id: "qwen3.8-max", name: "Qwen3.8 Max", api: "messages"),
        AIModelOption(id: "qwen3.7-max", name: "Qwen3.7 Max", api: "messages"),
        AIModelOption(id: "qwen3.7-plus", name: "Qwen3.7 Plus", api: "messages"),
        AIModelOption(id: "qwen3.6-plus", name: "Qwen3.6 Plus", api: "messages"),
    ]
}

struct AIModelSelector: View {
    @ObservedObject var settings: AISettings

    var body: some View {
        HStack(spacing: 6) {
            Picker("Proveedor", selection: $settings.provider) {
                ForEach(AIProviderChoice.allCases) { Text($0.name).tag($0) }
            }
            .labelsHidden().frame(width: 125)
            Picker("Modelo", selection: $settings.model) {
                ForEach(settings.models) { Text($0.name).tag($0.id) }
            }
            .labelsHidden().frame(width: 210)
        }
        .controlSize(.small)
        .help(settings.status)
    }
}

/// Una imagen de referencia lista para viajar dentro de la petición.
///
/// La foto **se reduce siempre**, y no es una optimización: una imagen de móvil son
/// doce megapíxeles, que en base64 pasan de los seis millones de caracteres. El
/// modelo local con visión tiene 16K de contexto. Mandarla entera no es «más lento»:
/// es que no llega, y el error que devuelve el servidor no menciona la imagen por
/// ninguna parte, así que se pierde media tarde buscándolo en otro sitio.
///
/// 1024 px en el lado largo es de sobra para leer la forma y las proporciones de una
/// pieza, que es lo único que se le pide a la imagen.
struct ImagenDeReferencia: Sendable {
    let datos: Data
    let tipoMime: String

    static let ladoMaximo: CGFloat = 1024

    var base64: String { datos.base64EncodedString() }
    var uriDeDatos: String { "data:\(tipoMime);base64,\(base64)" }

    /// Carga una imagen del disco y la deja en tamaño y formato de envío.
    static func desde(url: URL) -> ImagenDeReferencia? {
        guard let imagen = NSImage(contentsOf: url) else { return nil }
        return desde(imagen: imagen)
    }

    static func desde(imagen: NSImage) -> ImagenDeReferencia? {
        guard let original = imagen.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }

        let ancho = CGFloat(original.width)
        let alto = CGFloat(original.height)
        let escala = min(1, ladoMaximo / max(ancho, alto))
        let destino = NSSize(width: (ancho * escala).rounded(), height: (alto * escala).rounded())

        let reducida = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: Int(destino.width), pixelsHigh: Int(destino.height),
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
        )
        guard let reducida else { return nil }
        reducida.size = destino

        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: reducida)
        NSGraphicsContext.current?.imageInterpolation = .high
        imagen.draw(in: NSRect(origin: .zero, size: destino))
        NSGraphicsContext.restoreGraphicsState()

        // JPEG y no PNG: una foto en PNG pesa varias veces más sin aportar nada a
        // quien solo va a leer contornos.
        guard let jpeg = reducida.representation(using: .jpeg, properties: [.compressionFactor: 0.8]) else {
            return nil
        }
        return ImagenDeReferencia(datos: jpeg, tipoMime: "image/jpeg")
    }
}

enum AITransport {
    static func complete(
        selection: AISelection,
        system: String,
        user: String,
        maxTokens: Int,
        imagen: ImagenDeReferencia? = nil
    ) async throws -> String {
        let token: String?
        let base: String
        if selection.provider == .local {
            token = "local"
            base = "http://127.0.0.1:9292/v1"
        } else {
            guard let key = OpenCodeCredential.apiKey() else { throw AIServiceError.missingOpenCodeCredential }
            token = key
            base = "https://opencode.ai/zen/go/v1"
        }

        let isMessages = selection.api == "messages"
        let endpoint = isMessages ? "\(base)/messages" : "\(base)/chat/completions"
        var request = URLRequest(url: URL(string: endpoint)!)
        request.httpMethod = "POST"
        request.timeoutInterval = 240
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token!)", forHTTPHeaderField: "Authorization")
        // Sin imagen el contenido sigue siendo una cadena suelta, que es lo que
        // entiende todo servidor de texto. Solo se pasa a partes cuando hace falta.
        //
        // El texto va DESPUÉS de la imagen a propósito: la instrucción es lo último
        // que lee el modelo y es lo que tiene que pesar más al empezar a responder.
        let contenidoOpenAi: Any = imagen.map { img in
            [
                ["type": "image_url", "image_url": ["url": img.uriDeDatos]],
                ["type": "text", "text": user],
            ] as [[String: Any]]
        } ?? user

        let contenidoMessages: Any = imagen.map { img in
            [
                ["type": "image", "source": ["type": "base64", "media_type": img.tipoMime, "data": img.base64]],
                ["type": "text", "text": user],
            ] as [[String: Any]]
        } ?? user

        let body: [String: Any] = isMessages
            ? ["model": selection.model, "system": system, "messages": [["role": "user", "content": contenidoMessages]], "max_tokens": maxTokens, "temperature": 0.1]
            : ["model": selection.model, "messages": [["role": "system", "content": system], ["role": "user", "content": contenidoOpenAi]], "max_tokens": maxTokens, "temperature": 0.1]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"]
            throw AIServiceError.server("El proveedor rechazó la petición (\((response as? HTTPURLResponse)?.statusCode ?? 0)): \(message ?? "sin detalle")")
        }
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        if isMessages,
           let content = root?["content"] as? [[String: Any]],
           let text = content.first(where: { $0["type"] as? String == "text" })?["text"] as? String { return text }
        if let choices = root?["choices"] as? [[String: Any]],
           let message = choices.first?["message"] as? [String: Any],
           let text = message["content"] as? String { return text }
        throw AIServiceError.invalidResponse
    }
}

enum AIServiceError: LocalizedError {
    case unavailable, missingOpenCodeCredential, invalidResponse, server(String)
    var errorDescription: String? {
        switch self {
        case .unavailable: "El proveedor de IA no está disponible."
        case .missingOpenCodeCredential: "Conecta OpenCode Go desde OpenCode con /connect antes de usarlo aquí."
        case .invalidResponse: "El modelo respondió con un formato que no se puede aplicar con seguridad."
        case .server(let text): text
        }
    }
}

private enum OpenCodeCredential {
    static func apiKey() -> String? {
        let url = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".local/share/opencode/auth.json")
        guard let data = try? Data(contentsOf: url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let provider = root["opencode-go"] as? [String: Any],
              provider["type"] as? String == "api" else { return nil }
        return provider["key"] as? String
    }
}
