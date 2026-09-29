package com.omniretail.backend.pos.service;

import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.catalog.entity.*;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.inventory.dto.AddStockCommand;
import com.omniretail.backend.inventory.service.InventoryStockService;
import com.omniretail.backend.pos.dto.*;
import com.omniretail.backend.pos.entity.*;
import com.omniretail.backend.pos.repository.*;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.*;
import java.math.*; import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*; import org.springframework.http.HttpStatus; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;

@Service @Transactional @RequiredArgsConstructor
public class SaleReturnService {
 private final CurrentUser currentUser; private final TenantCapabilityGuard capability; private final BranchAccessResolver branches;
 private final SaleRepository sales; private final SaleItemRepository saleItems; private final SaleReturnRepository returns; private final SaleReturnItemRepository returnItems;
 private final ProductRepository products; private final InventoryStockService inventory; private final CashShiftRepository shifts; private final CashMovementRepository movements; private final PaymentRepository payments;
 public SaleReturnResponse create(UUID saleId,CreateSaleReturnRequest request){
  var actor=currentUser.require(); capability.ensureTenantCapability(actor.tenantId(),SaasCapability.pos);
  Sale sale=sales.findByTenantIdAndIdForUpdate(actor.tenantId(),saleId).orElseThrow(()->nf("SALE_NOT_FOUND","Venta no encontrada."));
  if(!branches.resolve(actor).allows(sale.getBranchId())) throw nf("SALE_NOT_FOUND","Venta no encontrada.");
  if(sale.getStatus()!=SaleStatus.completed && sale.getStatus()!=SaleStatus.partially_returned) throw new BusinessException(HttpStatus.CONFLICT,"SALE_NOT_RETURNABLE","La venta no admite devoluciones.");
  Map<UUID,SaleItem> available=saleItems.findByTenantIdAndSaleId(actor.tenantId(),saleId).stream().collect(java.util.stream.Collectors.toMap(SaleItem::getId,x->x));
  Set<UUID> seen=new HashSet<>(); List<SaleReturnItem> created=new ArrayList<>(); Map<UUID, BigDecimal> returnedQuantities=new HashMap<>(); BigDecimal total=BigDecimal.ZERO;
  SaleReturn ret=SaleReturn.builder().branchId(sale.getBranchId()).saleId(saleId).reason(request.reason()).createdByUserId(actor.userId()).refundAmount(BigDecimal.ZERO).build(); ret.setTenantId(actor.tenantId()); ret=returns.save(ret);
  for(var line:request.lines()){
   if(!seen.add(line.saleItemId())) throw new BusinessException(HttpStatus.BAD_REQUEST,"DUPLICATE_RETURN_ITEM","La línea está repetida.");
   SaleItem item=available.get(line.saleItemId()); if(item==null) throw nf("SALE_ITEM_NOT_FOUND","Línea no encontrada.");
   BigDecimal previous=Optional.ofNullable(returnItems.sumReturned(actor.tenantId(),item.getId())).orElse(BigDecimal.ZERO); BigDecimal remaining=item.getQuantity().subtract(previous);
   if(line.quantity().compareTo(remaining)>0) throw new BusinessException(HttpStatus.CONFLICT,"RETURN_QUANTITY_EXCEEDED","La cantidad supera la disponible para devolución.");
   BigDecimal unitNet=item.getUnitPrice().subtract(item.getDiscount().divide(item.getQuantity(),8,RoundingMode.HALF_UP));
   BigDecimal refund=unitNet.multiply(line.quantity()).setScale(2,RoundingMode.HALF_UP); total=total.add(refund);
   returnedQuantities.put(item.getId(), previous.add(line.quantity()));
   SaleReturnItem ri=SaleReturnItem.builder().returnId(ret.getId()).saleItemId(item.getId()).productId(item.getProductId()).quantity(line.quantity()).refundAmount(refund).build(); ri.setTenantId(actor.tenantId()); created.add(returnItems.save(ri));
   Product product=products.findByTenantIdAndId(actor.tenantId(),item.getProductId()).orElse(null);
   if(product!=null && Boolean.TRUE.equals(product.getTrackingStock()) && product.getProductType()==ProductType.physical) inventory.incrementStock(new AddStockCommand(actor.tenantId(),sale.getBranchId(),product.getId(),line.quantity(),"Devolución venta POS #"+sale.getNumber(),"POS_SALE_RETURN",ret.getId(),actor.userId()));
  }
  ret.setRefundAmount(total.setScale(2,RoundingMode.HALF_UP)); returns.save(ret);
  var shift=shifts.findByTenantIdAndId(actor.tenantId(),sale.getCashShiftId()).filter(s->s.getStatus()==CashShiftStatus.open);
  if(shift.isPresent()){ var cash=payments.findByTenantIdAndSaleIdOrderByCreatedAtAscIdAsc(actor.tenantId(),saleId).stream().filter(p->p.getMethod()==PaymentMethod.cash).map(Payment::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add); if(cash.signum()>0){BigDecimal cashRefund=total.multiply(cash).divide(sale.getTotal(),2,RoundingMode.HALF_UP); if(cashRefund.signum()>0) movements.save(CashMovement.builder().tenantId(actor.tenantId()).cashShiftId(shift.get().getId()).type(CashMovementType.out).amount(cashRefund).reason("Devolución venta POS #"+sale.getNumber()).referenceType("sale_return").referenceId(ret.getId()).createdByUserId(actor.userId()).build());}}
  boolean all=available.values().stream().allMatch(i -> i.getQuantity().compareTo(returnedQuantities.containsKey(i.getId()) ? returnedQuantities.get(i.getId()) : Optional.ofNullable(returnItems.sumReturned(actor.tenantId(), i.getId())).orElse(BigDecimal.ZERO)) <= 0); sale.setStatus(all?SaleStatus.returned:SaleStatus.partially_returned); sales.save(sale);
  return SaleReturnResponse.from(ret,created);
 }
 @Transactional(readOnly=true) public Page<SaleReturnResponse> list(UUID branchId,Pageable pageable){var a=currentUser.require(); if(!branches.resolve(a).allows(branchId)) throw nf("BRANCH_NOT_FOUND","Sucursal no encontrada."); return returns.findByTenantIdAndBranchId(a.tenantId(),branchId,pageable).map(r->SaleReturnResponse.from(r,returnItems.findByTenantIdAndReturnId(a.tenantId(),r.getId())));}
 private static BusinessException nf(String c,String m){return new BusinessException(HttpStatus.NOT_FOUND,c,m);}
}
