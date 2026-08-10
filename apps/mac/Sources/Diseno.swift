import SwiftUI

/// El sistema visual de Yunkil, en un sitio.
///
/// Antes cada vista elegía su tamaño y su gris a mano: diez tamaños de letra distintos
/// —de 8 a 34— sin escala que los relacionara, casi todo apretado entre 9 y 11 puntos, y
/// una paleta que eran los grises del sistema más un naranja, un turquesa y un amarillo
/// puestos donde hacían falta. Funcionaba y no se parecía a nada.
///
/// La idea que ordena esto es la del producto: **en Yunkil todo número es una medida con
/// una procedencia**. El campo se mide, la paridad se mide, el encaje se mide contra el
/// mundo. Así que la interfaz se comporta como un instrumento y no como un editor: las
/// cifras van monoespaciadas y de ancho fijo, la unidad va apagada al lado, y cuando un
/// número no lo ha escrito nadie —lo deriva un encaje, lo tabula un perfil— se dice
/// debajo de dónde sale.
///
/// El color sale del propio visor. La rejilla del plato ya era turquesa sobre grafito, y
/// ese turquesa es lo único con carácter que había en pantalla; aquí sube a la interfaz
/// como **la señal de lo medido**, y no se usa para nada más. Un acento que significa
/// algo vale más que un acento bonito.
enum Tinta {

    /// Grafito del visor. La interfaz se apoya en el mismo negro que el plato.
    static let fondo = Color(red: 0.055, green: 0.067, blue: 0.075)

    /// Superficie de panel, un punto por encima del fondo.
    static let chapa = Color(red: 0.090, green: 0.106, blue: 0.118)

    /// Filete de separación. Un hilo, no una caja: las cajas trocean un panel estrecho.
    static let hilo = Color(red: 0.180, green: 0.208, blue: 0.227)

    /// Texto principal.
    static let nieve = Color(red: 0.910, green: 0.933, blue: 0.941)

    /// Texto secundario, frío como la rejilla para que no compita con el principal.
    static let cota = Color(red: 0.624, green: 0.702, blue: 0.722)

    /// Texto de apoyo: unidades, procedencias, pies de sección.
    static let apagado = Color(red: 0.435, green: 0.494, blue: 0.514)

    /// **Lo medido y lo verificado, y nada más.** Es el turquesa del plato.
    static let calibre = Color(red: 0.122, green: 0.659, blue: 0.608)

    /// Riesgo que depende de la calibración de la máquina.
    static let riesgo = Color(red: 0.910, green: 0.514, blue: 0.227)

    /// Lo que se ha medido y la máquina no puede materializar.
    static let fallo = Color(red: 0.898, green: 0.282, blue: 0.302)
}

/// La escala tipográfica. Cinco papeles y ni uno más.
///
/// El cuerpo sube a 12 y el pie a 10: en macOS el cuerpo del sistema son 13 puntos, y el
/// inspector —que es donde vive todo el valor del producto— estaba escrito a 9 y 10. Cabe
/// de sobra: el panel mide 280 puntos de ancho.
enum Tipo {
    static let titulo = Font.system(size: 15, weight: .semibold)
    static let cuerpo = Font.system(size: 12)
    static let menor = Font.system(size: 11)
    static let pie = Font.system(size: 10)

    /// Rótulo de sección. Versalitas espaciadas: rotula sin pesar.
    static let rotulo = Font.system(size: 10, weight: .bold)

    /// Toda cifra que sea una medida.
    ///
    /// Monoespaciada y de ancho fijo por un motivo que se ve al arrastrar un deslizador:
    /// con cifras proporcionales el número baila de ancho en cada fotograma y el ojo no
    /// puede leerlo mientras se mueve. Es la misma razón por la que un calibre digital no
    /// usa una tipografía de libro.
    static let cifra = Font.system(size: 11, design: .monospaced).monospacedDigit()

    /// La cifra que manda en una fila.
    static let cifraFuerte = Font.system(size: 12, weight: .medium, design: .monospaced)
        .monospacedDigit()
}

/// Cómo se escribe un número en Yunkil.
///
/// `String(format:)` escribe siempre con punto decimal, así que la aplicación enseñaba
/// «25.00 mm» en el inspector y «Bambu P1S · PLA · 0,4» dos secciones más abajo, en la
/// misma pantalla y en el mismo idioma. Y escribía «−0» cuando un giro valía cero por la
/// izquierda, que no significa nada y desconcierta en una casilla que se puede editar.
enum Cifra {

    /// Un número con la coma que le toca al idioma del sistema, y sin ceros negativos.
    static func texto(_ valor: Float, decimales: Int = 2) -> String {
        let limpio = valor == 0 ? 0 : valor
        return Double(limpio).formatted(
            .number.precision(.fractionLength(decimales)).grouping(.never)
        )
    }
}

/// La retícula de espaciado. Múltiplos de 4, para que nada quede a ojo.
enum Hueco {
    static let pelo: CGFloat = 2
    static let corto: CGFloat = 4
    static let medio: CGFloat = 8
    static let largo: CGFloat = 12
    static let seccion: CGFloat = 18
}

// MARK: - La cota

/// Una medida escrita como la escribiría un plano.
///
/// Es el elemento con el que se reconoce esta aplicación, y existe porque responde a algo
/// que ningún otro modelador contesta: **de dónde sale este número**. Un radio de 9,80 en
/// una caja de texto es un dato; el mismo 9,80 con «entra en boca del tubo: 20 mm con
/// calibre · holgura 0,20 deslizante» debajo es una decisión que se puede discutir, y
/// dentro de un mes sigue teniendo sentido.
///
/// El candado no es decoración: marca las cotas que manda un encaje, que son justamente
/// las que no se pueden arrastrar.
struct Cota: View {
    let etiqueta: String
    let valor: String
    var unidad: String = "mm"
    /// De dónde sale el número, cuando no lo ha escrito una persona.
    var procedencia: String? = nil
    var gobernada: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: Hueco.pelo) {
            HStack(alignment: .firstTextBaseline, spacing: Hueco.corto) {
                if gobernada {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 8))
                        .foregroundStyle(Tinta.calibre)
                }
                Text(etiqueta)
                    .font(Tipo.cuerpo)
                    .foregroundStyle(Tinta.cota)
                Spacer(minLength: Hueco.medio)
                Text(valor)
                    .font(Tipo.cifraFuerte)
                    .foregroundStyle(gobernada ? Tinta.calibre : Tinta.nieve)
                if !unidad.isEmpty {
                    Text(unidad)
                        .font(Tipo.pie)
                        .foregroundStyle(Tinta.apagado)
                        .fixedSize()
                        .frame(width: 22, alignment: .leading)
                }
            }
            if let procedencia {
                Text(procedencia)
                    .font(Tipo.pie)
                    .foregroundStyle(Tinta.apagado)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// Rótulo de sección con su filete. El filete separa; una caja trocearía el panel.
struct Rotulo: View {
    let texto: String
    /// Dato corto a la derecha del rótulo: el perfil vigente, el número de avisos.
    var apunte: String? = nil

    var body: some View {
        HStack(spacing: Hueco.medio) {
            Text(texto.uppercased())
                .font(Tipo.rotulo)
                .tracking(0.8)
                .foregroundStyle(Tinta.apagado)
                .fixedSize()
            Rectangle()
                .fill(Tinta.hilo)
                .frame(maxWidth: .infinity, minHeight: 1, maxHeight: 1)
            if let apunte {
                Text(apunte)
                    .font(Tipo.pie)
                    .foregroundStyle(Tinta.apagado)
                    .fixedSize()
            }
        }
    }
}

extension View {
    /// Texto de apoyo bajo un control: qué hace, o qué falta para poder usarlo.
    func pieDeAyuda() -> some View {
        self.font(Tipo.pie)
            .foregroundStyle(Tinta.apagado)
            .fixedSize(horizontal: false, vertical: true)
    }
}
