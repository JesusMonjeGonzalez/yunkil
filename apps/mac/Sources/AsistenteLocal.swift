import Foundation

/// Transporte hacia el modelo, y nada más.
///
/// El esquema de operaciones, los sinónimos, la validación y la aplicación viven
/// en el núcleo Kotlin. Aquí solo se manda texto y se recoge texto. Esa frontera
/// es deliberada: la lógica que decide qué puede tocar un modelo tiene pruebas
/// automáticas y se comparte con iPad, mientras que duplicarla en Swift
/// garantizaría que las dos versiones divergen a la primera de cambio.
enum AsistenteLocal {

    static func pedirPlan(
        system: String,
        user: String,
        selection: AISelection,
        maxTokens: Int,
        imagen: ImagenDeReferencia? = nil
    ) async throws -> String {
        try await AITransport.complete(
            selection: selection,
            system: system,
            user: user,
            maxTokens: maxTokens,
            imagen: imagen
        )
    }
}

enum LocalAssistantError: LocalizedError {
    case invalidResponse
    case invalidPlan(String)
    case server(String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse: "El modelo no devolvió un plan válido."
        case .invalidPlan(let reason): "El modelo devolvió un plan no aplicable: \(reason)"
        case .server(let message): message
        }
    }
}
