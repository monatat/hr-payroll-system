package com.hrpayroll.backend.leave;

import com.hrpayroll.backend.audit.AuditService;
import com.hrpayroll.backend.employee.Employee;
import com.hrpayroll.backend.employee.EmployeeRepository;
import com.hrpayroll.backend.security.AuthenticatedUser;
import com.hrpayroll.backend.user.UserRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Leave request creation, read/list, and approval/rejection. Approval
 * deducts from the matching leave balance; rejection never touches a
 * balance. See {@link #approve} for the request-first optimistic-claim /
 * balance pessimistic-lock strategy.
 */
@Service
@Transactional(readOnly = true)
public class LeaveRequestService {

    private static final String ACTION_LEAVE_REQUEST_CREATED = "LEAVE_REQUEST_CREATED";
    private static final String ACTION_LEAVE_REQUEST_APPROVED = "LEAVE_REQUEST_APPROVED";
    private static final String ACTION_LEAVE_REQUEST_REJECTED = "LEAVE_REQUEST_REJECTED";
    private static final String ENTITY_LEAVE_REQUEST = "LEAVE_REQUEST";
    private static final List<LeaveRequestStatus> BLOCKING_STATUSES =
            List.of(LeaveRequestStatus.PENDING, LeaveRequestStatus.APPROVED);

    private final EmployeeRepository employeeRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final AuditService auditService;

    public LeaveRequestService(
            EmployeeRepository employeeRepository,
            LeaveTypeRepository leaveTypeRepository,
            LeaveRequestRepository leaveRequestRepository,
            LeaveBalanceRepository leaveBalanceRepository,
            AuditService auditService) {
        this.employeeRepository = employeeRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.auditService = auditService;
    }

    @Transactional
    public LeaveRequestResponse create(Long employeeId, CreateLeaveRequestRequest request, Long actorUserId) {
        // Employee row lock held for the whole method -- the overlap check
        // below runs inside the same lock/transaction, so concurrent
        // creations for this employee are serialized (Phase 7B-2 will add
        // the dedicated concurrency test; this phase only wires the lock).
        Employee employee = employeeRepository.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        LeaveType leaveType = leaveTypeRepository.findById(request.leaveTypeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave type not found"));

        LeaveDurationType durationType = request.durationType() != null ? request.durationType() : LeaveDurationType.FULL_DAY;

        LocalDate startDate = request.startDate();
        LocalDate endDate = request.endDate();

        if (endDate.isBefore(startDate)) {
            throw new InvalidLeaveDatesException("endDate cannot be before startDate");
        }
        if (durationType == LeaveDurationType.HALF_DAY && !startDate.equals(endDate)) {
            throw new InvalidLeaveDatesException("A HALF_DAY request must have the same startDate and endDate");
        }
        if (startDate.getYear() != endDate.getYear()) {
            throw new InvalidLeaveDatesException("startDate and endDate must fall within the same calendar year");
        }

        BigDecimal workingDays = WorkingDayCalculator.countInclusive(startDate, endDate);
        if (workingDays.signum() == 0) {
            throw new ZeroWorkingDaysRequestedException();
        }

        BigDecimal daysRequested = durationType == LeaveDurationType.HALF_DAY ? new BigDecimal("0.5") : workingDays;

        long overlapping = leaveRequestRepository.countOverlapping(employeeId, startDate, endDate, BLOCKING_STATUSES);
        if (overlapping > 0) {
            throw new LeaveRequestOverlapException();
        }

        LeaveRequest saved = leaveRequestRepository.save(
                new LeaveRequest(employee, leaveType, startDate, endDate, daysRequested, request.reason(), durationType));

        auditService.record(
                actorUserId,
                ACTION_LEAVE_REQUEST_CREATED,
                ENTITY_LEAVE_REQUEST,
                saved.getId(),
                Map.of(
                        "employeeId", employeeId,
                        "leaveTypeId", leaveType.getId(),
                        "startDate", startDate.toString(),
                        "endDate", endDate.toString(),
                        "daysRequested", daysRequested,
                        "durationType", durationType.name()));

        return LeaveRequestResponse.from(saved);
    }

    public PagedModel<LeaveRequestResponse> listForEmployee(Long employeeId, Pageable pageable) {
        Page<LeaveRequest> page = leaveRequestRepository.findByEmployeeId(employeeId, withIdDescTiebreaker(pageable));
        return new PagedModel<>(page.map(LeaveRequestResponse::from));
    }

    public PagedModel<LeaveRequestResponse> listForAdmin(LeaveRequestStatus status, Long employeeId, Pageable pageable) {
        Page<LeaveRequest> page = leaveRequestRepository.search(status, employeeId, withIdDescTiebreaker(pageable));
        return new PagedModel<>(page.map(LeaveRequestResponse::from));
    }

    public LeaveRequestResponse getById(Long id, AuthenticatedUser principal) {
        LeaveRequest leaveRequest = findRequestOrThrow(id);

        boolean isOwnRequest = leaveRequest.getEmployee().getId().equals(principal.employeeId());
        if (UserRole.EMPLOYEE.name().equals(principal.role()) && !isOwnRequest) {
            // 404, not 403 -- an employee must not learn that another
            // employee's leave request exists at all.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave request not found");
        }

        return LeaveRequestResponse.from(leaveRequest);
    }

    /**
     * Claims the request first (PENDING check, mutate, immediate
     * {@code saveAndFlush}) before ever touching a balance. The early
     * flush is deliberate: it forces the version-checked UPDATE to run
     * right here, so two concurrent approvals of the SAME request conflict
     * on version at this point -- the loser gets a clean
     * LeaveRequestAlreadyProcessedException instead of racing on into the
     * balance check and potentially reporting a misleading "insufficient
     * balance". Only after that claim succeeds does this method acquire
     * the balance row's PESSIMISTIC_WRITE lock, so two different requests
     * competing for the same balance are serialized there instead. If
     * anything from this point on throws, the whole transaction --
     * including the already-flushed APPROVED status and version bump --
     * rolls back at the transaction boundary; flush is not commit.
     */
    @Transactional
    public LeaveRequestResponse approve(Long requestId, Long actorUserId) {
        LeaveRequest leaveRequest = findRequestOrThrow(requestId);

        if (leaveRequest.getStatus() != LeaveRequestStatus.PENDING) {
            throw new LeaveRequestAlreadyProcessedException();
        }

        leaveRequest.approve(actorUserId, LocalDateTime.now());
        flushClaimOrThrowAlreadyProcessed(leaveRequest);

        Integer balanceYear = leaveRequest.getStartDate().getYear();
        LeaveBalance balance = leaveBalanceRepository
                .findForUpdate(leaveRequest.getEmployee().getId(), leaveRequest.getLeaveType().getId(), balanceYear)
                .orElseThrow(LeaveBalanceNotFoundException::new);

        BigDecimal remainingDays = balance.getEntitledDays().subtract(balance.getUsedDays());
        if (remainingDays.compareTo(leaveRequest.getDaysRequested()) < 0) {
            throw new InsufficientLeaveBalanceException();
        }
        balance.incrementUsedDays(leaveRequest.getDaysRequested());

        auditService.record(
                actorUserId,
                ACTION_LEAVE_REQUEST_APPROVED,
                ENTITY_LEAVE_REQUEST,
                leaveRequest.getId(),
                Map.of(
                        "leaveRequestId", leaveRequest.getId(),
                        "employeeId", leaveRequest.getEmployee().getId(),
                        "daysRequested", leaveRequest.getDaysRequested(),
                        "durationType", leaveRequest.getDurationType().name()));

        return LeaveRequestResponse.from(leaveRequest);
    }

    /** Never queries, locks, creates, or modifies a leave balance. */
    @Transactional
    public LeaveRequestResponse reject(Long requestId, String reason, Long actorUserId) {
        LeaveRequest leaveRequest = findRequestOrThrow(requestId);

        if (leaveRequest.getStatus() != LeaveRequestStatus.PENDING) {
            throw new LeaveRequestAlreadyProcessedException();
        }

        String normalizedReason = (reason == null || reason.isBlank()) ? null : reason;
        leaveRequest.reject(actorUserId, LocalDateTime.now(), normalizedReason);
        flushClaimOrThrowAlreadyProcessed(leaveRequest);

        auditService.record(
                actorUserId,
                ACTION_LEAVE_REQUEST_REJECTED,
                ENTITY_LEAVE_REQUEST,
                leaveRequest.getId(),
                Map.of(
                        "leaveRequestId", leaveRequest.getId(),
                        "employeeId", leaveRequest.getEmployee().getId(),
                        "hasReason", normalizedReason != null,
                        "durationType", leaveRequest.getDurationType().name()));

        return LeaveRequestResponse.from(leaveRequest);
    }

    private void flushClaimOrThrowAlreadyProcessed(LeaveRequest leaveRequest) {
        try {
            leaveRequestRepository.saveAndFlush(leaveRequest);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new LeaveRequestAlreadyProcessedException();
        }
    }

    private LeaveRequest findRequestOrThrow(Long id) {
        return leaveRequestRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave request not found"));
    }

    private static Pageable withIdDescTiebreaker(Pageable pageable) {
        Sort sortWithTiebreaker = pageable.getSort().and(Sort.by("id").descending());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortWithTiebreaker);
    }
}
