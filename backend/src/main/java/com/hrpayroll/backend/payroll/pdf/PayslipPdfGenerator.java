package com.hrpayroll.backend.payroll.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Pure PDFBox-based payslip PDF renderer. No Spring, JPA, filesystem, clock, or HTTP dependency --
 * takes an immutable {@link PayslipPdfData} snapshot and returns PDF bytes only. Uses only
 * PDFBox's built-in Standard 14 fonts (WinAnsiEncoding) -- a normal ASCII hyphen is used
 * everywhere a dash is needed in fixed labels/disclaimer text, rather than an em dash or other
 * typography outside that encoding. If a caller-supplied snapshot value contains a character the
 * font cannot encode, generation fails loudly via {@link PayslipPdfGenerationException} -- it
 * never silently drops/replaces characters (so an employee name is never silently altered) and
 * never produces corrupt output.
 */
public final class PayslipPdfGenerator implements PayslipPdfRenderer {

    private static final String HEADING = "HR Payroll System - Demonstration Payslip";
    private static final String DISCLAIMER_LINE_1 =
            "Demonstration only - deduction rates are fixed placeholder figures, not real Malaysian";
    private static final String DISCLAIMER_LINE_2 = "statutory rates. Not for actual payroll, tax, or compliance use.";

    private static final float PAGE_MARGIN = 50f;
    private static final float LINE_HEIGHT = 16f;
    private static final float SECTION_GAP = 10f;

    @Override
    public byte[] generate(PayslipPdfData data) {
        Objects.requireNonNull(data, "data must not be null");

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDFont regularFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                float y = page.getMediaBox().getHeight() - PAGE_MARGIN;

                y = drawLine(contentStream, boldFont, 16, y, HEADING);
                y -= SECTION_GAP;

                y = drawLine(contentStream, regularFont, 11, y,
                        "Payroll Period: " + monthName(data.periodMonth()) + " " + data.periodYear());
                y = drawLine(contentStream, regularFont, 11, y, "Employee Code: " + data.employeeCode());
                y = drawLine(contentStream, regularFont, 11, y, "Employee Name: " + data.employeeName());
                y = drawLine(contentStream, regularFont, 11, y, "Department: " + data.departmentName());
                y = drawLine(contentStream, regularFont, 11, y, "Job Title: " + data.jobTitle());
                y -= SECTION_GAP;

                y = drawLine(contentStream, boldFont, 12, y, "Earnings");
                y = drawLine(contentStream, regularFont, 11, y, "Base Pay: " + CurrencyFormatter.format(data.basePay()));
                y = drawLine(contentStream, regularFont, 11, y,
                        "Housing Allowance: " + CurrencyFormatter.format(data.housingAllowance()));
                y = drawLine(contentStream, regularFont, 11, y,
                        "Transport Allowance: " + CurrencyFormatter.format(data.transportAllowance()));
                y = drawLine(contentStream, regularFont, 11, y, "Gross Pay: " + CurrencyFormatter.format(data.grossPay()));
                y -= SECTION_GAP;

                y = drawLine(contentStream, boldFont, 12, y, "Deductions");
                y = drawLine(contentStream, regularFont, 11, y, "EPF: " + CurrencyFormatter.format(data.epfDeduction()));
                y = drawLine(contentStream, regularFont, 11, y, "SOCSO: " + CurrencyFormatter.format(data.socsoDeduction()));
                y = drawLine(contentStream, regularFont, 11, y, "EIS: " + CurrencyFormatter.format(data.eisDeduction()));
                y = drawLine(contentStream, regularFont, 11, y, "Income Tax: " + CurrencyFormatter.format(data.incomeTax()));
                y = drawLine(contentStream, regularFont, 11, y,
                        "Total Deductions: " + CurrencyFormatter.format(data.totalDeductions()));
                y -= SECTION_GAP;

                y = drawLine(contentStream, boldFont, 14, y, "Net Pay: " + CurrencyFormatter.format(data.netPay()));
                y -= SECTION_GAP * 2;

                y = drawLine(contentStream, regularFont, 8, y, DISCLAIMER_LINE_1);
                drawLine(contentStream, regularFont, 8, y, DISCLAIMER_LINE_2);
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            document.save(buffer);
            return buffer.toByteArray();
        } catch (IllegalArgumentException e) {
            throw new PayslipPdfGenerationException(
                    "Unable to generate the payslip PDF: employee data contains an unsupported character.", e);
        } catch (IOException e) {
            throw new PayslipPdfGenerationException("Unable to generate the payslip PDF.", e);
        }
    }

    private static float drawLine(PDPageContentStream contentStream, PDFont font, float fontSize, float y, String text)
            throws IOException {
        // beginText()/endText() are paired via try/finally so a showText() failure (e.g. an
        // unencodable character) never leaves the content stream with an unbalanced text object --
        // PDFBox logs a WARN and leaves internal state inconsistent otherwise.
        contentStream.beginText();
        try {
            contentStream.setFont(font, fontSize);
            contentStream.newLineAtOffset(PAGE_MARGIN, y);
            contentStream.showText(text);
        } finally {
            contentStream.endText();
        }
        return y - LINE_HEIGHT;
    }

    private static String monthName(int month) {
        return Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }
}
