package com.hrpayroll.backend.payroll;

import java.util.Objects;

/**
 * Immutable internal result of one payslip PDF download -- never an HTTP response, never exposes
 * a filesystem path. Defensively copies the byte array on construction and on every access so a
 * caller mutating either array can never affect the other or this object's own state.
 */
public final class PayslipPdfDownload {

    private final byte[] content;
    private final String filename;

    public PayslipPdfDownload(byte[] content, String filename) {
        Objects.requireNonNull(content, "content must not be null");
        this.content = content.clone();
        this.filename = Objects.requireNonNull(filename, "filename must not be null");
    }

    public byte[] content() {
        return content.clone();
    }

    public String filename() {
        return filename;
    }
}
