package com.omniretail.backend.pos.controller;
import com.omniretail.backend.pos.dto.*; import com.omniretail.backend.pos.service.SaleReturnService; import com.omniretail.backend.shared.security.RequirePermission; import jakarta.validation.Valid; import java.util.UUID; import lombok.RequiredArgsConstructor; import org.springframework.data.domain.*; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/pos") @RequiredArgsConstructor
public class SaleReturnController { private final SaleReturnService service;
 @PostMapping("/sales/{saleId}/returns") @RequirePermission("pos.returns.create") public SaleReturnResponse create(@PathVariable UUID saleId,@Valid @RequestBody CreateSaleReturnRequest request){return service.create(saleId,request);}
 @GetMapping("/returns") @RequirePermission("pos.returns.read") public Page<SaleReturnResponse> list(@RequestParam UUID branchId,Pageable pageable){return service.list(branchId,pageable);}
}
