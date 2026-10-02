package com.omniretail.backend.logistics.controller;
import com.omniretail.backend.logistics.dto.*; import com.omniretail.backend.logistics.service.DispatchService; import com.omniretail.backend.shared.security.RequirePermission; import jakarta.validation.Valid; import java.util.*; import lombok.RequiredArgsConstructor; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/logistics/dispatch") @RequiredArgsConstructor
public class DispatchController {
 private final DispatchService dispatchService;
 @GetMapping @RequirePermission("logistics.dispatch.read") public List<DispatchQueueResponse> queue(@RequestParam UUID branchId) { return dispatchService.getQueue(branchId); }
 @GetMapping("/{orderId}") @RequirePermission("logistics.dispatch.read") public DispatchResponse detail(@RequestParam UUID branchId, @PathVariable UUID orderId) { return dispatchService.getDetail(branchId, orderId); }
 @PostMapping("/{orderId}/confirm") @RequirePermission("logistics.dispatch.confirm") public DispatchResponse confirm(@RequestParam UUID branchId, @PathVariable UUID orderId, @Valid @RequestBody ConfirmDispatchRequest request) { return dispatchService.confirm(branchId, orderId, request); }
}
