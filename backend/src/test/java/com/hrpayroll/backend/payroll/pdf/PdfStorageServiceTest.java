package com.hrpayroll.backend.payroll.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link PdfStorageService}. Every test uses a JUnit {@code @TempDir} as the
 * storage root -- never the real development {@code ./data/payroll-pdfs} directory. No Spring
 * context; this class is plain and directly instantiable (wiring it into Spring is deferred to
 * the Phase 8C-2B orchestration service).
 */
class PdfStorageServiceTest {

    private static final byte[] SAMPLE_BYTES = "%PDF-1.7 sample content".getBytes(StandardCharsets.US_ASCII);

    @Test
    void relativePathForIsDeterministic() {
        assertThat(PdfStorageService.relativePathFor(1, 2)).isEqualTo("payroll-run-1/payslip-2.pdf");
        assertThat(PdfStorageService.relativePathFor(1, 2)).isEqualTo(PdfStorageService.relativePathFor(1, 2));
    }

    @Test
    void relativePathForRejectsNonPositiveIds() {
        assertThatThrownBy(() -> PdfStorageService.relativePathFor(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PdfStorageService.relativePathFor(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PdfStorageService.relativePathFor(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PdfStorageService.relativePathFor(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storeRejectsNonPositiveIds(@TempDir Path root) {
        PdfStorageService service = new PdfStorageService(root.toString());

        assertThatThrownBy(() -> service.store(0, 1, SAMPLE_BYTES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.store(1, 0, SAMPLE_BYTES)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storeCreatesTheFileAtTheDeterministicRelativePath(@TempDir Path root) throws IOException {
        PdfStorageService service = new PdfStorageService(root.toString());

        String relativePath = service.store(1, 2, SAMPLE_BYTES);

        assertThat(relativePath).isEqualTo("payroll-run-1/payslip-2.pdf");
        assertThat(Files.isRegularFile(root.resolve("payroll-run-1").resolve("payslip-2.pdf"))).isTrue();
    }

    @Test
    void storePreservesExactBytes(@TempDir Path root) throws IOException {
        PdfStorageService service = new PdfStorageService(root.toString());

        String relativePath = service.store(1, 2, SAMPLE_BYTES);

        assertThat(service.load(relativePath)).isEqualTo(SAMPLE_BYTES);
    }

    @Test
    void storingAgainReplacesThePreviousFileRatherThanCorruptingIt(@TempDir Path root) throws IOException {
        PdfStorageService service = new PdfStorageService(root.toString());
        byte[] secondVersion = "%PDF-1.7 second version, longer content than before".getBytes(StandardCharsets.US_ASCII);

        service.store(1, 2, SAMPLE_BYTES);
        String relativePath = service.store(1, 2, secondVersion);

        assertThat(service.load(relativePath)).isEqualTo(secondVersion);
    }

    @Test
    void storeCreatesOnlyTheRequiredRunDirectory(@TempDir Path root) throws IOException {
        PdfStorageService service = new PdfStorageService(root.toString());

        service.store(1, 2, SAMPLE_BYTES);

        assertThat(Files.isDirectory(root.resolve("payroll-run-1"))).isTrue();
        try (var siblingEntries = Files.list(root)) {
            assertThat(siblingEntries).hasSize(1);
        }
    }

    @Test
    void noTempFileSurvivesAfterASuccessfulStore(@TempDir Path root) throws IOException {
        PdfStorageService service = new PdfStorageService(root.toString());

        service.store(1, 2, SAMPLE_BYTES);

        try (var runDirEntries = Files.list(root.resolve("payroll-run-1"))) {
            assertThat(runDirEntries).allMatch(p -> p.getFileName().toString().equals("payslip-2.pdf"));
        }
    }

    @Test
    void noTempFileSurvivesAfterADeterministicInjectedFailure(@TempDir Path root) throws IOException {
        PdfStorageService failingService =
                new PdfStorageService(root.toString(), () -> {
                    throw new RuntimeException("injected failure before atomic move");
                });

        assertThatThrownBy(() -> failingService.store(1, 2, SAMPLE_BYTES)).isInstanceOf(RuntimeException.class);

        Path runDir = root.resolve("payroll-run-1");
        assertThat(Files.isRegularFile(runDir.resolve("payslip-2.pdf"))).isFalse();
        if (Files.isDirectory(runDir)) {
            try (var runDirEntries = Files.list(runDir)) {
                assertThat(runDirEntries).isEmpty();
            }
        }
    }

    @Test
    void loadRejectsATraversalAttempt(@TempDir Path root) throws IOException {
        Path outsideRoot = root.getParent().resolve("evil.pdf");
        Files.writeString(outsideRoot, "secret", StandardCharsets.US_ASCII);
        PdfStorageService service = new PdfStorageService(root.toString());

        assertThatThrownBy(() -> service.load("../evil.pdf")).isInstanceOf(PayrollPdfStorageException.class);
    }

    @Test
    void loadRejectsAnAbsoluteStoredPath(@TempDir Path root) {
        PdfStorageService service = new PdfStorageService(root.toString());
        String absolutePath = root.resolve("payroll-run-1").resolve("payslip-2.pdf").toAbsolutePath().toString();

        assertThatThrownBy(() -> service.load(absolutePath)).isInstanceOf(PayrollPdfStorageException.class);
    }

    @Test
    void loadRejectsASiblingPrefixEscapeAttempt(@TempDir Path parent) throws IOException {
        Path root = parent.resolve("storage");
        Path evilSibling = parent.resolve("storage-evil");
        Files.createDirectories(evilSibling);
        Files.writeString(evilSibling.resolve("x.pdf"), "secret", StandardCharsets.US_ASCII);
        PdfStorageService service = new PdfStorageService(root.toString());

        // "storage-evil" shares the string prefix "storage" with the root but is a different
        // sibling directory -- Path-based containment must reject this, not a naive String.startsWith.
        assertThatThrownBy(() -> service.load("../storage-evil/x.pdf")).isInstanceOf(PayrollPdfStorageException.class);
    }

    @Test
    void loadHandlesAMissingFileSafely(@TempDir Path root) {
        PdfStorageService service = new PdfStorageService(root.toString());

        assertThatThrownBy(() -> service.load("payroll-run-1/payslip-999.pdf"))
                .isInstanceOf(PayslipPdfFileMissingException.class);
    }

    @Test
    void loadRejectsADirectoryMasqueradingAsAPdf(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("payroll-run-1").resolve("payslip-2.pdf"));
        PdfStorageService service = new PdfStorageService(root.toString());

        assertThatThrownBy(() -> service.load("payroll-run-1/payslip-2.pdf"))
                .isInstanceOf(PayslipPdfFileMissingException.class);
    }

    @Test
    void storeReturnsARelativePathNeverAnAbsoluteOne(@TempDir Path root) throws IOException {
        PdfStorageService service = new PdfStorageService(root.toString());

        String relativePath = service.store(1, 2, SAMPLE_BYTES);

        assertThat(Path.of(relativePath).isAbsolute()).isFalse();
        assertThat(relativePath).doesNotContain(root.toString());
    }

    @Test
    void exceptionMessagesNeverContainTheAbsoluteRootPath(@TempDir Path root) throws IOException {
        Path notADirectory = Files.writeString(root.resolve("not-a-directory"), "x", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> new PdfStorageService(notADirectory.toString()))
                .isInstanceOf(PayrollPdfStorageException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(notADirectory.toString()));
    }

    @Test
    void failsClearlyAndSafelyWhenTheConfiguredRootCannotBeInitialized(@TempDir Path root) throws IOException {
        // A regular file where a directory is expected is a deterministic, cross-platform failure
        // that does not rely on OS file-permission assumptions.
        Path fileNotDirectory = Files.writeString(root.resolve("blocked"), "x", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> new PdfStorageService(fileNotDirectory.toString()))
                .isInstanceOf(PayrollPdfStorageException.class);
    }

    @Test
    void noWriteOccursOutsideTheConfiguredRoot(@TempDir Path parent) throws IOException {
        Path root = parent.resolve("storage");
        Files.createDirectories(root);
        Path sibling = parent.resolve("sibling");
        Files.createDirectories(sibling);
        PdfStorageService service = new PdfStorageService(root.toString());

        service.store(1, 2, SAMPLE_BYTES);

        try (var siblingEntries = Files.list(sibling)) {
            assertThat(siblingEntries).isEmpty();
        }
    }

    @Test
    void theRealDevelopmentDefaultDirectoryIsNeverCreatedByTests() {
        assertThat(Files.exists(Path.of("./data/payroll-pdfs"))).isFalse();
    }

    /**
     * Attempts to create a symbolic link; if the OS/environment genuinely does not permit it
     * (e.g. Windows without Developer Mode or elevated privileges), aborts only the calling test
     * with a clear reason rather than failing it or weakening the production check it exercises.
     */
    private static void createSymbolicLinkOrAssume(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(
                    false,
                    "Symbolic link creation is not permitted on this OS/environment ("
                            + e.getClass().getSimpleName() + ") -- skipping this symlink-specific test only.");
        }
    }

    @Test
    void loadRejectsASymbolicLinkPointingOutsideTheRoot(@TempDir Path parent) throws IOException {
        Path root = parent.resolve("storage");
        Files.createDirectories(root);
        Path outsideFile = parent.resolve("secret.pdf");
        Files.writeString(outsideFile, "outside content", StandardCharsets.US_ASCII);
        Path runDir = root.resolve("payroll-run-1");
        Files.createDirectories(runDir);
        Path linkPath = runDir.resolve("payslip-1.pdf");
        createSymbolicLinkOrAssume(linkPath, outsideFile);

        PdfStorageService service = new PdfStorageService(root.toString());

        assertThatThrownBy(() -> service.load("payroll-run-1/payslip-1.pdf"))
                .isInstanceOf(PayrollPdfStorageException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).doesNotContain(outsideFile.toAbsolutePath().toString());
                    assertThat(ex.getMessage()).doesNotContain(root.toAbsolutePath().toString());
                });
    }

    @Test
    void storeRejectsASymbolicLinkRunDirectoryAndWritesNothingOutsideTheRoot(@TempDir Path parent) throws IOException {
        Path root = parent.resolve("storage");
        Files.createDirectories(root);
        Path outsideDir = parent.resolve("outside-run-dir");
        Files.createDirectories(outsideDir);
        Path linkedRunDir = root.resolve("payroll-run-1");
        createSymbolicLinkOrAssume(linkedRunDir, outsideDir);

        PdfStorageService service = new PdfStorageService(root.toString());

        assertThatThrownBy(() -> service.store(1, 1, SAMPLE_BYTES)).isInstanceOf(PayrollPdfStorageException.class);

        try (var outsideEntries = Files.list(outsideDir)) {
            assertThat(outsideEntries).isEmpty();
        }
    }

    @Test
    void aNormalNonSymlinkFileBeneathTheRootStillLoadsSuccessfullyAfterHardening(@TempDir Path root) throws IOException {
        PdfStorageService service = new PdfStorageService(root.toString());

        String relativePath = service.store(5, 9, SAMPLE_BYTES);

        assertThat(service.load(relativePath)).isEqualTo(SAMPLE_BYTES);
    }
}
