import Foundation
import YunkilCore

/// Mirar la pieza antes de dársela a nadie.
///
/// El resto del bucle mide: si dos sólidos se tocan, si una resta corta, si las cotas
/// cuadran. Nada de eso sabe si lo que salió **es** lo que se pidió. Un gancho que no
/// abraza la puerta no tiene ningún defecto medible, y es el fallo que más veces queda
/// en pie cuando todo lo demás está limpio.
///
/// Toda la política vive en el núcleo (`CriticoVisual`), que es donde se prueba sin red.
/// Aquí solo se dibuja, se manda y se recoge.
enum CriticaVisual {

    /// El modelo que mira. Es el único local con visión que cabe en memoria al lado del
    /// modelo de texto: 6,6 GiB del crítico más 6,3 del 9B son 12,9 de los 24 del techo.
    static let visorLocal = "qwen3-vl-8b"

    /// Corto a propósito: lo que se le pide es un veredicto de dos líneas. Un crítico
    /// que se desboca cuesta más que el modelo al que critica.
    private static let presupuesto = 600

    /// Lo que le falla a la pieza a la vista, o vacío si pasa.
    ///
    /// **Cualquier problema devuelve vacío**: sin geometría que dibujar, sin visor
    /// instalado, sin red o con una respuesta que no se entiende, la pieza pasa. El
    /// crítico visual es un revisor *de más*, y uno que se cae no puede tumbar un plan
    /// que los revisores exactos ya dieron por bueno.
    static func reparos(
        editor: Editor,
        plan: PlanDeModelado,
        peticion: String,
        perfil: String,
        referencia: ImagenDeReferencia?,
        seleccion: AISelection
    ) async -> [String] {
        guard let png = editor.vistasDelPlanComoDatos(
            plan: plan, lado: 320, nombrePerfil: perfil
        ) else { return [] }

        let visor = seleccion.supportsImage == true
            ? seleccion
            : AISelection(
                provider: .local, model: visorLocal, api: "chat", supportsImage: true
            )
        do {
            let respuesta = try await AITransport.complete(
                selection: visor,
                system: referencia == nil
                    ? CriticoVisual.shared.instrucciones(peticion: peticion)
                    : CriticoVisual.shared.instruccionesConReferencia(peticion: peticion),
                user: referencia == nil
                    ? "Mira las cuatro vistas y dime si la pieza es la que se pidió."
                    : "Compara la referencia original con las cuatro vistas del resultado.",
                maxTokens: presupuesto,
                imagenes: [referencia, .dePng(png)].compactMap { $0 }
            )
            let veredicto = CriticoVisual.shared.leer(respuesta: respuesta)
            return veredicto.cumple ? [] : veredicto.reparos
        } catch {
            return []
        }
    }
}
