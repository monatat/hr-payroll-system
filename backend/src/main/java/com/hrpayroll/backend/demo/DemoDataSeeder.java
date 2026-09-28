package com.hrpayroll.backend.demo;

import com.hrpayroll.backend.department.Department;
import com.hrpayroll.backend.department.DepartmentRepository;
import com.hrpayroll.backend.employee.Employee;
import com.hrpayroll.backend.employee.EmployeeRepository;
import com.hrpayroll.backend.employee.EmploymentStatus;
import com.hrpayroll.backend.leave.LeaveBalance;
import com.hrpayroll.backend.leave.LeaveBalanceRepository;
import com.hrpayroll.backend.leave.LeaveDurationType;
import com.hrpayroll.backend.leave.LeaveRequest;
import com.hrpayroll.backend.leave.LeaveRequestRepository;
import com.hrpayroll.backend.leave.LeaveType;
import com.hrpayroll.backend.payroll.PayrollCalculationResult;
import com.hrpayroll.backend.payroll.PayrollCalculator;
import com.hrpayroll.backend.payroll.PayrollPeriod;
import com.hrpayroll.backend.payroll.PayrollRateSnapshot;
import com.hrpayroll.backend.payroll.PayrollRun;
import com.hrpayroll.backend.payroll.PayrollRunRepository;
import com.hrpayroll.backend.payroll.PayrollRunStatus;
import com.hrpayroll.backend.payroll.Payslip;
import com.hrpayroll.backend.payroll.PayslipRepository;
import com.hrpayroll.backend.payroll.PdfStatus;
import com.hrpayroll.backend.salary.SalaryStructure;
import com.hrpayroll.backend.salary.SalaryStructureRepository;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRepository;
import com.hrpayroll.backend.user.UserRole;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the opt-in local demo dataset (Phase 12C-2): 2 departments, 2 employees, 2 users, 2 salary
 * structures, 2 leave balances, 2 leave requests, 1 payroll run and 2 payslips -- 15 rows -- in ONE
 * transaction, or nothing at all.
 *
 * <p>This is deterministic reference-data insertion through repositories and entity constructors, not a
 * human or domain action, so it deliberately bypasses the domain services: no audit row, no attendance
 * row, no PDF, and no live payroll calculation/transition. Payslip amounts still come from the real
 * {@link PayrollCalculator}, and passwords are hashed with the real {@link PasswordEncoder}.
 *
 * <p>Idempotency: the dataset is recognised only by the fixed {@link DemoIdentities}. If none of the
 * reserved identities exist, everything is created; if the complete, correctly linked dataset exists,
 * nothing is created or changed (dates, hashes, and values are never recomputed); anything else -- a
 * partial dataset or a reserved identity/payroll period used by unrelated data -- fails with the fixed
 * {@link DemoDataConflictException} and writes nothing.
 */
@Service
@Profile("demo")
public class DemoDataSeeder {

    public enum SeedOutcome { CREATED, ALREADY_PRESENT }

    static final String ANNUAL_LEAVE = "Annual Leave";
    static final String SICK_LEAVE = "Sick Leave";
    static final String EMPLOYEE_ONE_JOB_TITLE = "Software Engineer";
    static final String EMPLOYEE_TWO_JOB_TITLE = "Accountant";

    private final EntityManager entityManager;
    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final SalaryStructureRepository salaryStructureRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayslipRepository payslipRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final DemoIdentities identities;

    @Autowired
    public DemoDataSeeder(
            EntityManager entityManager,
            DepartmentRepository departmentRepository,
            EmployeeRepository employeeRepository,
            UserRepository userRepository,
            SalaryStructureRepository salaryStructureRepository,
            LeaveBalanceRepository leaveBalanceRepository,
            LeaveRequestRepository leaveRequestRepository,
            PayrollRunRepository payrollRunRepository,
            PayslipRepository payslipRepository,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(entityManager, departmentRepository, employeeRepository, userRepository, salaryStructureRepository,
                leaveBalanceRepository, leaveRequestRepository, payrollRunRepository, payslipRepository, passwordEncoder,
                transactionManager, clock, DemoIdentities.PRODUCTION);
    }

    /** Package-private: lets tests supply a distinct identity set so they never touch the real demo rows. */
    DemoDataSeeder(
            EntityManager entityManager,
            DepartmentRepository departmentRepository,
            EmployeeRepository employeeRepository,
            UserRepository userRepository,
            SalaryStructureRepository salaryStructureRepository,
            LeaveBalanceRepository leaveBalanceRepository,
            LeaveRequestRepository leaveRequestRepository,
            PayrollRunRepository payrollRunRepository,
            PayslipRepository payslipRepository,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager,
            Clock clock,
            DemoIdentities identities) {
        this.entityManager = entityManager;
        this.departmentRepository = departmentRepository;
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.salaryStructureRepository = salaryStructureRepository;
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.payslipRepository = payslipRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.identities = identities;
    }

    /**
     * Creates the complete dataset atomically, or verifies that it already exists. Any failure rolls the
     * whole transaction back and surfaces only the fixed {@link DemoDataConflictException} -- never the
     * underlying persistence exception (deliberately not attached as a cause).
     */
    public SeedOutcome seed(String adminPassword, String employeePassword) {
        try {
            return transactionTemplate.execute(status -> seedInTransaction(adminPassword, employeePassword));
        } catch (DemoDataConflictException conflict) {
            throw conflict;
        } catch (RuntimeException failure) {
            throw new DemoDataConflictException();
        }
    }

    private SeedOutcome seedInTransaction(String adminPassword, String employeePassword) {
        User admin = findSingle("SELECT u FROM User u WHERE u.email = :value", User.class, identities.adminEmail());
        User employeeUser = findSingle("SELECT u FROM User u WHERE u.email = :value", User.class, identities.employeeEmail());
        Employee one = findSingle("SELECT e FROM Employee e WHERE e.employeeCode = :value", Employee.class,
                identities.employeeOneCode());
        Employee two = findSingle("SELECT e FROM Employee e WHERE e.employeeCode = :value", Employee.class,
                identities.employeeTwoCode());
        Employee oneByIc = findSingle("SELECT e FROM Employee e WHERE e.icNumber = :value", Employee.class,
                identities.employeeOneIc());
        Employee twoByIc = findSingle("SELECT e FROM Employee e WHERE e.icNumber = :value", Employee.class,
                identities.employeeTwoIc());
        Department engineering = findSingle("SELECT d FROM Department d WHERE d.name = :value", Department.class,
                identities.engineeringDepartment());
        Department finance = findSingle("SELECT d FROM Department d WHERE d.name = :value", Department.class,
                identities.financeDepartment());

        List<Object> reserved = Arrays.asList(admin, employeeUser, one, two, oneByIc, twoByIc, engineering, finance);
        if (reserved.stream().allMatch(Objects::isNull)) {
            createDataset(adminPassword, employeePassword);
            return SeedOutcome.CREATED;
        }
        if (reserved.stream().noneMatch(Objects::isNull) && isCompleteDataset(admin, employeeUser, one, two, oneByIc, twoByIc)) {
            return SeedOutcome.ALREADY_PRESENT;
        }
        throw new DemoDataConflictException();
    }

    /**
     * Validates identity links and the presence of every critical child -- not just row counts -- using
     * the stored relationships, so nothing is recomputed from today's date. Mutable values (statuses,
     * job titles, extra user-created leave requests) are intentionally not required to match.
     */
    private boolean isCompleteDataset(User admin, User employeeUser, Employee one, Employee two, Employee oneByIc,
            Employee twoByIc) {
        return one.getId().equals(oneByIc.getId())
                && two.getId().equals(twoByIc.getId())
                && !one.getId().equals(two.getId())
                && admin.getRole() == UserRole.HR_ADMIN
                && admin.getEmployeeId() == null
                && employeeUser.getRole() == UserRole.EMPLOYEE
                && one.getId().equals(employeeUser.getEmployeeId())
                && count("SELECT COUNT(s) FROM SalaryStructure s WHERE s.employee.id = :id", one.getId()) > 0
                && count("SELECT COUNT(s) FROM SalaryStructure s WHERE s.employee.id = :id", two.getId()) > 0
                && countBalances(one.getId(), ANNUAL_LEAVE) > 0
                && countBalances(one.getId(), SICK_LEAVE) > 0
                && count("SELECT COUNT(r) FROM LeaveRequest r WHERE r.employee.id = :id", one.getId()) >= 2
                && sharesAPayrollRun(one.getId(), two.getId());
    }

    private void createDataset(String adminPassword, String employeePassword) {
        LocalDate today = LocalDate.now(clock);
        YearMonth payrollMonth = YearMonth.from(today).minusMonths(1);
        if (payrollRunRepository.existsByPeriodMonthAndPeriodYear(payrollMonth.getMonthValue(), payrollMonth.getYear())) {
            throw new DemoDataConflictException();
        }
        LeaveType annualLeave = findSingle("SELECT t FROM LeaveType t WHERE t.name = :value", LeaveType.class, ANNUAL_LEAVE);
        LeaveType sickLeave = findSingle("SELECT t FROM LeaveType t WHERE t.name = :value", LeaveType.class, SICK_LEAVE);
        if (annualLeave == null || sickLeave == null) {
            throw new DemoDataConflictException();
        }

        Department engineering = departmentRepository.save(new Department(identities.engineeringDepartment()));
        Department finance = departmentRepository.save(new Department(identities.financeDepartment()));

        Employee one = employeeRepository.save(new Employee(identities.employeeOneCode(), identities.employeeOneName(),
                identities.employeeOneIc(), engineering, EMPLOYEE_ONE_JOB_TITLE, EmploymentStatus.ACTIVE,
                today.minusYears(2), null, null, null));
        Employee two = employeeRepository.save(new Employee(identities.employeeTwoCode(), identities.employeeTwoName(),
                identities.employeeTwoIc(), finance, EMPLOYEE_TWO_JOB_TITLE, EmploymentStatus.ACTIVE,
                today.minusYears(1), null, null, null));

        User admin = userRepository.save(new User(identities.adminEmail(), passwordEncoder.encode(adminPassword),
                UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        userRepository.save(new User(identities.employeeEmail(), passwordEncoder.encode(employeePassword),
                UserRole.EMPLOYEE, AccountStatus.ACTIVE, one.getId()));

        salaryStructureRepository.save(new SalaryStructure(one, money("4200.00"), money("400.00"), money("150.00"),
                one.getHireDate(), null));
        salaryStructureRepository.save(new SalaryStructure(two, money("5200.00"), money("500.00"), money("200.00"),
                two.getHireDate(), null));

        LeavePlan plan = LeavePlan.forDate(today);
        leaveBalanceRepository.save(new LeaveBalance(one, annualLeave, plan.year(), days("14.0"), days("2.0")));
        leaveBalanceRepository.save(new LeaveBalance(one, sickLeave, plan.year(), days("14.0"), days("0.0")));

        LeaveRequest approved = new LeaveRequest(one, annualLeave, plan.approvedStart(), plan.approvedEnd(), days("2.0"),
                null, LeaveDurationType.FULL_DAY);
        approved.approve(admin.getId(), LocalDateTime.now(clock));
        leaveRequestRepository.save(approved);
        leaveRequestRepository.save(new LeaveRequest(one, annualLeave, plan.pendingStart(), plan.pendingEnd(), days("3.0"),
                null, LeaveDurationType.FULL_DAY));

        PayrollPeriod period = PayrollPeriod.of(payrollMonth.getMonthValue(), payrollMonth.getYear());
        PayrollRateSnapshot rates = PayrollRateSnapshot.demoDefaults();
        PayrollRun run = payrollRunRepository.save(new PayrollRun(period.month(), period.year(), period.periodStart(),
                period.periodEnd(), PayrollRunStatus.CALCULATED, rates.epfRate(), rates.socsoRate(), rates.eisRate(),
                rates.incomeTaxRate()));

        payslipRepository.save(payslip(run, one, engineering, EMPLOYEE_ONE_JOB_TITLE, "4200.00", "400.00", "150.00", rates));
        payslipRepository.save(payslip(run, two, finance, EMPLOYEE_TWO_JOB_TITLE, "5200.00", "500.00", "200.00", rates));
    }

    private static Payslip payslip(PayrollRun run, Employee employee, Department department, String jobTitle, String basePay,
            String housingAllowance, String transportAllowance, PayrollRateSnapshot rates) {
        PayrollCalculationResult result = PayrollCalculator.calculate(money(basePay), money(housingAllowance),
                money(transportAllowance), rates);
        return new Payslip(run, employee, employee.getEmployeeCode(), employee.getFullName(), department.getName(), jobTitle,
                result.basePay(), result.housingAllowance(), result.transportAllowance(), result.grossPay(),
                result.epfDeduction(), result.socsoDeduction(), result.eisDeduction(), result.incomeTax(),
                result.totalDeductions(), result.netPay(), null, PdfStatus.PENDING);
    }

    private <T> T findSingle(String jpql, Class<T> type, String value) {
        return entityManager.createQuery(jpql, type).setParameter("value", value).getResultStream().findFirst().orElse(null);
    }

    private long count(String jpql, Long id) {
        return entityManager.createQuery(jpql, Long.class).setParameter("id", id).getSingleResult();
    }

    private long countBalances(Long employeeId, String leaveTypeName) {
        return entityManager.createQuery(
                        "SELECT COUNT(b) FROM LeaveBalance b WHERE b.employee.id = :id AND b.leaveType.name = :name", Long.class)
                .setParameter("id", employeeId)
                .setParameter("name", leaveTypeName)
                .getSingleResult();
    }

    private boolean sharesAPayrollRun(Long employeeOneId, Long employeeTwoId) {
        return entityManager.createQuery(
                        "SELECT COUNT(p1) FROM Payslip p1, Payslip p2 WHERE p1.payrollRun = p2.payrollRun "
                                + "AND p1.employee.id = :one AND p2.employee.id = :two", Long.class)
                .setParameter("one", employeeOneId)
                .setParameter("two", employeeTwoId)
                .getSingleResult() > 0;
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal days(String value) {
        return new BigDecimal(value);
    }

    /**
     * Deterministic leave dates for a first-seed date: an APPROVED Monday-Tuesday (2 working days) and,
     * one week later, a PENDING Monday-Wednesday (3 working days) -- weekdays only, never overlapping,
     * and always inside the same calendar year as the seeded balances. The anchor is the Monday on or
     * before {@code today}; it moves forward a week while it would fall in the previous year (early
     * January) and back a week while the pending request would spill into the next year (late December).
     */
    record LeavePlan(int year, LocalDate approvedStart, LocalDate approvedEnd, LocalDate pendingStart, LocalDate pendingEnd) {

        static LeavePlan forDate(LocalDate today) {
            int year = today.getYear();
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            while (monday.getYear() < year) {
                monday = monday.plusWeeks(1);
            }
            while (monday.plusDays(9).getYear() > year) {
                monday = monday.minusWeeks(1);
            }
            return new LeavePlan(year, monday, monday.plusDays(1), monday.plusDays(7), monday.plusDays(9));
        }
    }
}
