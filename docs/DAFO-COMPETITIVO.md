# Yunkil: DAFO competitivo

Estado y evidencia revisados el 3 de agosto de 2026.

## Referencia de mercado

El benchmark principal de experiencia es **Shapr3D**: modelado preciso en Mac,
iPad y Windows, edición directa, historial, bocetos y exportación orientada a
fabricación. Fusion es el techo de profundidad CAD/manufactura y Zoo Design
Studio es el competidor más cercano en CAD conversacional.

Yunkil no debe intentar ser un Fusion pequeño. Su posición defendible es:

> Crear por conversación una pieza funcional y editable, verificarla contra la
> impresora real y negarse a entregar un archivo que no pueda respaldar.

Fuentes oficiales:

- [Shapr3D: modelado](https://www.shapr3d.com/product/3d-modeling)
- [Shapr3D: historial](https://support.shapr3d.com/hc/en-us/articles/11567903089180-History)
- [Shapr3D: formatos](https://support.shapr3d.com/hc/en-us/articles/7874523890076-Supported-file-types)
- [Autodesk Fusion: capacidades](https://www.autodesk.com/products/fusion-360/features)
- [Autodesk Assistant](https://www.autodesk.com/products/fusion-360/blog/autodesk-assistant-enters-a-new-era/)
- [Zoo: text-to-CAD](https://zoo.dev/text-to-cad)
- [Zoo: limites declarados](https://zoo.dev/docs/faq)
- [nTop: geometria implicita](https://www.ntop.com/platform/)

## Comparativa honesta

| Capacidad | Yunkil | Shapr3D/Fusion | Consecuencia |
|---|---|---|---|
| Solidos parametricos | SDF y booleanas robustas | B-rep profesional | Ventaja de simplicidad para IA, brecha en operaciones CAD |
| Creacion conversacional | Prototipo local/OpenCode Go | Fusion ya ejecuta acciones naturales | IA sola no es un foso |
| Bocetos 2D | No | Maduro | Bloqueo para muchas piezas funcionales |
| Edicion visual | Arbol y medidas; sin gizmos | Seleccion y manipulacion directa | Brecha de usabilidad P0 |
| Fabricacion | STL examinado | STL/3MF y ecosistemas maduros | Yunkil debe ganar por evidencia, no por formatos |
| Analisis FDM | No | Herramientas parciales/integradas | Mayor oportunidad diferencial |
| Privacidad | Modelo local disponible | Dependencia variable de nube | Ventaja real si se empaqueta y verifica |
| iPad/Windows | Nucleo iOS, sin app | Productos terminados | No prometer plataforma antes del gate |

## DAFO

### Fortalezas

- El mismo campo SDF gobierna modelo, render, medidas y futuro analisis.
- Booleanas robustas y documento editable con historial.
- La IA solo emite operaciones declarativas validadas; no ejecuta codigo.
- IA local privada y OpenCode Go seleccionables.
- Paridad CPU/Metal documentada sobre 19.000 puntos.
- Exportacion atomica con topologia, orientacion, degenerados, desviacion y volumen.

### Debilidades

- Sin perfiles 2D, extrusion, revolucion, loft ni sweep.
- Sin seleccion de superficie ni gizmos en el viewport.
- El analizador FDM y las correcciones ejecutables aun no existen.
- Solo STL; faltan 3MF, orientacion, perfiles y handoff a slicer.
- La desviacion es muestreada y no se comprueban auto-intersecciones.
- La IA ve estructura y nombres, pero no toda la semantica dimensional.
- No hay corpus de impresiones externas ni evidencia de pago.

### Oportunidades

- Shapr3D no documenta aun un text-to-CAD mecanico general equivalente.
- Zoo reconoce que no garantiza fabricabilidad.
- Perfiles calibrados por impresora pueden reducir iteraciones fisicas.
- Flujo `describir -> analizar -> corregir -> 3MF` mucho mas corto que un CAD general.
- Integracion con Orca, Bambu y Prusa sin construir un slicer propio.

### Amenazas

- Fusion y Zoo ya combinan lenguaje natural e historial parametricos.
- Shapr3D puede añadir IA sobre una UX y distribucion maduras.
- Un solo archivo declarado apto que falle puede destruir la confianza.
- Meshy y MakerLab fijan una expectativa de resultado casi inmediato.
- Expandirse a plataformas o impresoras antes de validar el nucleo dispersaria el producto.

## Prioridades derivadas

### P0: demostrar la promesa

1. Certificado: auto-intersecciones, presupuesto aplicado a reintentos y corpus adversarial.
2. Perfiles 2D con cotas, extrusion y revolucion.
3. Seleccion desde viewport y gizmos basicos.
4. Analizador FDM minimo: grosor, detalle, voladizo y base.
5. IA contextual con preguntas cuando falten medidas y una transaccion por propuesta.
6. Impresiones externas en varias maquinas y materiales.

### P1: herramienta competitiva

- 3MF, orientacion automatica y perfiles versionados.
- Cupon imprimible de calibracion.
- Bucle maximo de tres rondas crear-analizar-corregir.
- Abrir directamente en OrcaSlicer, Bambu Studio y PrusaSlicer.
- App iPad solo despues de superar los gates de uso.

### P2: expansion con evidencia

- Envio LAN a impresora, MCP, colaboracion e importacion STEP.
- Windows, ensamblajes y marketplace solo despues de repeticion y pago.

## Gates de 12 meses

| Periodo | Gate obligatorio |
|---|---|
| Mes 1-2 | 100 modelos adversariales; todo archivo entregado abre sin reparacion en tres slicers; cero OOM |
| Mes 3-4 | 16 de 20 tareas funcionales resueltas con perfiles 2D y cotas persistentes |
| Mes 5-6 | 30 impresiones, 3 impresoras, 2 materiales; >=90% de defectos criticos detectados |
| Mes 7-8 | 50 prompts congelados; >=95% plan valido o pregunta; >=80% correctos en tres rondas |
| Mes 9-10 | 30 archivos 3MF conservan unidades y orientacion en tres slicers |
| Mes 11-12 | 20 usuarios externos, >=10 repiten tres piezas y cero exportaciones reparadas manualmente |

No se abre una fase por calendario: solo cuando pasa el gate anterior.
