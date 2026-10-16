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
    private static final Color GREEN = new Color(0x16, 0xA3, 0x4A);
    private static final Color LIGHT = new Color(0xF6, 0xF7, 0xFC);
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

        Font shopFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.WHITE);
        Font onBand = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(0xE0, 0xE7, 0xFF));
        Font bandBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.WHITE);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        Font norm = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);
        Font label = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, INDIGO);
        Font white = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
        Font grand = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.WHITE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 32, 32, 32, 40);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Invoice " + inv.getInvoiceNo());
            doc.addCreator(latin(shop.name()));
            doc.open();

            // ---- banner: shop on the left, invoice number and date on the right
            PdfPTable band = new PdfPTable(new float[]{6f, 4f});
            band.setWidthPercentage(100);
            PdfPCell left = new PdfPCell();
            left.setBackgroundColor(INDIGO);
            left.setBorder(Rectangle.NO_BORDER);
            left.setPadding(14);
            left.addElement(new Paragraph(latin(shop.name()), shopFont));
            if (!shop.addressLine().isBlank()) {
                left.addElement(new Paragraph(latin(shop.addressLine()), onBand));
            }
            StringBuilder contact = new StringBuilder();
            if (!shop.phone().isBlank()) {
                contact.append("Phone: ").append(shop.phone());
            }
            if (shop.taxEnabled() && !shop.gstin().isBlank()) {
                contact.append(contact.length() > 0 ? "    " : "").append("GSTIN: ").append(shop.gstin());
            }
            if (contact.length() > 0) {
                left.addElement(new Paragraph(latin(contact.toString()), onBand));
            }
            band.addCell(left);
            PdfPCell right = new PdfPCell();
            right.setBackgroundColor(INDIGO);
            right.setBorder(Rectangle.NO_BORDER);
            right.setPadding(14);
            Paragraph kind = new Paragraph(shop.taxEnabled() ? "TAX INVOICE" : "INVOICE", bandBold);
            kind.setAlignment(Element.ALIGN_RIGHT);
            Paragraph no = new Paragraph(inv.getInvoiceNo(), onBand);
            no.setAlignment(Element.ALIGN_RIGHT);
            Paragraph dt = new Paragraph(DT.format(clock.toLocal(inv.getSaleTime())), onBand);
            dt.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(kind);
            right.addElement(no);
            right.addElement(dt);
            band.addCell(right);
            doc.add(band);

            // ---- billed to / payment status boxes
            PdfPTable info = new PdfPTable(2);
            info.setWidthPercentage(100);
            info.setSpacingBefore(10);
            String cust = latin(inv.getCustomerName() == null || inv.getCustomerName().isBlank() ? "Walk-in customer" : inv.getCustomerName());
            info.addCell(box(label, norm, bold, "BILLED TO", cust,
                    inv.getCustomerPhone() == null ? "" : "Phone: " + inv.getCustomerPhone()));
            StringBuilder pm = new StringBuilder();
            for (int i = 0; i < pays.size(); i++) {
                PaymentRow p = pays.get(i);
                pm.append(i > 0 ? ", " : "").append(latin(p.methodName())).append(" Rs. ").append(IndianFormat.amount(p.amount()));
            }
            boolean due = inv.getBalanceDue().signum() > 0;
            info.addCell(box(label, norm, bold, "PAYMENT", due ? "BALANCE DUE  Rs. " + IndianFormat.amount(inv.getBalanceDue()) : "PAID IN FULL",
                    pm.toString()));
            doc.add(info);

            // ---- items
            boolean tax = shop.taxEnabled();
            PdfPTable t = new PdfPTable(tax ? new float[]{0.6f, 4.2f, 0.9f, 1.8f, 1.5f, 1.0f, 2.1f} : new float[]{0.6f, 5f, 1f, 1.8f, 1.6f, 2.1f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(12);
            t.setHeaderRows(1);
            t.setSplitLate(false);
            header(t, white, tax, "#", "ITEM", "QTY", "RATE", "DISC.", tax ? "GST%" : null, "AMOUNT");
            boolean alt = false;
            int n = 1;
            for (LineRow l : lines) {
                String pname = latin(l.productName());
                if (pname.replace("?", "").isBlank()) {
                    pname = l.sku();
                }
                StringBuilder desc = new StringBuilder(pname);
                String sub = null;
                if (l.colourName() != null || l.sizeName() != null) {
                    String c = l.colourName() == null ? "" : latin(l.colourName());
                    String sz = l.sizeName() == null ? "" : latin(l.sizeName());
                    sub = (c.isEmpty() ? "" : c) + (!c.isEmpty() && !sz.isEmpty() ? " / " : "") + (sz.isEmpty() ? "" : "Size " + sz);
                }
                BigDecimal disc = l.lineDiscount().add(l.invoiceDiscountShare());
                item(t, norm, small, alt, n++, desc.toString(), sub, String.valueOf(l.quantity()), IndianFormat.amount(l.unitPrice()),
                        disc.signum() == 0 ? "-" : IndianFormat.amount(disc),
                        tax ? l.taxRate().stripTrailingZeros().toPlainString() : null, IndianFormat.amount(l.lineTotal()));
                alt = !alt;
            }
            doc.add(t);

            int qtyTotal = 0;
            for (LineRow l : lines) {
                qtyTotal += l.quantity();
            }
            Paragraph count = new Paragraph("Items: " + lines.size() + "    Total quantity: " + qtyTotal, small);
            count.setSpacingBefore(6);
            doc.add(count);

            // ---- totals, right-aligned block with a coloured grand total
            PdfPTable totals = new PdfPTable(new float[]{5f, 3.4f});
            totals.setWidthPercentage(48);
            totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totals.setSpacingBefore(10);
            total(totals, norm, "Subtotal", inv.getSubtotal(), false);
            BigDecimal allDisc = inv.getLineDiscountTotal().add(inv.getInvoiceDiscount());
            if (allDisc.signum() > 0) {
                total(totals, norm, "Discount", allDisc.negate(), false);
            }
            if (tax) {
                total(totals, norm, "Taxable value", inv.getTaxableTotal(), false);
                total(totals, norm, "GST", inv.getTaxTotal(), false);
            }
            if (inv.getRoundOff().signum() != 0) {
                total(totals, norm, "Round off", inv.getRoundOff(), false);
            }
            PdfPCell gl = new PdfPCell(new Phrase("TOTAL", grand));
            gl.setBackgroundColor(GREEN);
            gl.setBorder(Rectangle.NO_BORDER);
            gl.setPadding(7);
            totals.addCell(gl);
            PdfPCell gv = new PdfPCell(new Phrase("Rs. " + IndianFormat.amount(inv.getTotal()), grand));
            gv.setBackgroundColor(GREEN);
            gv.setBorder(Rectangle.NO_BORDER);
            gv.setPadding(7);
            gv.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totals.addCell(gv);
            total(totals, norm, "Paid", inv.getAmountPaid(), false);
            if (due) {
                total(totals, bold, "Balance due", inv.getBalanceDue(), true);
            }
            doc.add(totals);

            PdfPTable thank = new PdfPTable(1);
            thank.setWidthPercentage(100);
            thank.setSpacingBefore(20);
            PdfPCell tc = new PdfPCell(new Phrase("Thank you for shopping with us! Please visit again.", white));
            tc.setBackgroundColor(INDIGO);
            tc.setBorder(Rectangle.NO_BORDER);
            tc.setPadding(8);
            tc.setHorizontalAlignment(Element.ALIGN_CENTER);
            thank.addCell(tc);
            doc.add(thank);
            if (!shop.footer().isBlank()) {
                Paragraph f = new Paragraph(latin(shop.footer()), norm);
                f.setAlignment(Element.ALIGN_CENTER);
                doc.add(f);
            }
            if (!shop.returnPolicy().isBlank()) {
                Paragraph rp = new Paragraph(latin(shop.returnPolicy()), small);
                rp.setAlignment(Element.ALIGN_CENTER);
                rp.setSpacingBefore(4);
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

    private static PdfPCell box(Font label, Font norm, Font bold, String title, String main, String extra) {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(new Color(0xE2, 0xE4, 0xF0));
        c.setBorderWidth(0.8f);
        c.setBackgroundColor(LIGHT);
        c.setPadding(9);
        c.addElement(new Paragraph(title, label));
        c.addElement(new Paragraph(main, bold));
        if (extra != null && !extra.isBlank()) {
            c.addElement(new Paragraph(extra, norm));
        }
        return c;
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

    private static void header(PdfPTable t, Font f, boolean tax, String... cols) {
        for (int i = 0; i < cols.length; i++) {
            if (cols[i] == null) {
                continue;
            }
            PdfPCell c = new PdfPCell(new Phrase(cols[i], f));
            c.setBackgroundColor(INDIGO);
            c.setBorder(Rectangle.NO_BORDER);
            c.setPaddingTop(7);
            c.setPaddingBottom(7);
            c.setPaddingLeft(6);
            c.setPaddingRight(6);
            c.setHorizontalAlignment(i == 1 ? Element.ALIGN_LEFT : (i == 0 || i == 2 ? Element.ALIGN_CENTER : Element.ALIGN_RIGHT));
            c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            t.addCell(c);
        }
    }

    private static void item(PdfPTable t, Font f, Font sub, boolean alt, int no, String name, String detail, String qty,
                             String rate, String disc, String gst, String amount) {
        cell(t, new Phrase(String.valueOf(no), f), alt, Element.ALIGN_CENTER);
        PdfPCell nameCell = new PdfPCell();
        nameCell.addElement(new Paragraph(name, f));
        if (detail != null && !detail.isBlank()) {
            nameCell.addElement(new Paragraph(detail, sub));
        }
        styled(nameCell, alt);
        nameCell.setHorizontalAlignment(Element.ALIGN_LEFT);
        t.addCell(nameCell);
        cell(t, new Phrase(qty, f), alt, Element.ALIGN_CENTER);
        cell(t, new Phrase(rate, f), alt, Element.ALIGN_RIGHT);
        cell(t, new Phrase(disc, f), alt, Element.ALIGN_RIGHT);
        if (gst != null) {
            cell(t, new Phrase(gst, f), alt, Element.ALIGN_RIGHT);
        }
        cell(t, new Phrase(amount, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9)), alt, Element.ALIGN_RIGHT);
    }

    private static void cell(PdfPTable t, Phrase p, boolean alt, int align) {
        PdfPCell c = new PdfPCell(p);
        styled(c, alt);
        c.setHorizontalAlignment(align);
        t.addCell(c);
    }

    private static void styled(PdfPCell c, boolean alt) {
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(new Color(0xE2, 0xE4, 0xF0));
        c.setPaddingTop(6);
        c.setPaddingBottom(6);
        c.setPaddingLeft(6);
        c.setPaddingRight(6);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        if (alt) {
            c.setBackgroundColor(LIGHT);
        }
    }

    private static void total(PdfPTable t, Font f, String label, BigDecimal v, boolean strong) {
        PdfPCell a = plain(new Phrase(label, f), Element.ALIGN_LEFT);
        PdfPCell b = plain(new Phrase("Rs. " + IndianFormat.amount(v), f), Element.ALIGN_RIGHT);
        a.setPadding(4);
        b.setPadding(4);
        t.addCell(a);
        t.addCell(b);
    }
}
