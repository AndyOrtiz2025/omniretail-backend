package com.omniretail.backend.logistics.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.ecommerce.entity.*;
import com.omniretail.backend.ecommerce.repository.InventoryReservationRepository;
import com.omniretail.backend.ecommerce.repository.OrderRepository;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.inventory.service.InventoryReservationLifecycleService;
import com.omniretail.backend.logistics.dto.*;
import com.omniretail.backend.logistics.entity.*;
import com.omniretail.backend.logistics.repository.*;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

/** Confirmacion transaccional del despacho de pedidos ecommerce a domicilio. */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class DispatchService {
 private final DispatchRepository dispatches; private final DispatchPackageRepository packages;
 private final DispatchOperationRepository operations; private final PackingRepository packings;
 private final OrderRepository orders; private final InventoryReservationRepository reservations;
 private final InventoryReservationLifecycleService reservationLifecycle;
 private final InventoryMovementRepository movements; private final ProductRepository products;
 private final InventoryBalanceRepository balances;
 private final BranchAccessResolver branchAccessResolver; private final CurrentUser currentUser; private final JsonMapper jsonMapper;

 public List<DispatchQueueResponse> getQueue(UUID branchId) {
  AuthenticatedUser actor=actorForBranch(branchId);
  return orders.findByTenantIdAndStatusIn(actor.tenantId(), List.of(OrderStatus.ready_for_dispatch)).stream()
   .filter(o->branchId.equals(o.getBranchId()) && o.getDeliveryMethod()==DeliveryMethod.home_delivery)
   .map(o->packings.findByTenantIdAndBranchIdAndSourceTypeAndSourceId(actor.tenantId(),branchId,PackingSourceType.order,o.getId())
    .filter(p->p.getStatus()==PackingStatus.finalized).map(p->new DispatchQueueResponse(o.getId(),o.getOrderNumber(),o.getCreatedAt(),o.getTransportMode(),p.getId(),p.getFinalizedAt())).orElse(null))
   .filter(Objects::nonNull).toList();
 }

 public DispatchResponse getDetail(UUID branchId, UUID orderId) {
  AuthenticatedUser actor=actorForBranch(branchId);
  Dispatch dispatch=dispatches.findByTenantIdAndOrderId(actor.tenantId(),orderId).orElseThrow(()->notFound("DISPATCH_NOT_FOUND","Despacho no encontrado."));
  if(!branchId.equals(dispatch.getBranchId())) throw notFound("DISPATCH_NOT_FOUND","Despacho no encontrado.");
  return response(dispatch,false);
 }

 @Transactional
 public DispatchResponse confirm(UUID branchId, UUID orderId, ConfirmDispatchRequest request) {
  AuthenticatedUser actor=actorForBranch(branchId);
  Order order=orders.findByTenantIdAndIdForUpdate(actor.tenantId(),orderId).orElseThrow(()->notFound("ORDER_NOT_FOUND","Pedido no encontrado."));
  if(!branchId.equals(order.getBranchId())) throw notFound("ORDER_NOT_FOUND","Pedido no encontrado.");
  Packing packing=packings.findByTenantIdAndBranchIdAndSourceTypeAndSourceId(actor.tenantId(),branchId,PackingSourceType.order,orderId)
   .orElseThrow(()->conflict("PACKING_NOT_FOUND","El pedido no tiene Packing."));
  List<ConfirmDispatchRequest.PackageRequest> requestedPackages=resolvedPackages(packing, request.packages());
  String operationId=request.operationId().trim(); String fingerprint=fingerprint(order,packing,actor.userId(),request, requestedPackages);
  Optional<DispatchOperation> previous=operations.findByTenantIdAndOperationId(actor.tenantId(),operationId);
  if(previous.isPresent()) return replay(previous.get(),fingerprint);
  if(order.getDeliveryMethod()!=DeliveryMethod.home_delivery) throw conflict("UNSUPPORTED_FULFILLMENT","El despacho solo admite entrega a domicilio.");
  if(order.getStatus()!=OrderStatus.ready_for_dispatch) throw conflict("INVALID_ORDER_STATUS_TRANSITION","El pedido no esta listo para despacho.");
  if(packing.getStatus()!=PackingStatus.finalized || packing.getFinalizedAt()==null) throw conflict("PACKING_NOT_FINALIZED","El Packing debe estar finalizado.");
  validateShipment(order,request); validatePackages(packing,requestedPackages);
  if(dispatches.findBySourceForUpdate(actor.tenantId(),branchId,DispatchSourceType.order,orderId).isPresent()) throw conflict("DISPATCH_ALREADY_EXISTS","El pedido ya tiene un despacho.");
  List<InventoryReservation> active=reservations.findByTenantIdAndSourceTypeAndSourceIdAndStatus(actor.tenantId(),InventoryReservationSourceType.order,orderId,InventoryReservationStatus.active);
  if(active.isEmpty()) throw conflict("INVENTORY_RESERVATION_NOT_ACTIVE","El pedido no tiene reservas activas.");
  for(InventoryReservation reservation: active) {
   Product product=products.findByTenantIdAndId(actor.tenantId(),reservation.getProductId()).orElseThrow(()->notFound("PRODUCT_NOT_FOUND","Producto no encontrado."));
   requireTraceabilitySupported(product);
  }
  Instant now=Instant.now();
  Dispatch newDispatch=Dispatch.builder().branchId(branchId).sourceType(DispatchSourceType.order).sourceId(orderId).orderId(orderId).packingId(packing.getId()).transportMode(order.getTransportMode()).carrierName(trimToNull(request.carrierName())).trackingNumber(trimToNull(request.trackingNumber())).dispatchedByUserId(actor.userId()).dispatchedAt(now).build();
  newDispatch.setTenantId(actor.tenantId());
  Dispatch dispatch=dispatches.saveAndFlush(newDispatch);
  for(InventoryReservation reservation: active) {
   reservationLifecycle.consume(actor.tenantId(),reservation.getId());
   for (Allocation allocation : allocations(reservation)) {
    var balance=balances.findByTenantIdAndId(actor.tenantId(), allocation.balanceId())
      .orElseThrow(()->conflict("INVENTORY_RESERVATION_INCONSISTENT","La reserva no coincide con el balance de inventario."));
    movements.save(InventoryMovement.builder().tenantId(actor.tenantId()).branchId(branchId).productId(reservation.getProductId()).type(InventoryMovementType.out).reason("Despacho ecommerce confirmado").quantity(allocation.quantity()).quantityBefore(balance.getQuantity().add(allocation.quantity())).quantityAfter(balance.getQuantity()).fromLocationId(balance.getLocationId()).toLocationId(null).referenceType("dispatch").referenceId(dispatch.getId()).performedByUserId(actor.userId()).build());
   }
  }
  List<DispatchPackage> saved=packages.saveAll(requestedPackages.stream().map(p->DispatchPackage.builder().dispatchId(dispatch.getId()).number(p.number().trim()).weight(p.weight()).description(trimToNull(p.description())).build()).toList());
  order.setStatus(OrderStatus.dispatched); orders.save(order);
  DispatchResponse result=response(dispatch,saved,false);
  operations.saveAndFlush(DispatchOperation.builder().tenantId(actor.tenantId()).branchId(branchId).dispatchId(dispatch.getId()).operationId(operationId).fingerprint(fingerprint).resultDispatch(jsonMapper.writeValueAsString(result)).build());
  return result;
 }
 private DispatchResponse replay(DispatchOperation operation,String fingerprint) { if(!fingerprint.equals(operation.getFingerprint())) throw conflict("DISPATCH_OPERATION_ID_REUSED","El operationId ya fue utilizado con una confirmacion diferente."); DispatchResponse r=jsonMapper.readValue(operation.getResultDispatch(),DispatchResponse.class); return new DispatchResponse(r.orderId(),r.orderStatus(),r.dispatchId(),r.dispatchStatus(),r.transportMode(),r.carrierName(),r.trackingNumber(),r.dispatchedAt(),r.packages(),true); }
 private DispatchResponse response(Dispatch dispatch,boolean idempotent) { return response(dispatch,packages.findByDispatchIdOrderByNumberAsc(dispatch.getId()),idempotent); }
 private DispatchResponse response(Dispatch d,List<DispatchPackage> p,boolean idempotent) { return new DispatchResponse(d.getOrderId(),OrderStatus.dispatched,d.getId(),d.getStatus(),d.getTransportMode(),d.getCarrierName(),d.getTrackingNumber(),d.getDispatchedAt(),p.stream().map(x->new DispatchPackageResponse(x.getId(),x.getNumber(),x.getWeight(),x.getDescription())).toList(),idempotent); }
 private void validateShipment(Order order,ConfirmDispatchRequest r) { if(order.getTransportMode()==TransportMode.third_party && (trimToNull(r.carrierName())==null || trimToNull(r.trackingNumber())==null)) throw BusinessException.badRequest("Transportista y numero de guia son obligatorios para terceros."); }
 private List<ConfirmDispatchRequest.PackageRequest> resolvedPackages(Packing packing,List<ConfirmDispatchRequest.PackageRequest> requested) {
  if(requested!=null&&!requested.isEmpty()) return requested;
  if(packing.getPackageCount()==null||packing.getLabelCode()==null) throw conflict("PACKING_PACKAGE_DATA_MISSING","Packing no contiene paquetes etiquetados.");
  List<ConfirmDispatchRequest.PackageRequest> generated=new ArrayList<>();
  for(int i=1;i<=packing.getPackageCount();i++) generated.add(new ConfirmDispatchRequest.PackageRequest(packing.getLabelCode()+"-"+i,null,"Bulto "+i+" de "+packing.getPackageCount()));
  return generated;
 }
 private void validatePackages(Packing p,List<ConfirmDispatchRequest.PackageRequest> ps) { if(p.getPackageCount()==null || p.getPackageCount()!=ps.size()) throw BusinessException.badRequest("Los paquetes deben coincidir con el packageCount del Packing."); Set<String> seen=new HashSet<>(); for(var x:ps) if(!seen.add(x.number().trim().toLowerCase(Locale.ROOT))) throw BusinessException.badRequest("El numero de paquete no puede repetirse."); }
 private void requireTraceabilitySupported(Product p) { if(Boolean.TRUE.equals(p.getTrackingLot())||Boolean.TRUE.equals(p.getTrackingSerial())||Boolean.TRUE.equals(p.getTrackingExpiration())) throw conflict("TRACEABILITY_NOT_SUPPORTED","El producto requiere trazabilidad aun no soportada."); }
 private AuthenticatedUser actorForBranch(UUID branchId) { AuthenticatedUser a=currentUser.require(); if(branchId==null||!branchAccessResolver.resolve(a).allows(branchId)) throw new BusinessException(HttpStatus.FORBIDDEN,"BRANCH_ACCESS_DENIED","No tienes acceso a la sucursal indicada."); return a; }
 private static String trimToNull(String x){return x==null||x.trim().isEmpty()?null:x.trim();}
 private List<Allocation> allocations(InventoryReservation reservation) {
  JsonNode root=jsonMapper.readTree(reservation.getAllocations());
  if (!root.isArray() || root.isEmpty()) {
   UUID balanceId=balances.findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
       reservation.getTenantId(), reservation.getBranchId(), reservation.getProductId())
       .orElseThrow(()->conflict("INVENTORY_RESERVATION_INCONSISTENT","La reserva no coincide con el balance de inventario."))
       .getId();
   return List.of(new Allocation(balanceId,reservation.getQuantity()));
  }
  List<Allocation> result=new ArrayList<>();
  for(JsonNode node:root) result.add(new Allocation(UUID.fromString(node.get("balanceId").asText()), new BigDecimal(node.get("reservedQuantity").asText())));
  return result;
 }
 private static String fingerprint(Order o,Packing p,UUID actor,ConfirmDispatchRequest r,List<ConfirmDispatchRequest.PackageRequest> packages){ String x=o.getId()+"|"+p.getId()+"|"+actor+"|"+o.getTransportMode()+"|"+trimToNull(r.carrierName())+"|"+trimToNull(r.trackingNumber())+"|"+packages.stream().map(z->z.number().trim()+":"+z.weight()+":"+trimToNull(z.description())).sorted().reduce("",(a,b)->a+"|"+b); try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(x.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
 private static BusinessException conflict(String c,String m){return BusinessException.conflict(c,m);} private static BusinessException notFound(String c,String m){return new BusinessException(HttpStatus.NOT_FOUND,c,m);}
 private record Allocation(UUID balanceId, BigDecimal quantity) {}
}
