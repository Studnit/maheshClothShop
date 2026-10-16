# Printing

## A4 bill
Bills -> open a bill -> **Print A4**. Uses the browser print dialog; choose A4, margins "Default", turn off headers
and footers. **Download PDF** gives the same bill as a PDF file.

## Thermal receipt (58 mm or 80 mm)
Open a bill -> **Print receipt**. Set the paper width once in Settings -> Billing (58 or 80 mm). In the browser
print dialog choose your thermal printer, paper size to match the roll, margins "None" (or minimum), scale 100%.
Any ESC/POS thermal printer that has an operating-system driver works, over USB, Bluetooth or network.
For one-click printing without the dialog, start Chrome/Edge with `--kiosk-printing` on the billing computer.

## Barcode labels
Open a product and press **Labels** (or go to Inventory -> Labels and pick the product). Set the number of copies and
print. Each label shows the shop name, product, size/colour and a Code 128 barcode of the variant barcode, or of the
SKU when no barcode is stored. A normal USB barcode scanner works: it types the code and presses Enter, and the billing
search finds the variant by variant barcode, product barcode or SKU. Print on label sheets or a label printer.
