package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.CreateSaasPlanRequest;
import com.omniretail.backend.administration.dto.SaasPlanResponse;
import com.omniretail.backend.administration.dto.UpdateSaasPlanRequest;
import com.omniretail.backend.administration.service.SaasPlanService;
import com.omniretail.backend.shared.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/plans")
@RequirePermission("administration.plans.manage")
@RequiredArgsConstructor
public class SaasPlanController {

    private final SaasPlanService planService;

    @GetMapping
    public List<SaasPlanResponse> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return planService.list(activeOnly);
    }

    @GetMapping("/{id}")
    public SaasPlanResponse get(@PathVariable UUID id) {
        return planService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SaasPlanResponse create(@Valid @RequestBody CreateSaasPlanRequest request) {
        return planService.create(request);
    }

    @PutMapping("/{id}")
    public SaasPlanResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSaasPlanRequest request) {
        return planService.update(id, request);
    }

    @PutMapping("/{id}/activate")
    public SaasPlanResponse activate(@PathVariable UUID id) {
        return planService.activate(id);
    }

    @PutMapping("/{id}/deactivate")
    public SaasPlanResponse deactivate(@PathVariable UUID id) {
        return planService.deactivate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        planService.delete(id);
    }
}
