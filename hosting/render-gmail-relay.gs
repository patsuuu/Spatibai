function doPost(event) {
  const properties = PropertiesService.getScriptProperties();
  const expectedSecret = properties.getProperty("GMAIL_BRIDGE_SECRET") || "";
  let request;
  try {
    request = JSON.parse(event.postData.contents);
  } catch (error) {
    return jsonResponse({ ok: false, error: "invalid_request" });
  }

  if (!request || typeof request !== "object" || Array.isArray(request)) {
    return jsonResponse({ ok: false, error: "invalid_request" });
  }
  if (expectedSecret.length < 32 ||
      !constantTimeEquals(String(request.secret || ""), expectedSecret)) {
    return jsonResponse({ ok: false, error: "unauthorized" });
  }

  const email = String(request.email || "").trim();
  const name = String(request.name || "").replace(/[\r\n\0]/g, " ").trim().slice(0, 80);
  const code = String(request.code || "").trim();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ||
      !name || !/^\d{6}$/.test(code)) {
    return jsonResponse({ ok: false, error: "invalid_request" });
  }

  try {
    MailApp.sendEmail({
      to: email,
      subject: "Verify your Spatibai account",
      body: `Hi ${name},\n\nYour Spatibai verification code is: ${code}\n\n` +
        "It expires in 15 minutes. If you did not request this, you can ignore this email.",
      name: "Spatibai",
    });
    return jsonResponse({ ok: true });
  } catch (error) {
    console.error("Email send failed: " + error.message);
    return jsonResponse({ ok: false, error: "delivery_failed" });
  }
}

function constantTimeEquals(actual, expected) {
  if (actual.length !== expected.length) return false;
  let difference = 0;
  for (let index = 0; index < actual.length; index++) {
    difference |= actual.charCodeAt(index) ^ expected.charCodeAt(index);
  }
  return difference === 0;
}

function jsonResponse(value) {
  return ContentService.createTextOutput(JSON.stringify(value))
    .setMimeType(ContentService.MimeType.JSON);
}
