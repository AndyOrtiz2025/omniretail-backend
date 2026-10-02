# Inventory Traceability Foundation — Phase 1

Esta fase agrega exclusivamente la persistencia base para lotes, balances por lote,
seriales y el desglose físico histórico de movimientos de inventario.

Las tablas comienzan vacías. Ningún flujo operativo actual escribe o consulta estos
registros: ajustes, recepción, POS, devoluciones, picking, despacho, transferencias,
reservas, stock, movimientos y sus KPI conservan su comportamiento anterior.

El tracking operacional, las validaciones según configuración del producto, la
asignación y transición de lotes/seriales y su integración con movimientos pertenecen
a fases posteriores. La existencia de estas entidades no significa que el soporte de
lotes o seriales esté completo.
