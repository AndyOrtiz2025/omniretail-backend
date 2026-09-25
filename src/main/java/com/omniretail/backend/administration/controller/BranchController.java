package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.BranchResponse;
import com.omniretail.backend.administration.dto.CreateBranchRequest;
import com.omniretail.backend.administration.dto.UpdateBranchRequest;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.service.BranchService;
import com.omniretail.backend.shared.dto.PageResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
@RequestMapping("/administration/branches")
@RequiredArgsConstructor
public class BranchController {

    private final BranchService branchService;

    @GetMapping
    public PageResponse<BranchResponse> list(
            @RequestParam(required = false) BranchStatus status, @PageableDefault(size = 20) Pageable pageable) {
        return branchService.listBranches(status, pageable);
    }

    @GetMapping("/active")
    public List<BranchResponse> listActive() {
        return branchService.listActiveBranches();
    }

    @GetMapping("/{id}")
    public BranchResponse getById(@PathVariable UUID id) {
        return branchService.getBranchById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BranchResponse create(@Valid @RequestBody CreateBranchRequest request) {
        return branchService.createBranch(request);
    }

    @PutMapping("/{id}")
    public BranchResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateBranchRequest request) {
        return branchService.updateBranch(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        branchService.archiveBranch(id);
    }
}
