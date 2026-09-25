package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.BranchResponse;
import com.omniretail.backend.administration.dto.CreateBranchRequest;
import com.omniretail.backend.administration.dto.UpdateBranchRequest;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class BranchService {

    private final BranchRepository branchRepository;
    private final CurrentUser currentUser;

    public PageResponse<BranchResponse> listBranches(BranchStatus status, Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        Page<Branch> page = status != null
                ? branchRepository.findByTenantIdAndStatus(tenantId, status, pageable)
                : branchRepository.findByTenantId(tenantId, pageable);
        return PageResponse.from(page, BranchResponse::from);
    }

    public List<BranchResponse> listActiveBranches() {
        UUID tenantId = currentUser.require().tenantId();
        return branchRepository.findByTenantIdAndStatus(tenantId, BranchStatus.active).stream()
                .map(BranchResponse::from)
                .toList();
    }

    public BranchResponse getBranchById(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Branch branch = branchRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
        return BranchResponse.from(branch);
    }

    public BranchResponse createBranch(CreateBranchRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        String code = request.code().trim().toUpperCase();
        if (branchRepository.existsByTenantIdAndCodeIgnoreCase(tenantId, code)) {
            throw new BusinessException(
                    HttpStatus.CONFLICT, "BRANCH_CODE_EXISTS", "Ya existe una sucursal con el codigo " + code);
        }
        Branch branch = Branch.builder()
                .code(code)
                .name(request.name().trim())
                .type(request.type())
                .address(request.address() != null ? request.address().trim() : null)
                .phone(request.phone() != null ? request.phone().trim() : null)
                .email(request.email() != null ? request.email().trim().toLowerCase() : null)
                .status(request.status() != null ? request.status() : BranchStatus.active)
                .build();
        branch.setTenantId(tenantId);
        Branch saved = branchRepository.save(branch);
        return BranchResponse.from(saved);
    }

    public BranchResponse updateBranch(UUID id, UpdateBranchRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        Branch branch = branchRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));

        if (request.code() != null && !request.code().isBlank()) {
            String newCode = request.code().trim().toUpperCase();
            if (!newCode.equals(branch.getCode())
                    && branchRepository.existsByTenantIdAndCodeIgnoreCaseAndIdNot(tenantId, newCode, id)) {
                throw new BusinessException(
                        HttpStatus.CONFLICT, "BRANCH_CODE_EXISTS", "Ya existe una sucursal con el codigo " + newCode);
            }
            branch.setCode(newCode);
        }

        branch.setName(request.name().trim());
        branch.setType(request.type());
        branch.setAddress(request.address() != null ? request.address().trim() : null);
        branch.setPhone(request.phone() != null ? request.phone().trim() : null);
        branch.setEmail(request.email() != null ? request.email().trim().toLowerCase() : null);
        if (request.status() != null) {
            branch.setStatus(request.status());
        }

        Branch saved = branchRepository.save(branch);
        return BranchResponse.from(saved);
    }

    public void archiveBranch(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Branch branch = branchRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Sucursal no encontrada."));
        branch.setStatus(BranchStatus.archived);
        branchRepository.save(branch);
    }
}
