package com.maheshclothes.invoice;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.maheshclothes.common.IndianFormat;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.SettingsService.ShopInfo;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.sales.SalesInvoice;
import com.maheshclothes.sales.SalesQueryRepository;
import com.maheshclothes.sales.SalesQueryRepository.LineRow;
import com.maheshclothes.sales.SalesQueryRepository.PaymentRow;
import com.maheshclothes.sales.SalesRepositories.SalesInvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Builds the A4 invoice PDF that is archived and sent to the customer.
 *
 * Fonts: the PDF uses the standard Helvetica font, which has no Devanagari. Marathi (and any other non-Latin)
 * text is therefore replaced by "?" in the PDF only; product names should have an English name. The WhatsApp
 * message text and the on-screen invoice are fully Unicode. Embedding a Devanagari TTF needs a text-shaping
 * engine for conjuncts and is deliberately not attempted here.
 */
@Service
@RequiredArgsConstructor
public class InvoicePdfService {

    private static final Color INDIGO = new Color(0x43, 0x38, 0xCA);
    private static final Color LIGHT = new Color(0xF1, 0xF2, 0xF9);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a");

    private final SalesInvoiceRepository invoices;
    private final SalesQueryRepository query;
    private final SettingsService settings;
    private final ShopClock clock;

    public byte[] render(UUID invoiceId) {
        SalesInvoice inv = invoices.findById(invoiceId).orElseThrow(() -> new NotFoundException("Invoice"));
        List<LineRow> lines = query.lines(invoiceId);
        List<PaymentRow> pays = query.paymentsOf(invoiceId);
        ShopInfo shop = settings.shop();

        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, INDIGO);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        Font norm = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);
        Font white = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 36, 40);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Invoice " + inv.getInvoiceNo());
            doc.addCreator(latin(shop.name()));
            doc.open();

            Paragraph name = new Paragraph(latin(shop.name()), title);
            doc.add(name);
            if (!shop.addressLine().isBlank()) {
                doc.add(new Paragraph(latin(shop.addressLine()), norm));
            }
            StringBuilder contact = new StringBuilder();
            if (!shop.phone().isBlank()) {
                contact.append("Phone: ").append(shop.phone());
            }
            if (shop.taxEnabled() && !shop.gstin().isBlank()) {
                contact.append(contact.length() > 0 ? "   " : "").append("GSTIN: ").append(shop.gstin());
            }
            if (contact.length() > 0) {
                doc.add(new Paragraph(latin(contact.toString()), norm));
            }
            doc.add(new Paragraph(" ", small));

            PdfPTable head = new PdfPTable(2);
            head.setWidthPercentage(100);
            head.addCell(plain(new Phrase((shop.taxEnabled() ? "TAX INVOICE" : "INVOICE") + "  " + inv.getInvoiceNo(), bold), Element.ALIGN_LEFT));
            head.addCell(plain(new Phrase("Date: " + DT.format(clock.toLocal(inv.getSaleTime())), norm), Element.ALIGN_RIGHT));
            String cust = latin(inv.getCustomerName() == null ? "Walk-in customer" : inv.getCustomerName());
            head.addCell(plain(new Phrase("Customer: " + cust, norm), Element.ALIGN_LEFT));
            head.addCell(plain(new Phrase(inv.getCustomerPhone() == null ? "" : "Phone: " + inv.getCustomerPhone(), norm), Element.ALIGN_RIGHT));
            doc.add(head);
            doc.add(new Paragraph(" ", small));

            boolean tax = shop.taxEnabled();
            PdfPTable t = new PdfPTable(tax ? new float[]{4.2f, 1.1f, 1.8f, 1.6f, 1.2f, 2f} : new float[]{5f, 1.2f, 2f, 1.8f, 2.2f});
            t.setWidthPercentage(100);
            header(t, white, "Item", "Qty", "Rate", "Disc.", tax ? "GST%" : null, "Amount");
            boolean alt = false;
            for (LineRow l : lines) {
                StringBuilder desc = new StringBuilder(latin(l.productName()));
                if (l.colourName() != null || l.sizeName() != null) {
                    desc.append(" (").append(l.colourName() == null ? "" : latin(l.colourName()))
                            .append(l.colourName() != null && l.sizeName() != null ? ", " : "")
                            .append(l.sizeName() == null ? "" : latin(l.sizeName())).append(")");
                }
                BigDecimal disc = l.lineDiscount().add(l.invoiceDiscountShare());
                row(t, norm, alt, desc.toString(), String.valueOf(l.quantity()), IndianFormat.amount(l.unitPrice()),
                        IndianFormat.amount(disc), tax ? l.taxRate().stripTrailingZeros().toPlainString() : null,
                        IndianFormat.amount(l.lineTotal()));
                alt = !alt;
            }
            doc.add(t);
            doc.add(new Paragraph(" ", small));

            PdfPTable totals = new PdfPTable(new float[]{6f, 3f});
            totals.setWidthPercentage(50);
            totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
            total(totals, norm, "Subtotal", inv.getSubtotal());
            if (inv.getLineDiscountTotal().add(inv.getInvoiceDiscount()).signum() > 0) {
                total(totals, norm, "Discount", inv.getLineDiscountTotal().add(inv.getInvoiceDiscount()).negate());
            }
            if (tax) {
                total(totals, norm, "Taxable value", inv.getTaxableTotal());
                total(totals, norm, "GST", inv.getTaxTotal());
            }
            if (inv.getRoundOff().signum() != 0) {
                total(totals, norm, "Round off", inv.getRoundOff());
            }
            total(totals, bold, "TOTAL", inv.getTotal());
            total(totals, norm, "Paid", inv.getAmountPaid());
            if (inv.getBalanceDue().signum() > 0) {
                total(totals, bold, "BALANCE DUE", inv.getBalanceDue());
            }
            doc.add(totals);

            if (!pays.isEmpty()) {
                StringBuilder sb = new StringBuilder("Payment: ");
                for (int i = 0; i < pays.size(); i++) {
                    PaymentRow p = pays.get(i);
                    sb.append(i > 0 ? ", " : "").append(latin(p.methodName())).append(" Rs.").append(IndianFormat.amount(p.amount()));
                }
                doc.add(new Paragraph(sb.toString(), small));
            }
            if (!shop.footer().isBlank()) {
                Paragraph f = new Paragraph(latin(shop.footer()), norm);
                f.setSpacingBefore(14);
                f.setAlignment(Element.ALIGN_CENTER);
                doc.add(f);
            }
            if (!shop.returnPolicy().isBlank()) {
                Paragraph rp = new Paragraph(latin(shop.returnPolicy()), small);
                rp.setAlignment(Element.ALIGN_CENTER);
                doc.add(rp);
            }
            Paragraph gen = new Paragraph("This is a computer generated invoice.", small);
            gen.setAlignment(Element.ALIGN_CENTER);
            gen.setSpacingBefore(6);
            doc.add(gen);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not build the invoice PDF", e);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }

    /** Helvetica can only draw Latin-1; anything else (e.g. Devanagari) becomes '?'. */
    static String latin(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(c < 256 ? c : '?');
        }
        return sb.toString();
    }

    private static PdfPCell plain(Phrase p, int align) {
        PdfPCell c = new PdfPCell(p);
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        c.setPadding(2);
        return c;
    }

    private static void header(PdfPTable t, Font f, String... cols) {
        for (int i = 0; i < cols.length; i++) {
            if (cols[i] == null) {
                continue;
            }
            PdfPCell c = new PdfPCell(new Phrase(cols[i], f));
            c.setBackgroundColor(INDIGO);
            c.setBorder(Rectangle.NO_BORDER);
            c.setPadding(5);
            c.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            t.addCell(c);
        }
    }

    private static void row(PdfPTable t, Font f, boolean alt, String... cols) {
        for (int i = 0; i < cols.length; i++) {
            if (cols[i] == null) {
                continue;
            }
            PdfPCell c = new PdfPCell(new Phrase(cols[i], f));
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(Color.LIGHT_GRAY);
            c.setPadding(4);
            if (alt) {
                c.setBackgroundColor(LIGHT);
            }
            c.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            t.addCell(c);
        }
    }

    private static void total(PdfPTable t, Font f, String label, BigDecimal v) {
        t.addCell(plain(new Phrase(label, f), Element.ALIGN_LEFT));
        t.addCell(plain(new Phrase("Rs. " + IndianFormat.amount(v), f), Element.ALIGN_RIGHT));
    }
}
