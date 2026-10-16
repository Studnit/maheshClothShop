# Sending bills on WhatsApp from the owner Business account

After every bill the app can send the **bill PDF** to the customer from the shop owner WhatsApp Business account,
with a thank-you message in English or Marathi. A copy of the PDF is kept on our side and every send is listed under
**WhatsApp History** (Queued, Sent, Delivered, Read, Failed - with a Send again button).

There are two ways. Use either or both.

## 1. "Share on WhatsApp" button - works today, nothing to set up

On the bill-done screen and the bill page, press **Send on WhatsApp**. WhatsApp opens (phone or WhatsApp Web) with the
customer chat and a ready thank-you text that contains a download link of the PDF. The cashier presses Send.
It is recorded in history as "Share link / Link opened". Set **Settings -> WhatsApp -> Public web address** so the link
works for customers.

Limit: the app cannot know whether the cashier actually pressed Send, so this mode never shows Delivered or Read.

## 2. Automatic sending with the WhatsApp Business Platform (Cloud API)

The PDF goes out automatically right after the bill is completed. Meta controls this channel, so some things are
required by Meta, not by this app:

1. **A Meta business account and a WhatsApp Business Platform phone number.** Create an app at
   developers.facebook.com (type: Business), add the WhatsApp product, and add the shop number.
   Meta changes the rules for using the same number in the WhatsApp Business app and the API from time to time
   (there is a "coexistence" option in some countries). Read Meta current guidance before moving the number.
2. **A message template.** WhatsApp only lets a business start a conversation with an approved template. Create it in
   WhatsApp Manager -> Message templates:
   * Name: `invoice_thank_you` (or change the name in Settings)
   * Category: **Utility**
   * Header: **Document**
   * Language: **Marathi (`mr`)**
   * Body (no variables - paste exactly):
     ```
     🙏 महेश क्लॉथ शॉपमध्ये आपले हार्दिक स्वागत! 🙏
     आमच्या महेश क्लॉथ शॉपला भेट दिल्याबद्दल आपले मनःपूर्वक आभार! ❤️
     आपला विश्वास आणि प्रेम हेच आमच्यासाठी सर्वात मोठे बक्षीस आहे. 😊
     आपली सेवा करण्याची संधी दिल्याबद्दल धन्यवाद! पुन्हा एकदा नक्की भेट द्या. 🛍️
     महेश क्लॉथ शॉप
     ✨ आपला विश्वास, आमची गुणवत्ता! ✨
     ```
   Approval usually takes minutes to a day. The app sends this template with the bill PDF as the document header.
   The same text is used for the "Send on WhatsApp" link fallback (plus a download link).
3. **A permanent access token** (System User token with `whatsapp_business_messaging` and `whatsapp_business_management`).
4. **Customer consent.** The cashier ticks "Customer agrees to receive this bill on WhatsApp" on the billing screen;
   nothing is sent without it. Do not message people who did not agree - it can get the number blocked.

### Put the values in the app

Secrets are **environment variables** (see `deploy/.env.example`), never typed into Settings:

| Variable | Where to find it |
|---|---|
| `WHATSAPP_ACCESS_TOKEN` | System User token |
| `WHATSAPP_APP_SECRET` | App settings -> Basic -> App secret (used to verify delivery webhooks) |
| `WHATSAPP_VERIFY_TOKEN` | any long random text you choose; you paste the same text in the webhook setup |

Then in **Settings -> WhatsApp**: turn on *Send bills through WhatsApp Business API*, enter the **Phone number ID**
(WhatsApp -> API setup), the template name and languages, and the **Public web address** of this app.

### Delivery status (Delivered / Read)

In the Meta app: WhatsApp -> Configuration -> Webhook:
* Callback URL: `https://<your domain>/webhooks/whatsapp`
* Verify token: the value of `WHATSAPP_VERIFY_TOKEN`
* Subscribe to the **messages** field.

The app rejects webhook calls whose HMAC-SHA256 signature does not match `WHATSAPP_APP_SECRET`.

### Which language is sent?

Automatic sending uses the shop default language (**Settings -> System -> Default language**), so set it to the
language most of your customers read; the matching template language (`en` or `mr`) must be approved. The
"Send on WhatsApp" share button can send either language for a single bill. The PDF itself is in English: the PDF
engine uses a built-in Latin font, so Marathi letters would print as "?". The on-screen bill, the print version and
the WhatsApp text are fully Marathi when Marathi is selected. See REVIEW_AND_DECISIONS.md.

### If a send fails

History shows the reason (invalid number, template not approved, token expired, ...). Fix it and press **Send again**.
A failed send never blocks or undoes the bill.

## What was and was not tested

The message building, phone-number cleaning, webhook signature check and the English/Marathi texts are covered by
automated tests. A live send to Meta needs your credentials and an approved template, so it must be tried once by you:
make a bill for your own number with consent ticked and watch History.
