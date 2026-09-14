package com.hrpayroll.backend.payroll;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only payroll queries, deliberately kept separate from
 * {@link PayrollRunTransactionService}'s write/transactional-collaborator role. Never mutates
 * anything.
 */
@Service
@Transactional(readOnly = true)
public class PayrollQueryService {

    private final PayrollRunRepository payrollRunRepository;
    private final PayslipRepository payslipRepository;

    public PayrollQueryService(PayrollRunRepository payrollRunRepository, PayslipRepository payslipRepository) {
        this.payrollRunRepository = payrollRunRepository;
        this.payslipRepository = payslipRepository;
    }

    public PagedModel<PayrollRunResponse> listRuns(PayrollRunStatus status, Integer year, Pageable pageable) {
        if (year != null && (year < 2000 || year > 2100)) {
            throw new InvalidPayrollPeriodException("year must be between 2000 and 2100: was " + year);
        }
        return new PagedModel<>(
                payrollRunRepository.search(status, year, withIdDescTiebreaker(pageable)).map(PayrollRunResponse::from));
    }

    public PayrollRunResponse getRun(Long id) {
        PayrollRun run = payrollRunRepository.findById(id).orElseThrow(PayrollRunNotFoundException::new);
        return PayrollRunResponse.from(run);
    }

    public PagedModel<PayslipResponse> listPayslipsForRun(Long runId, Pageable pageable) {
        if (!payrollRunRepository.existsById(runId)) {
            throw new PayrollRunNotFoundException();
        }
        return new PagedModel<>(
                payslipRepository.findByPayrollRunId(runId, withIdAscTiebreaker(pageable)).map(PayslipResponse::from));
    }

    public PayslipResponse getPayslip(Long id) {
        Payslip payslip = payslipRepository.findWithDetailsById(id).orElseThrow(PayslipNotFoundException::new);
        return PayslipResponse.from(payslip);
    }

    /** Mirrors LeaveRequestService.withIdDescTiebreaker -- newest-first listings always break same-createdAt ties by id DESC. */
    private static Pageable withIdDescTiebreaker(Pageable pageable) {
        Sort sortWithTiebreaker = pageable.getSort().and(Sort.by("id").descending());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortWithTiebreaker);
    }

    /** Same tiebreaker shape, ascending -- for the employeeCodeSnapshot-ASC payslip listing. */
    private static Pageable withIdAscTiebreaker(Pageable pageable) {
        Sort sortWithTiebreaker = pageable.getSort().and(Sort.by("id").ascending());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortWithTiebreaker);
    }
}
