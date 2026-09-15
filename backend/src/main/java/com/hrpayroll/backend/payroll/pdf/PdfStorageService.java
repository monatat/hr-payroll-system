package com.hrpayroll.backend.payroll.pdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Secure filesystem storage for generated payslip PDFs, kept deliberately separate from
 * {@link PayslipPdfGenerator}. Plain, directly instantiable class (no Spring annotation) --
 * wiring this into the Spring context is deferred to the Phase 8C-2B orchestration service, which
 * is also responsible for the database transaction/locking half of "move file before marking
 * GENERATED" (see the class-level note on that future service). Every stored/loaded path is a
 * server-controlled, deterministic {@code payroll-run-{runId}/payslip-{payslipId}.pdf} built only
 * from positive database identity values -- never from employee/user-controlled text, and never
 * from an HTTP-client-supplied destination.
 *
 * <p><b>Future 8C-2B orchestration note (not implemented here):</b> the caller that eventually
 * wires this together with persistence must (1) call {@link #store} and only then mark the
 * corresponding {@code Payslip} GENERATED, never the other way around; (2) catch only the
 * expected {@link PayslipPdfGenerationException}/{@link PayrollPdfStorageException} failures per
 * payslip, never broadly catching and suppressing a JPA/database persistence failure; (3) let a
 * persistence failure escape, since Hibernate may already have marked the transaction
 * rollback-only by that point; (4) accept that a database rollback occurring after a successful
 * {@link #store} call leaves a harmless, deterministic orphan file that a later retry overwrites
 * (the same relative path is always recomputed from the same ids); and (5) never treat a
 * filesystem write as if it participated in the PostgreSQL transaction -- Postgres cannot roll
 * back a file that has already been moved into place.
 */
public final class PdfStorageService {

    private final Path root;
    private final Runnable preMoveHook;

    public PdfStorageService(String storageRootDir) {
        this(storageRootDir, () -> {
        });
    }

    /** Package-private test seam only: {@code preMoveHook} runs after the temp file is fully written but before the atomic move, so a test can inject a deterministic failure at that exact point without relying on OS file-permission assumptions. Production code always uses the public constructor, whose hook is a no-op. */
    PdfStorageService(String storageRootDir, Runnable preMoveHook) {
        Objects.requireNonNull(storageRootDir, "storageRootDir must not be null");
        this.preMoveHook = Objects.requireNonNull(preMoveHook, "preMoveHook must not be null");

        Path candidate = Path.of(storageRootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(candidate);
            // Canonicalize once, after creation, so every later containment check
            // (Path.startsWith) compares against the storage root's real physical location rather
            // than its lexical one -- a prerequisite for detecting a symlink anywhere in a
            // resolved path's chain.
            this.root = candidate.toRealPath();
        } catch (IOException e) {
            throw new PayrollPdfStorageException("Unable to initialize the payslip PDF storage directory.", e);
        }
    }

    /** Builds the deterministic relative path for one payslip's PDF -- never employee/user-controlled text. */
    public static String relativePathFor(long payrollRunId, long payslipId) {
        requirePositive(payrollRunId, "payrollRunId");
        requirePositive(payslipId, "payslipId");
        return "payroll-run-" + payrollRunId + "/payslip-" + payslipId + ".pdf";
    }

    /**
     * Writes {@code pdfBytes} to a temp file in the same target directory, then atomically moves
     * it into place, and returns the relative path that was written. On any failure, any temp file
     * created is removed and nothing is left in a partially-written state at the final path.
     *
     * <p>Rejects a pre-existing symbolic link at either the run-directory or final-file position
     * before any write is attempted, and re-verifies the run directory's real (canonicalized)
     * location stays under the storage root before creating the temp file inside it -- this is
     * defense against a stored-path/pre-existing-link escape, not protection against an attacker
     * who can continuously mutate the filesystem during the call (out of scope for this local CV
     * project; no {@code SecureDirectoryStream} or platform-specific code is used).
     */
    public String store(long payrollRunId, long payslipId, byte[] pdfBytes) {
        Objects.requireNonNull(pdfBytes, "pdfBytes must not be null");
        String relativePath = relativePathFor(payrollRunId, payslipId);
        Path target = resolveUnderRoot(relativePath);
        Path runDir = target.getParent();

        // Checked before any write: a symlink here would otherwise let createDirectories/
        // createTempFile silently write through it to wherever it points.
        if (Files.isSymbolicLink(runDir)) {
            throw new PayrollPdfStorageException("Unable to store the generated payslip PDF.");
        }

        Path temp = null;
        try {
            Files.createDirectories(runDir);

            Path realRunDir = runDir.toRealPath();
            if (!realRunDir.startsWith(root)) {
                throw new PayrollPdfStorageException("Unable to store the generated payslip PDF.");
            }

            Path realTarget = realRunDir.resolve(target.getFileName());
            if (Files.isSymbolicLink(realTarget)) {
                throw new PayrollPdfStorageException("Unable to store the generated payslip PDF.");
            }

            temp = Files.createTempFile(realRunDir, "payslip-" + payslipId + "-", ".pdf.tmp");
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(pdfBytes);
            }
            preMoveHook.run();
            moveAtomically(temp, realTarget);
            temp = null; // moved successfully -- nothing left for the finally block to clean up
            return relativePath;
        } catch (IOException e) {
            throw new PayrollPdfStorageException("Unable to store the generated payslip PDF.", e);
        } finally {
            deleteIfExists(temp);
        }
    }

    /**
     * Reads back a previously stored PDF by its stored relative path, treated as untrusted on
     * read: rejects a symbolic link at the stored path outright, then independently resolves the
     * real (canonicalized) physical path -- catching a symlink anywhere earlier in the path chain,
     * e.g. a compromised run directory -- and verifies it still lies under the canonical storage
     * root before ever reading its bytes. Never exposes a physical path in an exception.
     */
    public byte[] load(String storedRelativePath) {
        Objects.requireNonNull(storedRelativePath, "storedRelativePath must not be null");
        Path target = resolveUnderRoot(storedRelativePath);

        if (Files.isSymbolicLink(target)) {
            throw new PayrollPdfStorageException("Stored payslip PDF path is invalid.");
        }
        if (!Files.exists(target)) {
            throw new PayslipPdfFileMissingException();
        }

        Path realTarget;
        try {
            realTarget = target.toRealPath();
        } catch (IOException e) {
            throw new PayslipPdfFileMissingException();
        }
        if (!realTarget.startsWith(root)) {
            throw new PayrollPdfStorageException("Stored payslip PDF path is invalid.");
        }
        if (!Files.isRegularFile(realTarget)) {
            throw new PayslipPdfFileMissingException();
        }

        try {
            return Files.readAllBytes(realTarget);
        } catch (IOException e) {
            throw new PayrollPdfStorageException("Unable to read the stored payslip PDF.", e);
        }
    }

    private Path resolveUnderRoot(String relativePath) {
        Path candidate = Path.of(relativePath);
        if (candidate.isAbsolute()) {
            throw new PayrollPdfStorageException("Stored payslip PDF path is invalid.");
        }
        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw new PayrollPdfStorageException("Stored payslip PDF path is invalid.");
        }
        return resolved;
    }

    private static void moveAtomically(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void requirePositive(long id, String fieldName) {
        if (id <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive: was " + id);
        }
    }

    private static void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup only -- the original failure is already being thrown/propagated.
        }
    }
}
