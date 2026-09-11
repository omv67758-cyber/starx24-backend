/**
 * STARX24 payment backend for Render.
 *
 * Secrets stay in Render environment variables:
 * - ZAPUPI_KEY
 * - FIREBASE_SERVICE_ACCOUNT (raw JSON or base64 JSON)
 * - FIREBASE_DATABASE_URL
 *
 * The Android app never receives the ZapUPI key. It sends a Firebase ID token
 * to this service, which creates and verifies orders with the Firebase Admin SDK.
 */

const express = require("express");
const admin = require("firebase-admin");
const { randomBytes } = require("node:crypto");

function readServiceAccount() {
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT?.trim();
  const base64 = process.env.FIREBASE_SERVICE_ACCOUNT_BASE64?.trim();
  const encoded = base64
    ? Buffer.from(base64, "base64").toString("utf8")
    : raw;

  if (!encoded) {
    throw new Error("Missing FIREBASE_SERVICE_ACCOUNT");
  }

  let serviceAccount;
  try {
    serviceAccount = JSON.parse(encoded);
  } catch (error) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT must be valid JSON or base64-encoded JSON");
  }

  if (!serviceAccount.project_id || !serviceAccount.client_email || !serviceAccount.private_key) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT is missing project_id, client_email, or private_key");
  }

  return {
    ...serviceAccount,
    private_key: serviceAccount.private_key.replace(/\\n/g, "\n"),
  };
}

const serviceAccount = readServiceAccount();
const databaseURL = process.env.FIREBASE_DATABASE_URL?.trim()
  || `https://${serviceAccount.project_id}-default-rtdb.firebaseio.com`;
const zapupiKey = process.env.ZAPUPI_KEY?.trim();
const zapupiMode = (process.env.ZAPUPI_MODE || "TEST").trim().toUpperCase();
const publicBaseUrl = (process.env.PUBLIC_BASE_URL?.trim()
  || process.env.RENDER_EXTERNAL_URL?.trim())?.replace(/\/+$/, "");

if (!zapupiKey) throw new Error("Missing ZAPUPI_KEY");
if (!publicBaseUrl) throw new Error("Missing PUBLIC_BASE_URL");

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL,
});

const db = admin.database();
const app = express();
const port = Number(process.env.PORT || 3000);
const CREATE_ORDER_URL = "https://pay.zapupi.com/api/create-order";
const ORDER_STATUS_URL = "https://pay.zapupi.com/api/order-status";

app.disable("x-powered-by");
app.use(express.json({ limit: "32kb" }));

app.get("/", (_req, res) => {
  res.status(200).json({ service: "STARX24 payment backend", status: "ok" });
});

app.get("/health", (_req, res) => {
  res.status(200).json({ status: "ok" });
});

function getBearerToken(req) {
  const header = req.headers.authorization || "";
  return header.startsWith("Bearer ") ? header.slice(7).trim() : "";
}

async function requireMaster(req, res) {
  const token = getBearerToken(req);
  if (!token) { res.status(401).json({ error: "Missing Firebase authorization token" }); return null; }
  try {
    const decoded = await admin.auth().verifyIdToken(token);
    const snap = await db.ref(`adminUsers/${decoded.uid}`).once("value");
    const account = snap.val();
    const bootstrap = String(decoded.email || "").toLowerCase() === "fflueclark@gmail.com";
    if (!String(decoded.email || "").toLowerCase().endsWith("@gmail.com") || (!bootstrap && (!account || account.enabled !== true || account.status !== "ACTIVE" || !["MASTER_CONTROL", "SUPER_ADMIN"].includes(account.role)))) {
      res.status(403).json({ error: "Master Control permission required" }); return null;
    }
    return decoded;
  } catch (_error) { res.status(401).json({ error: "Invalid Firebase authorization token" }); return null; }
}

app.post("/admin/provision", async (req, res) => {
  const actor = await requireMaster(req, res);
  if (!actor) return;
  const role = String(req.body?.role || "").trim();
  const email = String(req.body?.email || "").trim().toLowerCase();
  const displayName = String(req.body?.displayName || "").trim();
  if (!["PAYMENT_ADMIN", "MATCH_ADMIN"].includes(role)) return res.status(400).json({ error: "Role must be PAYMENT_ADMIN or MATCH_ADMIN" });
  if (!/^[^\s@]+@[^\s@]+\.com$/.test(email)) return res.status(400).json({ error: "A valid email address is required" });
  const temporaryPassword = `Sx${randomBytes(9).toString("base64url")}9!`;
  try {
    const user = await admin.auth().createUser({ email, password: temporaryPassword, displayName: displayName || undefined });
    const now = admin.database.ServerValue.TIMESTAMP;
    await db.ref().update({
      [`adminUsers/${user.uid}`]: { uid: user.uid, email, displayName, role, enabled: true, status: "ACTIVE", createdAt: now, updatedAt: now, permissions: role === "PAYMENT_ADMIN" ? { withdrawals: "READ_WRITE", matches: "DENIED" } : { matches: "READ_WRITE", payments: "DENIED" } },
      [`activity_logs/${db.ref("activity_logs").push().key}`]: { admin: actor.uid, role: "MASTER_CONTROL", action: "ADMIN_CREATED", target: user.uid, timestamp: now, details: role }
    });
    return res.status(201).json({ uid: user.uid, email, role, temporaryPassword, message: "Store this password securely; it will not be shown again." });
  } catch (error) {
    if (error?.code === "auth/email-already-exists") return res.status(409).json({ error: "This email already has a Firebase account" });
    console.error("admin provision error", error.message);
    return res.status(500).json({ error: "Could not provision admin account" });
  }
});

function validOrderId(orderId) {
  return typeof orderId === "string" && /^STARX[0-9]+_[a-z0-9]{6}$/.test(orderId);
}

async function createZapupiOrder(orderId, amount, mobile) {
  const response = await fetch(CREATE_ORDER_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      zap_key: zapupiKey,
      order_id: orderId,
      amount: String(amount),
      customer_mobile: mobile || undefined,
      remark: `STARX24 wallet | ${orderId}`,
      webhook_url: `${publicBaseUrl}/zapupiWebhook`,
    }),
  });

  const text = await response.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch (_error) {
    data = {};
  }

  return { response, data };
}

function paymentProviderError(data, statusCode) {
  const providerMessage = String(data?.message || data?.error || "").trim();
  if (/invalid zap key/i.test(providerMessage)) {
    return "Payment gateway key is invalid or expired. Update ZAPUPI_KEY in Render.";
  }
  if (/key/i.test(providerMessage) && /(missing|expired|inactive|disabled)/i.test(providerMessage)) {
    return "Payment gateway key is not active. Check ZAPUPI_KEY in Render.";
  }
  if (providerMessage) {
    console.error("ZapUPI provider message", { statusCode, providerMessage });
  }
  return "Payment provider rejected the order. Please try again.";
}

app.post("/createOrder", async (req, res) => {
  const idToken = getBearerToken(req);
  if (!idToken) return res.status(401).json({ error: "Missing Firebase authorization token" });

  let decoded;
  try {
    decoded = await admin.auth().verifyIdToken(idToken);
  } catch (_error) {
    return res.status(401).json({ error: "Invalid Firebase authorization token" });
  }

  const amount = Number(req.body?.amount);
  if (!Number.isInteger(amount) || amount < 1 || amount > 100000) {
    return res.status(400).json({ error: "Invalid amount" });
  }

  const mobile = String(req.body?.mobile || "").trim();
  if (mobile && !/^[0-9+() -]{7,20}$/.test(mobile)) {
    return res.status(400).json({ error: "Invalid mobile number" });
  }

  const orderId = `STARX${Date.now()}_${randomBytes(3).toString("hex")}`;
  const orderRef = db.ref(`orders/${orderId}`);

  try {
    await orderRef.set({
      uid: decoded.uid,
      amount,
      status: "pending",
      mode: zapupiMode,
      createdAt: admin.database.ServerValue.TIMESTAMP,
    });

    const { response, data } = await createZapupiOrder(orderId, amount, mobile);
    const providerStatus = String(data?.status || "").trim().toLowerCase();
    if (!response.ok || providerStatus !== "success" || !data.payment_url) {
      await orderRef.update({ status: "create_failed" }).catch((updateError) => {
        console.error("Could not mark failed order", updateError.message);
      });
      console.error("ZapUPI create-order rejected", {
        httpStatus: response.status,
        providerStatus,
        providerMessage: data.message || data.error || "",
      });
      return res.status(502).json({
        error: paymentProviderError(data, response.status),
      });
    }

    return res.status(200).json({
      order_id: orderId,
      payment_url: data.payment_url,
    });
  } catch (error) {
    console.error("createOrder error", error.message);
    await orderRef.update({ status: "create_failed" }).catch(() => {});
    return res.status(500).json({ error: "Payment service could not save the order. Please try again." });
  }
});

// ZapUPI dashboard test pings can be GET requests.
app.get("/zapupiWebhook", (_req, res) => res.status(200).send("ok"));

async function getVerifiedOrderStatus(orderId) {
  const response = await fetch(ORDER_STATUS_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ zap_key: zapupiKey, order_id: orderId }),
  });
  const text = await response.text();
  try {
    return response.ok ? JSON.parse(text) : {};
  } catch (_error) {
    return {};
  }
}

async function creditWalletOnce(order) {
  const walletRef = db.ref(`users/${order.uid}/wallet`);
  const result = await walletRef.transaction((current) => {
    const wallet = current && typeof current === "object" ? { ...current } : {};
    const creditedOrders = wallet.creditedOrders && typeof wallet.creditedOrders === "object"
      ? { ...wallet.creditedOrders }
      : {};

    if (creditedOrders[order.orderId]) return;

    const currentBalance = Number(wallet.balance || 0);
    wallet.balance = currentBalance + Number(order.amount);
    creditedOrders[order.orderId] = {
      amount: Number(order.amount),
      creditedAt: admin.database.ServerValue.TIMESTAMP,
    };
    wallet.creditedOrders = creditedOrders;
    return wallet;
  });

  if (result.committed) {
    // Record a DEPOSIT entry so the app's Wallet history list (which reads
    // users/<uid>/wallet/transactions) shows this top-up, matching the
    // Transaction model's fields (type/title/amount/timestamp/status).
    await db.ref(`users/${order.uid}/wallet/transactions`).push({
      type: "DEPOSIT",
      title: "Wallet Top-up",
      amount: Number(order.amount),
      timestamp: admin.database.ServerValue.TIMESTAMP,
      status: "SUCCESS",
    });
  }

  return result.committed;
}

/**
 * Shared settlement logic used by BOTH the ZapUPI webhook and the app's
 * manual /verifyOrder call. Re-checks the real status with ZapUPI itself
 * (never trusts the caller), credits the wallet at most once, and returns
 * the final order status. Safe to call multiple times for the same order.
 */
async function settleOrder(orderId, storedOrder) {
  if (storedOrder.status === "credited") return "credited";
  if (storedOrder.status === "failed") return "failed";

  const statusData = await getVerifiedOrderStatus(orderId);
  const verifiedStatus = String(statusData?.status || statusData?.data?.status || "").trim().toLowerCase();

  if (verifiedStatus === "success") {
    const order = { ...storedOrder, orderId };
    await db.ref(`orders/${orderId}/status`).transaction((current) => {
      if (current === "pending" || current === "processing") return "processing";
      return;
    });

    await creditWalletOnce(order);
    await db.ref(`orders/${orderId}`).update({
      status: "credited",
      creditedAt: admin.database.ServerValue.TIMESTAMP,
    });
    return "credited";
  }

  if (verifiedStatus === "failed") {
    await db.ref(`orders/${orderId}/status`).set("failed");
    return "failed";
  }

  return "pending";
}

app.post("/zapupiWebhook", async (req, res) => {
  const orderId = req.body?.order_id || req.body?.data?.order_id;
  if (!orderId) return res.status(200).send("ok");
  // ZapUPI's dashboard test may send a placeholder order ID. It must receive
  // a successful HTTP response, but it must never be allowed to touch Firebase.
  if (!validOrderId(orderId)) return res.status(200).send("ok - test ping");

  try {
    const orderSnapshot = await db.ref(`orders/${orderId}`).once("value");
    const storedOrder = orderSnapshot.val();
    // Ignore unknown provider probes without creating a retry storm. Real
    // orders are always created in Firebase before ZapUPI is called.
    if (!storedOrder) return res.status(200).send("ok");

    await settleOrder(orderId, storedOrder);
    return res.status(200).send("ok");
  } catch (error) {
    console.error("webhook error", error.message);
    // Returning 200 avoids unbounded retries for a provider callback that we
    // have already logged. The order remains non-credited and can be retried
    // from the provider dashboard.
    return res.status(200).send("logged");
  }
});

// Called by the app itself the moment the user returns from the payment page
// (Custom Tab closed / app resumed). This is a same-second fallback so coins
// land immediately instead of waiting on ZapUPI's webhook delivery, which can
// be slow or, on a sleeping free-tier Render instance, missed entirely.
app.post("/verifyOrder", async (req, res) => {
  const idToken = getBearerToken(req);
  if (!idToken) return res.status(401).json({ error: "Missing Firebase authorization token" });

  let decoded;
  try {
    decoded = await admin.auth().verifyIdToken(idToken);
  } catch (_error) {
    return res.status(401).json({ error: "Invalid Firebase authorization token" });
  }

  const orderId = String(req.body?.order_id || "").trim();
  if (!validOrderId(orderId)) return res.status(400).json({ error: "Invalid order id" });

  try {
    const orderSnapshot = await db.ref(`orders/${orderId}`).once("value");
    const storedOrder = orderSnapshot.val();
    if (!storedOrder) return res.status(404).json({ error: "Order not found" });
    // Only the user who created the order can trigger a settlement check for it.
    if (storedOrder.uid !== decoded.uid) return res.status(403).json({ error: "Order does not belong to this user" });

    const status = await settleOrder(orderId, storedOrder);
    return res.status(200).json({ status });
  } catch (error) {
    console.error("verifyOrder error", error.message);
    return res.status(500).json({ error: "Could not verify order right now. Please try again." });
  }
});

app.listen(port, () => {
  console.log(`STARX24 payment backend listening on port ${port}`);
});
